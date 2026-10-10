package app.f1multiview.media

import app.f1multiview.model.StreamKind
import app.f1multiview.model.StreamSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthorizedWidevineDrmSessionFactoryTest {
    private fun stream(
        licenseUrl: String? = "https://license.example.test/widevine",
        requestHeaders: Map<String, String> = emptyMap(),
        drmRequestHeaders: Map<String, String> = emptyMap(),
        playToken: String? = null
    ) = StreamSource(
        id = "feed",
        title = "Authorized feed",
        kind = StreamKind.WORLD,
        url = "https://stream.example.test/manifest.mpd",
        drmLicenseUrl = licenseUrl,
        requestHeaders = requestHeaders,
        drmRequestHeaders = drmRequestHeaders,
        playToken = playToken,
        drmProtected = true
    )

    @Test
    fun refusesToCreateConfigurationWithoutAuthorizedLicenseEndpoint() {
        assertNull(AuthorizedWidevineDrmSessionFactory.configurationFor(stream(licenseUrl = null)))
        assertNull(AuthorizedWidevineDrmSessionFactory.configurationFor(stream(licenseUrl = "  ")))
        assertNull(AuthorizedWidevineDrmSessionFactory.createSessionManager(stream(licenseUrl = null)))
    }

    @Test
    fun usesDrmHeadersWhenProvidedAndPreservesExistingPrecedence() {
        val config = requireNotNull(
            AuthorizedWidevineDrmSessionFactory.configurationFor(
                stream(
                    requestHeaders = mapOf("Authorization" to "playback-auth", "User-Agent" to "F1TV-UA"),
                    drmRequestHeaders = mapOf("Authorization" to "license-auth", "X-Device" to "device-1"),
                    playToken = "opaque-token"
                )
            )
        )

        assertEquals("https://license.example.test/widevine", config.licenseUrl)
        assertEquals("license-auth", config.requestHeaders["Authorization"])
        assertEquals("device-1", config.requestHeaders["X-Device"])
        assertEquals("playToken=opaque-token", config.requestHeaders["Cookie"])
        assertEquals("F1TV-UA", config.userAgent)
        assertTrue("Configuration must not mutate the stream's source headers", config.requestHeaders !== stream().requestHeaders)
    }

    @Test
    fun preservesExistingSessionCookiesWhenAddingPlayToken() {
        val config = requireNotNull(
            AuthorizedWidevineDrmSessionFactory.configurationFor(
                stream(
                    drmRequestHeaders = mapOf("cookie" to "session=abc; region=uk; playToken=old-token"),
                    playToken = "new-token"
                )
            )
        )

        assertEquals("session=abc; region=uk; playToken=new-token", config.requestHeaders["cookie"])
    }

    @Test
    fun fallsBackToPlaybackHeadersAndDefaultUserAgent() {
        val config = requireNotNull(
            AuthorizedWidevineDrmSessionFactory.configurationFor(
                stream(requestHeaders = mapOf("Authorization" to "playback-auth"))
            )
        )

        assertEquals(mapOf("Authorization" to "playback-auth"), config.requestHeaders)
        assertEquals("Mozilla/5.0", config.userAgent)
    }
}
