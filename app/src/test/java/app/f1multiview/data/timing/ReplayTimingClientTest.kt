package app.f1multiview.data.timing

import org.junit.Assert.assertEquals
import org.junit.Test

class ReplayTimingClientTest {
    @Test
    fun calibrationReturnsTimingMinusVideoPosition() {
        val client = ReplayTimingClient()
        assertEquals(1500L, client.calibratedOffset(10_000L, 11_500L))
    }

    @Test
    fun calibrationIsClampedToFiveMinutes() {
        val client = ReplayTimingClient()
        assertEquals(300_000L, client.calibratedOffset(0L, 500_000L))
        assertEquals(-300_000L, client.calibratedOffset(500_000L, 0L))
    }

    @Test
    fun nudgeChangesReplayOffset() {
        val client = ReplayTimingClient()
        client.setSyncOffset(1000L)
        client.nudge(250L)
        assertEquals(1250L, client.calibratedOffset(0L, 1250L))
    }
}
