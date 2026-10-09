package app.f1multiview.media

import app.f1multiview.model.StreamKind
import app.f1multiview.model.StreamSource
import org.junit.Assert.assertEquals
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

class ProtectedSampleAdmissionTest {
    private fun evaluate(
        streamProtected: Boolean = true,
        sampleEncrypted: Boolean = true,
        licenseConfigured: Boolean = true,
        drmSessionReady: Boolean = true,
        secureDecoderConfigured: Boolean = true,
        protectedSurfaceValid: Boolean = true
    ) = ProtectedSampleAdmission.evaluate(
        streamProtected = streamProtected,
        sampleEncrypted = sampleEncrypted,
        licenseConfigured = licenseConfigured,
        drmSessionReady = drmSessionReady,
        secureDecoderConfigured = secureDecoderConfigured,
        protectedSurfaceValid = protectedSurfaceValid
    )

    @Test
    fun clearUnprotectedSampleDoesNotRequireDrm() {
        assertEquals(
            ProtectedSampleAdmission.Result.ACCEPT_CLEAR_SAMPLE,
            evaluate(streamProtected = false, sampleEncrypted = false, licenseConfigured = false,
                drmSessionReady = false, secureDecoderConfigured = false, protectedSurfaceValid = false)
        )
    }

    @Test
    fun encryptedSampleFailsClosedWithoutLicense() {
        assertEquals(
            ProtectedSampleAdmission.Result.REJECT_MISSING_LICENSE,
            evaluate(licenseConfigured = false)
        )
    }

    @Test
    fun encryptedSampleFailsClosedUntilDrmSessionIsReady() {
        assertEquals(
            ProtectedSampleAdmission.Result.REJECT_DRM_SESSION_NOT_READY,
            evaluate(drmSessionReady = false)
        )
    }

    @Test
    fun encryptedSampleFailsClosedWithoutConfiguredSecureDecoder() {
        assertEquals(
            ProtectedSampleAdmission.Result.REJECT_SECURE_DECODER_UNAVAILABLE,
            evaluate(secureDecoderConfigured = false)
        )
    }

    @Test
    fun encryptedSampleFailsClosedWithoutValidProtectedSurface() {
        assertEquals(
            ProtectedSampleAdmission.Result.REJECT_PROTECTED_SURFACE_UNAVAILABLE,
            evaluate(protectedSurfaceValid = false)
        )
    }

    @Test
    fun encryptedSampleAdmittedOnlyWhenEveryProtectedPrerequisiteIsReady() {
        assertEquals(
            ProtectedSampleAdmission.Result.ACCEPT_PROTECTED_SAMPLE,
            evaluate()
        )
    }

    @Test
    fun clearSampleInProtectedStreamStillRequiresProtectedOutputPrerequisites() {
        assertEquals(
            ProtectedSampleAdmission.Result.REJECT_SECURE_DECODER_UNAVAILABLE,
            evaluate(sampleEncrypted = false, secureDecoderConfigured = false)
        )
    }
}
