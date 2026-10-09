package app.f1multiview.media

import app.f1multiview.model.StreamKind
import app.f1multiview.model.StreamSource
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DrmProtectionPolicyTest {
    private fun stream(
        drmProtected: Boolean = false,
        licenseUrl: String? = null,
        drmType: String? = null,
        streamType: String? = null
    ) = StreamSource(
        id = "feed",
        title = "Authorized feed",
        kind = StreamKind.WORLD,
        url = "https://example.test/manifest.mpd",
        drmProtected = drmProtected,
        drmLicenseUrl = licenseUrl,
        drmType = drmType,
        streamType = streamType
    )

    @Test
    fun explicitProtectedFlagRequiresProtectedOutputEvenWithoutLicenseUrl() {
        val stream = stream(drmProtected = true)
        assertTrue(DrmProtectionPolicy.requiresProtectedOutput(stream))
        assertTrue(DrmProtectionPolicy.missingLicenseEndpoint(stream))
    }

    @Test
    fun licenseEndpointOrWidevineMetadataRequiresProtectedOutput() {
        assertTrue(DrmProtectionPolicy.requiresProtectedOutput(stream(licenseUrl = "https://license.test/wv")))
        assertTrue(DrmProtectionPolicy.requiresProtectedOutput(stream(drmType = "Widevine")))
        assertTrue(DrmProtectionPolicy.requiresProtectedOutput(stream(streamType = "SDR_HD_DASHWV")))
    }

    @Test
    fun pipelineVersionOrManifestUrlAloneDoesNotInventDrmDeclaration() {
        val clear = stream()
        assertFalse(DrmProtectionPolicy.requiresProtectedOutput(clear))
        assertFalse(DrmProtectionPolicy.missingLicenseEndpoint(clear))
    }
}
