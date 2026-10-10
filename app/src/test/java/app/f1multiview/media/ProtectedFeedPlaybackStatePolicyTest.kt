package app.f1multiview.media

import androidx.media3.common.Player
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtectedFeedPlaybackStatePolicyTest {
    @Test
    fun decoderConfiguredButNoRenderedFrameRemainsBuffering() {
        assertEquals(
            Player.STATE_BUFFERING,
            ProtectedFeedPlaybackStatePolicy.toPlayerState(ProtectedCmafFeedRuntime.State.WAITING_FOR_FIRST_FRAME)
        )
        assertFalse(ProtectedFeedPlaybackStatePolicy.isActuallyPlaying(ProtectedCmafFeedRuntime.State.WAITING_FOR_FIRST_FRAME))
    }

    @Test
    fun playingRequiresFirstFramePresentationState() {
        assertEquals(
            Player.STATE_READY,
            ProtectedFeedPlaybackStatePolicy.toPlayerState(ProtectedCmafFeedRuntime.State.PLAYING)
        )
        assertTrue(ProtectedFeedPlaybackStatePolicy.isActuallyPlaying(ProtectedCmafFeedRuntime.State.PLAYING))
    }

    @Test
    fun pausedIsReadyButNotPlaying() {
        assertEquals(
            Player.STATE_READY,
            ProtectedFeedPlaybackStatePolicy.toPlayerState(ProtectedCmafFeedRuntime.State.PAUSED)
        )
        assertFalse(ProtectedFeedPlaybackStatePolicy.isActuallyPlaying(ProtectedCmafFeedRuntime.State.PAUSED))
    }

    @Test
    fun failedOrClosedRuntimeIsIdle() {
        assertEquals(Player.STATE_IDLE, ProtectedFeedPlaybackStatePolicy.toPlayerState(ProtectedCmafFeedRuntime.State.FAILED))
        assertEquals(Player.STATE_IDLE, ProtectedFeedPlaybackStatePolicy.toPlayerState(ProtectedCmafFeedRuntime.State.CLOSED))
    }
}
