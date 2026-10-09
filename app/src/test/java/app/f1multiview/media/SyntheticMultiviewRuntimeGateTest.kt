package app.f1multiview.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SyntheticMultiviewRuntimeGateTest {
    private fun feed(id: String, updates: Long = 180, frames: Long = 180, ageMs: Long? = 40,
        gapMs: Long = 200, error: String? = null) =
        SyntheticMultiviewRuntimeGate.FeedMetrics(
            feedId = id, codecName = "c2.android.avc.decoder", decoderOutputFrames = frames,
            textureUpdates = updates, firstFrameLatencyMs = 250L, lastUpdateAgeMs = ageMs,
            maxUpdateGapMs = gapMs, error = error
        )

    private fun draw(frames: Long = 400, fps: Double? = 14.9, maxGapMs: Long = 90) =
        SyntheticMultiviewRuntimeGate.DrawMetrics(frames, fps, maxGapMs)

    @Test fun remainsWarmingUntilEnoughRuntimeHasBeenObserved() {
        val verdict = SyntheticMultiviewRuntimeGate.evaluate(14_999L,
            listOf(feed("LEFT"), feed("CENTER"), feed("RIGHT")), draw())
        assertEquals(SyntheticMultiviewRuntimeGate.State.WARMING, verdict.state)
    }

    @Test fun passesOnlyWhenAllThreeFeedsAndCompositorMeetAcceptanceCriteria() {
        val verdict = SyntheticMultiviewRuntimeGate.evaluate(20_000L,
            listOf(feed("LEFT"), feed("CENTER"), feed("RIGHT")), draw())
        assertEquals(SyntheticMultiviewRuntimeGate.State.PASS, verdict.state)
        assertTrue(verdict.failures.isEmpty())
    }

    @Test fun failsWhenAnyFeedStopsUpdating() {
        val verdict = SyntheticMultiviewRuntimeGate.evaluate(20_000L,
            listOf(feed("LEFT"), feed("CENTER", ageMs = 2_500L), feed("RIGHT")), draw())
        assertEquals(SyntheticMultiviewRuntimeGate.State.FAIL, verdict.state)
        assertTrue(verdict.failures.any { it.contains("CENTER texture is stale") })
    }

    @Test fun failsWhenDecoderReportsAnErrorEvenDuringWarmup() {
        val verdict = SyntheticMultiviewRuntimeGate.evaluate(1_000L,
            listOf(feed("LEFT"), feed("CENTER", error = "codec configure failed"), feed("RIGHT")), draw())
        assertEquals(SyntheticMultiviewRuntimeGate.State.FAIL, verdict.state)
        assertTrue(verdict.failures.single().contains("codec configure failed"))
    }

    @Test fun failsWhenThreeFeedTopologyIsIncomplete() {
        val verdict = SyntheticMultiviewRuntimeGate.evaluate(20_000L,
            listOf(feed("LEFT"), feed("CENTER")), draw())
        assertEquals(SyntheticMultiviewRuntimeGate.State.FAIL, verdict.state)
        assertTrue(verdict.failures.any { it.contains("Expected exactly LEFT, CENTER, and RIGHT") })
    }

    @Test fun failsWhenCompositorCadenceIsTooLow() {
        val verdict = SyntheticMultiviewRuntimeGate.evaluate(20_000L,
            listOf(feed("LEFT"), feed("CENTER"), feed("RIGHT")), draw(fps = 3.0))
        assertEquals(SyntheticMultiviewRuntimeGate.State.FAIL, verdict.state)
        assertTrue(verdict.failures.any { it.contains("GLES draw cadence below 8 FPS") })
    }
}
