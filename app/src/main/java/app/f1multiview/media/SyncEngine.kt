package app.f1multiview.media

import androidx.media3.common.Player
import kotlin.math.abs

enum class SyncAction {
    HOLD,
    SEEK,
    SPEED_UP,
    SLOW_DOWN,
    NORMAL
}

data class SyncDecision(
    val action: SyncAction,
    val playbackSpeed: Float = 1f
)

/**
 * Shared multiview synchronization policy.
 *
 * The engine intentionally stays independent of ExoPlayer timing sources. PlayerPool supplies
 * either media-position deltas (VOD) or live-offset deltas (live). This keeps the policy
 * testable while allowing the playback layer to choose the correct clock.
 */
class SyncEngine(private val players: () -> Collection<Player> = { emptyList() }) {
    /** Followers inside this window are considered aligned. */
    var toleranceMs = 50L

    /** VOD/replay drift above this threshold is corrected with a seek. */
    var hardSeekThresholdMs = 500L

    /** Live/replay follower rate correction magnitude, matching the F1OpenViewer approach. */
    var correctionRate = 0.05f

    /** Median target retained for callers that need a robust multi-player reference. */
    fun targetPosition(positions: List<Long>): Long? =
        positions.sorted().takeIf { it.isNotEmpty() }?.let { it[it.size / 2] }

    /**
     * Decide how one follower should converge on the reference.
     *
     * Positive delta means the follower is behind the reference and should speed up.
     * Negative delta means it is ahead and should slow down.
     *
     * Buffering always wins over correction: callers should hold the follower rather than
     * repeatedly seeking/rate-adjusting while the reference is unavailable.
     */
    fun decide(
        deltaMs: Long,
        canSeek: Boolean = true,
        referenceBuffering: Boolean = false,
        seekThresholdMs: Long = hardSeekThresholdMs
    ): SyncDecision {
        if (referenceBuffering) return SyncDecision(SyncAction.HOLD)
        val drift = abs(deltaMs)
        if (drift <= toleranceMs) return SyncDecision(SyncAction.NORMAL)
        if (canSeek && drift >= seekThresholdMs) return SyncDecision(SyncAction.SEEK)
        return if (deltaMs > 0L) {
            SyncDecision(SyncAction.SPEED_UP, 1f + correctionRate)
        } else {
            SyncDecision(SyncAction.SLOW_DOWN, 1f - correctionRate)
        }
    }

    /**
     * One-shot convergence for legacy callers. Phase 4's continuous PlayerPool watcher uses
     * decide() so the same policy is applied repeatedly without replacing this API.
     */
    fun synchronize() {
        val active = players().filter { it.playbackState != Player.STATE_IDLE && it.currentPosition > 0 }
        if (active.size < 2) return
        val target = targetPosition(active.map { it.currentPosition }) ?: return
        active.forEach { player ->
            val delta = target - player.currentPosition
            when (decide(delta).action) {
                SyncAction.SEEK -> {
                    player.seekTo(target.coerceAtLeast(0L))
                    player.setPlaybackSpeed(1f)
                }
                SyncAction.SPEED_UP -> player.setPlaybackSpeed(1f + correctionRate)
                SyncAction.SLOW_DOWN -> player.setPlaybackSpeed(1f - correctionRate)
                SyncAction.NORMAL, SyncAction.HOLD -> player.setPlaybackSpeed(1f)
            }
        }
    }

    fun resetSpeed() = players().forEach { it.setPlaybackSpeed(1f) }
}
