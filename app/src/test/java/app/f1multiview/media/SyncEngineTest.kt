package app.f1multiview.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncEngineTest {
    @Test fun targetUsesMedianToIgnoreOutlier() {
        val engine = SyncEngine()
        assertEquals(1010L, engine.targetPosition(listOf(1000L, 1010L, 100000L)))
    }

    @Test fun defaultPolicyUsesTightFiftyMsTolerance() {
        val engine = SyncEngine()
        assertEquals(50L, engine.toleranceMs)
        assertEquals(500L, engine.hardSeekThresholdMs)
        assertEquals(0.05f, engine.correctionRate)
    }

    @Test fun smallPositiveDriftSpeedsFollowerUp() {
        val decision = SyncEngine().decide(deltaMs = 120L)
        assertEquals(SyncAction.SPEED_UP, decision.action)
        assertEquals(1.05f, decision.playbackSpeed)
    }

    @Test fun smallNegativeDriftSlowsFollowerDown() {
        val decision = SyncEngine().decide(deltaMs = -120L)
        assertEquals(SyncAction.SLOW_DOWN, decision.action)
        assertEquals(0.95f, decision.playbackSpeed)
    }

    @Test fun largeVodDriftSeeks() {
        val decision = SyncEngine().decide(deltaMs = 600L)
        assertEquals(SyncAction.SEEK, decision.action)
    }

    @Test fun liveLargeDriftCanUseRateCorrectionWithoutSeek() {
        val decision = SyncEngine().decide(
            deltaMs = 600L,
            canSeek = false
        )
        assertEquals(SyncAction.SPEED_UP, decision.action)
        assertEquals(1.05f, decision.playbackSpeed)
    }

    @Test fun referenceBufferingHoldsFollower() {
        val decision = SyncEngine().decide(
            deltaMs = 2000L,
            canSeek = true,
            referenceBuffering = true
        )
        assertEquals(SyncAction.HOLD, decision.action)
    }

    @Test fun withinToleranceRestoresNormalSpeed() {
        val decision = SyncEngine().decide(deltaMs = 50L)
        assertEquals(SyncAction.NORMAL, decision.action)
        assertEquals(1f, decision.playbackSpeed)
    }

    @Test fun configuredPolicyRemainsMutable() {
        val engine = SyncEngine()
        engine.toleranceMs = 120L
        engine.hardSeekThresholdMs = 1000L
        engine.correctionRate = 0.02f
        assertEquals(120L, engine.toleranceMs)
        assertEquals(1000L, engine.hardSeekThresholdMs)
        assertTrue(engine.correctionRate > 0f)
    }
}
