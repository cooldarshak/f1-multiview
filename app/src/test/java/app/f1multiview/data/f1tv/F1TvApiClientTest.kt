package app.f1multiview.data.f1tv

import org.junit.Assert.assertEquals
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
    fun prefersNestedPlaybackProfileDrmMetadataOverGenericEnvelope() {
        val response = HttpResponse(
            200, true,
            """{"url":"https://example.com/live.mpd","streamType":"DASH_SINGLE","drmType":"unknown","pipelineVersion":6,"resultObj":{"streamType":"SDR_HD_DASHWV_SINGLE","drmType":"widevine"}}"""
        )
        val parsed = F1TvApiClient().parsePlaybackResponse(response, "content-nested", null, "WEB_DASH")
        assertEquals("SDR_HD_DASHWV_SINGLE", parsed.streamType)
        assertEquals("widevine", parsed.drmType)
        org.junit.Assert.assertTrue(parsed.licenseUrl.orEmpty().contains("/CONTENT/LA/widevine?contentId=content-nested"))
    }

    @Test
    fun doesNotInferWidevineFromPipelineVersionAlone() {
        val response = HttpResponse(
            200, true,
            """{"url":"https://example.com/live.mpd","pipelineVersion":6,"streamType":"SDR_HD_DASH_SINGLE"}"""
        )
        val parsed = F1TvApiClient().parsePlaybackResponse(response, "content-4", null, "WEB_DASH")
        assertEquals(null, parsed.licenseUrl)
    }

    @Test
    fun synthesizesLicenseOnlyWhenF1ExplicitlyDeclaresWidevine() {
        val api = F1TvApiClient()
        assertEquals(
            null,
            api.fallbackLicense("content-5", null, "WEB_DASH", 6, "SDR_HD_DASH_SINGLE", "unknown")
        )
        val license = api.fallbackLicense("content-6", null, "WEB_DASH", 6, "SDR_HD_DASHWV_SINGLE", "widevine")
        org.junit.Assert.assertTrue(license.orEmpty().contains("/CONTENT/LA/widevine?contentId=content-6"))
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

    @Test
    fun detectsWidevineOnlyWhenManifestDeclaresItsSystemUuid() {
        val api = F1TvApiClient()
        assertEquals(
            true,
            api.containsWidevineUuid("<ContentProtection schemeIdUri=\"urn:uuid:edef8ba9-79d6-4ace-a3c8-27dcd51d21ed\"/>")
        )
        assertEquals(false, api.containsWidevineUuid("<ContentProtection schemeIdUri=\"urn:uuid:9a04f079-9840-4286-ab92-e65be0885f95\"/>"))
        assertEquals(false, api.containsWidevineUuid("<MPD><Period/></MPD>"))
    }
}
