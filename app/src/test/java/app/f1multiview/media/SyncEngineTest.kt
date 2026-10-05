package app.f1multiview.media

import androidx.media3.common.Player
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncEngineTest {
    @Test fun targetUsesMedianToIgnoreOutlier() {
        val engine = SyncEngine()
        assertEquals(1000L, engine.targetPosition(listOf(1000L, 1010L, 100000L)))
    }

    @Test fun exposesConfiguredThresholds() {
        val engine = SyncEngine()
        engine.toleranceMs = 120L
        engine.hardSeekThresholdMs = 1000L
        engine.correctionRate = 0.02f
        assertEquals(120L, engine.toleranceMs)
        assertEquals(1000L, engine.hardSeekThresholdMs)
        assertTrue(engine.correctionRate > 0f)
    }
}
