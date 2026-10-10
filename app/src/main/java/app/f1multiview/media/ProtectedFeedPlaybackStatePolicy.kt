package app.f1multiview.media

import androidx.media3.common.Player

/** Keep player-facing state honest: startup is buffering until MediaCodec reports a rendered frame. */
internal object ProtectedFeedPlaybackStatePolicy {
    fun toPlayerState(state: ProtectedCmafFeedRuntime.State?): Int = when (state) {
        ProtectedCmafFeedRuntime.State.PREPARING,
        ProtectedCmafFeedRuntime.State.WAITING_FOR_SURFACE_OR_KEYS,
        ProtectedCmafFeedRuntime.State.WAITING_FOR_FIRST_FRAME -> Player.STATE_BUFFERING
        ProtectedCmafFeedRuntime.State.PLAYING,
        ProtectedCmafFeedRuntime.State.PAUSED -> Player.STATE_READY
        else -> Player.STATE_IDLE
    }

    fun isActuallyPlaying(state: ProtectedCmafFeedRuntime.State?): Boolean =
        state == ProtectedCmafFeedRuntime.State.PLAYING
}
