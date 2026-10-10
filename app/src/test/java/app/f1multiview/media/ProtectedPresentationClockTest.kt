package app.f1multiview.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ProtectedPresentationClockTest {
    @Test
    fun clockWaitsForMasterAndMapsFollowerOffsetsToMasterPresentationTime() {
        val clock = ProtectedPresentationClock()
        clock.setMaster("main", mapOf("onboard" to 100L))

        assertFalse(clock.isReady)
        clock.establish(1_000_000L)
        assertTrue(clock.isReady)

        val main = clock.presentationTimeNs("main", 1_000_000L)
        val onboard = clock.presentationTimeNs("onboard", 1_100_000L)
        assertEquals(main, onboard)
    }

    @Test
    fun changingMasterResetsTheSharedPresentationEpoch() {
        val clock = ProtectedPresentationClock()
        clock.setMaster("main")
        clock.establish(500_000L)
        assertTrue(clock.isReady)

        clock.setMaster("new-main")
        assertFalse(clock.isReady)
    }

    @Test(expected = IllegalStateException::class)
    fun cannotEstablishClockBeforeChoosingMaster() {
        ProtectedPresentationClock().establish(0L)
    }
}
