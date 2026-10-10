package app.f1multiview.media

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackFailurePolicyTest {
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
