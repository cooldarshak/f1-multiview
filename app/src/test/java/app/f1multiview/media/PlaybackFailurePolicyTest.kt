package app.f1multiview.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackFailurePolicyTest {

    @Test
    fun explicitDrmFailureTakesPrecedenceOverGenericDecoderFailure() {
        assertEquals(
            PlaybackFailurePolicy.FailureKind.DRM_FATAL,
            PlaybackFailurePolicy.classify(
                "ERROR_CODE_DRM_SYSTEM_ERROR",
                "MediaCodecVideoRenderer failed",
                decoderFailure = true
            )
        )
    }

    @Test
    fun ordinaryDecoderFailureUsesDecoderRecovery() {
        assertEquals(
            PlaybackFailurePolicy.FailureKind.DECODER_RECOVERY,
            PlaybackFailurePolicy.classify(
                "ERROR_CODE_DECODER_INIT_FAILED",
                "codec init failed",
                decoderFailure = true
            )
        )
    }

    @Test
    fun ordinarySourceFailureUsesSourceRecovery() {
        assertEquals(
            PlaybackFailurePolicy.FailureKind.SOURCE_RECOVERY,
            PlaybackFailurePolicy.classify(
                "ERROR_CODE_IO_NETWORK_CONNECTION_FAILED",
                "timeout",
                decoderFailure = false
            )
        )
    }

    @Test
    fun nestedDrmCauseTakesPrecedenceOverGenericRendererDecoderFailure() {
        val cause = IllegalStateException(
            "Renderer failed",
            IllegalArgumentException("Widevine DrmSession licence acquisition failed")
        )
        assertEquals(
            PlaybackFailurePolicy.FailureKind.DRM_FATAL,
            PlaybackFailurePolicy.classify(
                "ERROR_CODE_DECODER_FAILED",
                "MediaCodecVideoRenderer failed",
                decoderFailure = true,
                cause = cause
            )
        )
    }

    @Test
    fun cyclicCauseChainDoesNotHangClassifier() {
        val first = IllegalStateException("renderer failed")
        val second = IllegalStateException("decoder failed")
        first.initCause(second)
        second.initCause(first)
        assertEquals(
            PlaybackFailurePolicy.FailureKind.DECODER_RECOVERY,
            PlaybackFailurePolicy.classify(
                "ERROR_CODE_DECODER_INIT_FAILED",
                "codec init failed",
                decoderFailure = true,
                cause = first
            )
        )
    }

    @Test
    fun classifiesLicenceAcquisitionFailureAsNonRetryableDrmFailure() {
        assertTrue(
            PlaybackFailurePolicy.isDrmFailure(
                "ERROR_CODE_DRM_LICENSE_ACQUISITION_FAILED",
                "License request failed"
            )
        )
    }

    @Test
    fun classifiesProvisioningAndKeyStatusFailuresAsDrmFailures() {
        assertTrue(PlaybackFailurePolicy.isDrmFailure("ERROR_CODE_DRM_PROVISIONING_FAILED", null))
        assertTrue(PlaybackFailurePolicy.isDrmFailure("ERROR_CODE_DRM_LICENSE_EXPIRED", null))
        assertTrue(PlaybackFailurePolicy.isDrmFailure(null, "MediaDrm session error"))
    }

    @Test
    fun doesNotClassifyOrdinaryNetworkOrDecoderErrorsAsDrmFailures() {
        assertFalse(PlaybackFailurePolicy.isDrmFailure("ERROR_CODE_IO_NETWORK_CONNECTION_FAILED", "timeout"))
        assertFalse(PlaybackFailurePolicy.isDrmFailure("ERROR_CODE_DECODER_INIT_FAILED", "codec init failed"))
    }
}
