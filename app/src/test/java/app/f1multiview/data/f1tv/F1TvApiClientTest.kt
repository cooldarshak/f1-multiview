package app.f1multiview.data.f1tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class F1TvApiClientTest {

    @Test
    fun prioritizesServerConfiguredPlaybackApiVersion() {
        assertEquals(listOf("3.0", "2.0"), F1TvApiClient.apiVersionCandidates("3.0", listOf("2.0", "3.0")))
    }

    @Test
    fun rejectsInvalidConfiguredApiVersionAndUsesLegacyFallbacks() {
        assertEquals(listOf("2.0", "3.0"), F1TvApiClient.apiVersionCandidates("../3.0", listOf("2.0", "3.0")))
    }

    @Test
    fun preservesConfiguredVideoApiVersionAndLegacyFallback() {
        assertEquals(listOf("4.0", "3.0"), F1TvApiClient.apiVersionCandidates("4.0", listOf("4.0", "3.0")))
    }

    @Test
    fun parsesAuthorizedManifestAndWidevineFieldsWithoutPrivateMultiviewMetadata() {
        val response = HttpResponse(
            200, true,
            """{"url":"https://example.com/live.mpd","laURL":"https://license.example/license","drmToken":"redacted","playApiVersion":"3.0","streamType":"DASH"}"""
        )
        val parsed = F1TvApiClient().parsePlaybackResponse(response, "content-1", "channel-1", "WEB_DASH")
        assertEquals("https://example.com/live.mpd", parsed.manifestUrl)
        assertEquals("https://license.example/license", parsed.licenseUrl)
        assertEquals("3.0", parsed.playApiVersion)
        assertEquals("DASH", parsed.streamType)
    }

    @Test
    fun acceptsManifestInResultObjectCompatibilityEnvelope() {
        val response = HttpResponse(200, true, """{"resultObj":{"manifestUrl":"https://example.com/replay.m3u8"}}""")
        val parsed = F1TvApiClient().parsePlaybackResponse(response, "content-2", null, "WEB_HLS")
        assertEquals("https://example.com/replay.m3u8", parsed.manifestUrl)
    }

    @Test(expected = F1TvException::class)
    fun rejectsPlaybackResponseWithoutManifest() {
        F1TvApiClient().parsePlaybackResponse(HttpResponse(200, true, """{"resultObj":{"title":"not a manifest"}}"""), "content-3", null, "WEB_DASH")
    }
}
