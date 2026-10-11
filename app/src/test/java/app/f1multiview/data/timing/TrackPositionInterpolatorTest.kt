package app.f1multiview.data.timing

import app.f1multiview.model.TrackDriverPosition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackPositionInterpolatorTest {
    @Test
    fun interpolatesOnlyBetweenActualReceivedSamples() {
        val driver = TrackDriverPosition(
            number = "1",
            x = 100.0,
            y = 50.0,
            updatedAtMs = 1_100L,
            previousX = 0.0,
            previousY = 0.0,
            previousUpdatedAtMs = 1_000L
        )

        val middle = interpolateLivePosition(driver, frameTimeMs = 1_150L, presentationDelayMs = 80L)

        assertEquals(70.0, middle.x, 0.0001)
        assertEquals(35.0, middle.y, 0.0001)
    }

    @Test
    fun returnsNewestReceivedPointWhenSampleGapIsTooLarge() {
        val driver = TrackDriverPosition(
            number = "1",
            x = 100.0,
            y = 50.0,
            updatedAtMs = 5_000L,
            previousX = 0.0,
            previousY = 0.0,
            previousUpdatedAtMs = 1_000L
        )

        val rendered = interpolateLivePosition(driver, frameTimeMs = 5_100L)

        assertEquals(100.0, rendered.x, 0.0)
        assertEquals(50.0, rendered.y, 0.0)
    }

    @Test
    fun staleDetectionUsesLastActualReceiveTimestamp() {
        val driver = TrackDriverPosition(number = "1", x = 5.0, y = 9.0, updatedAtMs = 10_000L)
        assertFalse(isLivePositionStale(driver, nowMs = 12_000L))
        assertTrue(isLivePositionStale(driver, nowMs = 12_501L))
    }
}
