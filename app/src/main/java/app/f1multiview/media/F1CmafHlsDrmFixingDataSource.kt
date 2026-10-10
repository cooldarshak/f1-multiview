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
        val fixed = rewritePlaylistText(text).toByteArray(StandardCharsets.UTF_8)
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
                // Read at most one byte beyond the budget so unknown or dishonest
                // Content-Length values cannot make playlist buffering unbounded.
                val remaining = MAX_PLAYLIST_BYTES - output.size()
                val requested = min(buffer.size.toLong(), remaining.toLong() + 1L).toInt()
                val read = upstream.read(buffer, 0, requested)
                if (read == C.RESULT_END_OF_INPUT) break
                if (output.size().toLong() + read > MAX_PLAYLIST_BYTES) {
                    throw IOException("F1 CMAF HLS playlist exceeds ${MAX_PLAYLIST_BYTES} byte limit")
                }
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

    internal companion object {
        // HLS playlists are small control documents, not media payloads. Fail closed
        // rather than buffering an unbounded response in memory before parsing.
        const val MAX_PLAYLIST_BYTES = 2 * 1024 * 1024

        /**
         * Rewrites only the documented F1 UHD/HDR Widevine playlist declaration.
         * The KEYFORMAT value is ordinary quoted playlist text, not backslash-escaped text.
         */
        private const val WIDEVINE_KEY_FORMAT =
            "KEYFORMAT=\"urn:uuid:edef8ba9-79d6-4ace-a3c8-27dcd51d21ed\""

        fun rewritePlaylistText(text: String): String {
            if (!text.startsWith("#EXTM3U")) return text
            if (!text.contains(WIDEVINE_KEY_FORMAT)) return text
            // Encryption methods belong to individual EXT-X-KEY declarations. Do not
            // rewrite an unrelated key format merely because another line is Widevine.
            return Regex("(?m)^#EXT-X-KEY:[^\\r\\n]*$").replace(text) { match ->
                val line = match.value
                if (line.contains(WIDEVINE_KEY_FORMAT) && line.contains("METHOD=SAMPLE-AES-CTR")) {
                    line.replace("METHOD=SAMPLE-AES-CTR", "METHOD=SAMPLE-AES")
                } else {
                    line
                }
            }
        }
    }
}
