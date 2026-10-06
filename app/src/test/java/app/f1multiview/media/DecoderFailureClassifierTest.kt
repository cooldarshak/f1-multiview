package app.f1multiview.media

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DecoderFailureClassifierTest {
    @Test
    fun triesL3ForFourthSecureDecoderResourceFailure() {
        assertTrue(
            DecoderFailureClassifier.shouldTryWidevineL3(
                isMain = false,
                playerCount = 4,
                hasDrmLicense = true,
                errorCodeName = "ERROR_CODE_DECODER_INIT_FAILED",
                evidence = "MediaCodec ResourceBusyException: insufficient resource"
            )
        )
    }

    @Test
    fun doesNotDowngradeNormalDecoderFailure() {
        assertFalse(
            DecoderFailureClassifier.shouldTryWidevineL3(
                isMain = false,
                playerCount = 4,
                hasDrmLicense = true,
                errorCodeName = "ERROR_CODE_DECODING_FAILED",
                evidence = "codec failed to decode frame"
            )
        )
    }

    @Test
    fun neverDowngradesMainFeed() {
        assertFalse(
            DecoderFailureClassifier.shouldTryWidevineL3(
                isMain = true,
                playerCount = 4,
                hasDrmLicense = true,
                errorCodeName = "ERROR_CODE_DECODER_INIT_FAILED",
                evidence = "MediaCodec ResourceBusyException"
            )
        )
    }
}
