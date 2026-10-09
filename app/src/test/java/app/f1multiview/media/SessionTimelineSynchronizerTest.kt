package app.f1multiview.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionTimelineSynchronizerTest {
    private val synchronizer = SessionTimelineSynchronizer()

    @Test
    fun driftWithinToleranceRestoresNormalRate() {
        val result = synchronizer.decide(
            masterPositionMs = 10_000,
            followerPositionMs = 10_040,
            isLive = false,
            masterBuffering = false
        )

        assertEquals(SessionTimelineSynchronizer.Action.SetPlaybackRate(1.0f), result.action)
        assertTrue(result.synchronized)
    }

    @Test
    fun followerBehindUsesSmallSpeedIncrease() {
        val result = synchronizer.decide(
            masterPositionMs = 10_000,
            followerPositionMs = 9_800,
            isLive = false,
            masterBuffering = false
        )

        assertEquals(SessionTimelineSynchronizer.Action.SetPlaybackRate(1.05f), result.action)
        assertFalse(result.synchronized)
    }

    @Test
    fun followerAheadUsesSmallSpeedDecrease() {
        val result = synchronizer.decide(
            masterPositionMs = 10_000,
            followerPositionMs = 10_200,
            isLive = false,
            masterBuffering = false
        )

        assertEquals(SessionTimelineSynchronizer.Action.SetPlaybackRate(0.95f), result.action)
    }

    @Test
    fun largeVodDriftSeeksToMaster() {
        val result = synchronizer.decide(
            masterPositionMs = 10_000,
            followerPositionMs = 10_600,
            isLive = false,
            masterBuffering = false
        )

        assertEquals(SessionTimelineSynchronizer.Action.SeekTo(10_000), result.action)
    }

    @Test
    fun liveFeedAllowsLargerRateCorrectionWindow() {
        val result = synchronizer.decide(
            masterPositionMs = 10_000,
            followerPositionMs = 10_600,
            isLive = true,
            masterBuffering = false
        )

        assertEquals(SessionTimelineSynchronizer.Action.SetPlaybackRate(0.95f), result.action)
    }

    @Test
    fun liveDriftBeyondThresholdSeeksToMaster() {
        val result = synchronizer.decide(
            masterPositionMs = 10_000,
            followerPositionMs = 11_600,
            isLive = true,
            masterBuffering = false
        )

        assertEquals(SessionTimelineSynchronizer.Action.SeekTo(10_000), result.action)
    }

    @Test
    fun masterBufferingPausesFollowerBeforeAnyCorrection() {
        val result = synchronizer.decide(
            masterPositionMs = 10_000,
            followerPositionMs = 10_500,
            isLive = false,
            masterBuffering = true
        )

        assertEquals(SessionTimelineSynchronizer.Action.PauseFollower, result.action)
    }

    @Test
    fun enginePausedFollowerResumesAfterMasterRecovers() {
        val result = synchronizer.decide(
            masterPositionMs = 10_000,
            followerPositionMs = 10_100,
            isLive = false,
            masterBuffering = false,
            followerPausedByEngine = true
        )

        assertEquals(SessionTimelineSynchronizer.Action.ResumeFollower, result.action)
    }
}
