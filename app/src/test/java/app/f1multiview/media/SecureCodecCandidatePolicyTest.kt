package app.f1multiview.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SecureCodecCandidatePolicyTest {
    @Test
    fun selectsOnlyHardwareSecureDecoderCandidatesSupportingTheTrackMime() {
        val selected = SecureCodecCandidatePolicy.select(
            listOf(
                SecureCodecCandidate("software.secure", false, false, true, true),
                SecureCodecCandidate("hardware.clear", false, true, true, false),
                SecureCodecCandidate("hardware.encoder", true, true, true, true),
                SecureCodecCandidate("hardware.other-mime", false, true, false, true),
                SecureCodecCandidate("hardware.secure", false, true, true, true),
                SecureCodecCandidate("hardware.secure", false, true, true, true)
            )
        )

        assertEquals(listOf("hardware.secure"), selected)
    }

    @Test
    fun rejectsBlankNamesAndEveryNonSecureCandidate() {
        val selected = SecureCodecCandidatePolicy.select(
            listOf(
                SecureCodecCandidate("", false, true, true, true),
                SecureCodecCandidate("no-secure", false, true, true, false),
                SecureCodecCandidate("software", false, false, true, true),
                SecureCodecCandidate("encoder", true, true, true, true)
            )
        )

        assertTrue(selected.isEmpty())
    }
}
