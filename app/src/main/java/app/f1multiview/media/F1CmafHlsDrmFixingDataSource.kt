package app.f1multiview.media

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import kotlin.math.min

/**
 * F1 UHD CMAF HLS playlists can advertise SAMPLE-AES-CTR while the protected
 * CMAF init segments are cbcs. Media3 maps the former to cenc. Rewrite only
 * the known F1 HDR/UHD Widevine playlist shape before HlsPlaylistParser sees it.
 */
class F1CmafHlsDrmFixingDataSource private constructor(
    private val upstream: DataSource
) : DataSource {

    class Factory(private val upstreamFactory: DataSource.Factory) : DataSource.Factory {
        override fun createDataSource(): DataSource = F1CmafHlsDrmFixingDataSource(upstreamFactory.createDataSource())
    }

    private var openedUri: Uri? = null
    private var responseHeaders: Map<String, List<String>> = emptyMap()
    private var memoryData: ByteArray? = null
    private var memoryPosition = 0
    private var memoryLimit = 0
    private var upstreamOpened = false

    override fun addTransferListener(transferListener: TransferListener) {
        upstream.addTransferListener(transferListener)
    }

    @Throws(IOException::class)
    override fun open(dataSpec: DataSpec): Long {
        close()
        openedUri = dataSpec.uri
        if (!shouldRewritePlaylist(dataSpec.uri)) {
            val length = upstream.open(dataSpec)
            upstreamOpened = true
            responseHeaders = upstream.responseHeaders
            return length
        }

        val original = readUpstreamFully(dataSpec)
        val text = original.toString(StandardCharsets.UTF_8)
        val fixed = rewritePlaylist(text).toByteArray(StandardCharsets.UTF_8)
        val start = dataSpec.position.coerceAtMost(fixed.size.toLong()).toInt()
        val end = if (dataSpec.length == C.LENGTH_UNSET.toLong()) {
            fixed.size
        } else {
            min(fixed.size.toLong(), dataSpec.position + dataSpec.length).toInt()
        }
        memoryData = fixed
        memoryPosition = start
        memoryLimit = end
        return (end - start).toLong()
    }

    @Throws(IOException::class)
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val data = memoryData ?: return upstream.read(buffer, offset, length)
        if (length == 0) return 0
        if (memoryPosition >= memoryLimit) return C.RESULT_END_OF_INPUT
        val count = min(length, memoryLimit - memoryPosition)
        data.copyInto(buffer, offset, memoryPosition, memoryPosition + count)
        memoryPosition += count
        return count
    }

    override fun getUri(): Uri? = openedUri ?: upstream.uri

    override fun getResponseHeaders(): Map<String, List<String>> = responseHeaders

    @Throws(IOException::class)
    override fun close() {
        memoryData = null
        memoryPosition = 0
        memoryLimit = 0
        if (upstreamOpened) {
            upstreamOpened = false
            upstream.close()
        }
    }

    @Throws(IOException::class)
    private fun readUpstreamFully(dataSpec: DataSpec): ByteArray {
        try {
            upstream.open(dataSpec)
            upstreamOpened = true
            responseHeaders = upstream.responseHeaders
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(16 * 1024)
            while (true) {
                val read = upstream.read(buffer, 0, buffer.size)
                if (read == C.RESULT_END_OF_INPUT) break
                output.write(buffer, 0, read)
            }
            return output.toByteArray()
        } finally {
            if (upstreamOpened) {
                upstreamOpened = false
                upstream.close()
            }
        }
    }

    private fun shouldRewritePlaylist(uri: Uri): Boolean {
        val url = uri.toString()
        return url.contains("HDR-UHD-CMAF-WV", true) && url.contains(".m3u8", true)
    }

    private fun rewritePlaylist(text: String): String {
        if (!text.startsWith("#EXTM3U")) return text
        if (!text.contains("KEYFORMAT=\\\"urn:uuid:edef8ba9-79d6-4ace-a3c8-27dcd51d21ed\\\"")) return text
        return text.replace("METHOD=SAMPLE-AES-CTR", "METHOD=SAMPLE-AES")
    }
}
