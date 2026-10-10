package app.f1multiview.media

import org.junit.Assert.assertEquals
import org.junit.Test

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
}
