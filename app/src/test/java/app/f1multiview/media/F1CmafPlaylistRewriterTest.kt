package app.f1multiview.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
class F1CmafPlaylistRewriterTest {
    private val widevineKeyFormat =
        "KEYFORMAT=\"urn:uuid:edef8ba9-79d6-4ace-a3c8-27dcd51d21ed\""

    @Test
    fun rewritesSampleAesCtrForF1WidevinePlaylistWithNormalQuotedKeyFormat() {
        val input = """#EXTM3U
#EXT-X-KEY:METHOD=SAMPLE-AES-CTR,URI="data:text/plain;base64,AA==",$widevineKeyFormat
#EXTINF:2.0,
segment.m4s
"""
        val result = F1CmafHlsDrmFixingDataSource.rewritePlaylistText(input)
        assertEquals(true, result.contains("METHOD=SAMPLE-AES,"))
        assertEquals(false, result.contains("METHOD=SAMPLE-AES-CTR"))
        assertEquals(true, result.contains(widevineKeyFormat))
    }


    @Test
    fun rewritesOnlyWidevineKeyDeclarationInMixedPlaylist() {
        val identityKey = "#EXT-X-KEY:METHOD=SAMPLE-AES-CTR,URI=\"key.bin\",KEYFORMAT=\"identity\""
        val input = """#EXTM3U
#EXT-X-KEY:METHOD=SAMPLE-AES-CTR,URI="data:text/plain;base64,AA==",$widevineKeyFormat
$identityKey
#EXTINF:2.0,
segment.m4s
"""
        val result = F1CmafHlsDrmFixingDataSource.rewritePlaylistText(input)

        assertEquals(true, result.contains("METHOD=SAMPLE-AES,URI=\"data:text/plain;base64,AA==\",$widevineKeyFormat"))
        assertEquals(true, result.contains(identityKey))
    }

    @Test
    fun leavesUnrelatedPlaylistEncryptionUnchanged() {
        val input = """#EXTM3U
#EXT-X-KEY:METHOD=SAMPLE-AES-CTR,URI="key.bin",KEYFORMAT="identity"
"""
        assertEquals(input, F1CmafHlsDrmFixingDataSource.rewritePlaylistText(input))
    }

    @Test
    fun leavesNonPlaylistTextUnchanged() {
        val input = "not a playlist $widevineKeyFormat METHOD=SAMPLE-AES-CTR"
        assertEquals(input, F1CmafHlsDrmFixingDataSource.rewritePlaylistText(input))
    }

    @Test
    fun rejectsOversizedTargetPlaylistWithoutUnboundedBuffering() {
        val payload = ByteArray(F1CmafHlsDrmFixingDataSource.MAX_PLAYLIST_BYTES + 100)
        val upstream = FakeDataSource(payload)
        val source = F1CmafHlsDrmFixingDataSource.Factory(object : DataSource.Factory {
            override fun createDataSource(): DataSource = upstream
        }).createDataSource()
        val spec = DataSpec.Builder()
            .setUri(Uri.parse("https://example.test/HDR-UHD-CMAF-WV/playlist.m3u8"))
            .build()

        assertThrows(IOException::class.java) { source.open(spec) }
        assertEquals(F1CmafHlsDrmFixingDataSource.MAX_PLAYLIST_BYTES + 1, upstream.bytesRead)
        assertEquals(1, upstream.closeCount)
    }


    @Test
    fun rewrittenPlaylistExposesResolvedRedirectUriAndCorrectContentLength() {
        val body = """#EXTM3U
#EXT-X-KEY:METHOD=SAMPLE-AES-CTR,URI="data:text/plain;base64,AA==",$widevineKeyFormat
#EXTINF:2.0,
segment.m4s
""".toByteArray()
        val resolvedUri = Uri.parse("https://cdn.example.test/final/HDR-UHD-CMAF-WV/index.m3u8")
        val upstream = FakeDataSource(
            body,
            resolvedUri = resolvedUri,
            headers = mapOf(
                "Content-Length" to listOf(body.size.toString()),
                "Content-Range" to listOf("bytes 0-${body.size - 1}/${body.size}")
            )
        )
        val source = F1CmafHlsDrmFixingDataSource.Factory(object : DataSource.Factory {
            override fun createDataSource(): DataSource = upstream
        }).createDataSource()
        source.open(DataSpec.Builder()
            .setUri(Uri.parse("https://origin.example.test/redirect/HDR-UHD-CMAF-WV/index.m3u8"))
            .build())

        val rewritten = ByteArrayOutputStreamForTest()
        val buffer = ByteArray(256)
        while (true) {
            val read = source.read(buffer, 0, buffer.size)
            if (read == C.RESULT_END_OF_INPUT) break
            rewritten.write(buffer, read)
        }
        assertEquals(resolvedUri, source.uri)
        assertEquals(rewritten.size.toString(), source.responseHeaders["Content-Length"]?.single())
        assertEquals(false, source.responseHeaders.keys.any { it.equals("Content-Range", true) })
        source.close()
    }

    private class ByteArrayOutputStreamForTest {
        private val bytes = java.io.ByteArrayOutputStream()
        val size: Int get() = bytes.size()
        fun write(buffer: ByteArray, count: Int) { bytes.write(buffer, 0, count) }
    }

    private class FakeDataSource(
        private val payload: ByteArray,
        private val resolvedUri: Uri = Uri.parse("https://example.test/HDR-UHD-CMAF-WV/playlist.m3u8"),
        private val headers: Map<String, List<String>> = emptyMap()
    ) : DataSource {
        var bytesRead = 0
            private set
        var closeCount = 0
            private set

        override fun addTransferListener(transferListener: TransferListener) = Unit
        override fun open(dataSpec: DataSpec): Long = payload.size.toLong()

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (bytesRead >= payload.size) return C.RESULT_END_OF_INPUT
            val count = minOf(length, payload.size - bytesRead)
            payload.copyInto(buffer, offset, bytesRead, bytesRead + count)
            bytesRead += count
            return count
        }

        override fun getUri(): Uri = resolvedUri
        override fun getResponseHeaders(): Map<String, List<String>> = headers
        override fun close() { closeCount++ }
    }
}
