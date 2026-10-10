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

    @Test
    fun smallFollowerDriftUsesFivePercentPhaseCorrection() {
        val clock = ProtectedPresentationClock()
        clock.setMaster("main")
        clock.establish(1_000_000L)
        val baseNs = clock.presentationTimeNs("main", 1_000_000L)

        val observation = clock.observeRenderedFrame(
            feedId = "follower",
            presentationTimeUs = 1_100_000L,
            renderTimeNs = baseNs + 200_000_000L,
            isLive = false
        )

        assertEquals(-100_000L, observation.driftUs)
        assertEquals(5_000L, observation.correctionUs)
        assertFalse(observation.hardResync)
    }

    @Test
    fun largeLiveDriftRequestsHardResynchronization() {
        val clock = ProtectedPresentationClock()
        clock.setMaster("main")
        clock.establish(1_000_000L)
        val baseNs = clock.presentationTimeNs("main", 1_000_000L)

        val observation = clock.observeRenderedFrame(
            feedId = "follower",
            presentationTimeUs = 1_100_000L,
            renderTimeNs = baseNs + 2_000_000_000L,
            isLive = true
        )

        assertTrue(observation.hardResync)
        assertEquals(0L, observation.correctionUs)
    }

    @Test(expected = IllegalStateException::class)
    fun cannotEstablishClockBeforeChoosingMaster() {
        ProtectedPresentationClock().establish(0L)
    }
}
