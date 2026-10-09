package app.f1multiview.media

import kotlin.math.abs

/**
 * Pure synchronization policy for independent feed pipelines.
 *
 * This class does not own decoders or players. The eventual engine applies its returned
 * action to each feed pipeline. Keeping policy separate makes drift behavior testable without
 * Android media, network access, F1 credentials, or DRM.
 */
class SessionTimelineSynchronizer(
    private val toleranceMs: Long = 50L,
    private val vodHardSeekMs: Long = 500L,
    private val liveHardSeekMs: Long = 1_500L,
    private val correctionRate: Float = 0.05f
) {
    init {
        require(toleranceMs >= 0L)
        require(vodHardSeekMs > toleranceMs)
        require(liveHardSeekMs > toleranceMs)
        require(correctionRate in 0.0f..0.25f)
    }

    sealed interface Action {
        data object NoChange : Action
        data object PauseFollower : Action
        data object ResumeFollower : Action
        data class SeekTo(val positionMs: Long) : Action
        data class SetPlaybackRate(val rate: Float) : Action
    }

    data class Decision(
        val driftMs: Long,
        val action: Action,
        val synchronized: Boolean
    )

    /**
     * driftMs = follower position - master position.
     * A positive drift means the follower is ahead and should slow down.
     */
    fun decide(
        masterPositionMs: Long,
        followerPositionMs: Long,
        isLive: Boolean,
        masterBuffering: Boolean,
        followerPausedByEngine: Boolean = false
    ): Decision {
        require(masterPositionMs >= 0L)
        require(followerPositionMs >= 0L)

        val drift = followerPositionMs - masterPositionMs
        if (masterBuffering) {
            return Decision(drift, Action.PauseFollower, synchronized = false)
        }
        if (followerPausedByEngine) {
            return Decision(drift, Action.ResumeFollower, synchronized = false)
        }

        val magnitude = abs(drift)
        if (magnitude <= toleranceMs) {
            return Decision(drift, Action.SetPlaybackRate(1.0f), synchronized = true)
        }

        val hardSeekThreshold = if (isLive) liveHardSeekMs else vodHardSeekMs
        if (magnitude >= hardSeekThreshold) {
            return Decision(drift, Action.SeekTo(masterPositionMs), synchronized = false)
        }

        val rate = if (drift > 0L) 1.0f - correctionRate else 1.0f + correctionRate
        return Decision(drift, Action.SetPlaybackRate(rate), synchronized = false)
    }
}
