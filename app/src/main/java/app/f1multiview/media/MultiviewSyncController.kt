package app.f1multiview.media

import androidx.media3.common.C
import androidx.media3.common.Player
import android.os.Handler
import android.os.Looper
import kotlin.math.max

/**
 * Central multiview playback clock and synchronization controller.
 *
 * The reference player is never rate-adjusted. Followers converge to the reference
 * using a single policy, with buffering protection, VOD/live clock selection and
 * per-channel replay offsets.
 */
class MultiviewSyncController(
    private val players: () -> Map<String, Player>,
    private val desiredPlaying: () -> Set<String>,
    private val onReferenceRemoved: (String) -> Unit = {}
) {
    private val policy = SyncEngine()
    private val handler = Handler(Looper.getMainLooper())
    private val pausedByReference = mutableSetOf<String>()
    private val lastCorrectionSeekMs = mutableMapOf<String, Long>()
    private val firstFrame = mutableSetOf<String>()
    private var referenceId: String? = null
    private var channelOffsetsMs: Map<String, Long> = emptyMap()
    private var running = false

    private val ticker = object : Runnable {
        override fun run() {
            if (!running) return
            synchronizeOnce()
            handler.postDelayed(this, 250L)
        }
    }

    fun setReference(id: String?) {
        if (referenceId == id) return
        referenceId?.let { old ->
            players()[old]?.setPlaybackSpeed(1f)
        }
        referenceId = id
        pausedByReference.clear()
    }

    fun referenceId(): String? = referenceId

    fun synchronize(offsetsMs: Map<String, Long> = emptyMap()) {
        channelOffsetsMs = offsetsMs
        synchronizeOnce()
        start()
    }

    fun start() {
        if (running) return
        running = true
        handler.removeCallbacks(ticker)
        handler.post(ticker)
    }

    fun stop() {
        running = false
        handler.removeCallbacks(ticker)
        players().values.forEach { it.setPlaybackSpeed(1f) }
        pausedByReference.clear()
    }

    fun onFeedRemoved(id: String) {
        if (referenceId == id) {
            referenceId = null
            onReferenceRemoved(id)
        }
        pausedByReference.remove(id)
        lastCorrectionSeekMs.remove(id)
        firstFrame.remove(id)
    }

    fun markFirstFrame(id: String) {
        firstFrame.add(id)
        if (referenceId != null && referenceId != id) synchronizeOnce()
    }

    fun reset() {
        stop()
        referenceId = null
        channelOffsetsMs = emptyMap()
        pausedByReference.clear()
        lastCorrectionSeekMs.clear()
        firstFrame.clear()
    }

    private fun synchronizeOnce() {
        val refId = referenceId ?: return
        val all = players()
        val reference = all[refId] ?: return
        if (!firstFrame.contains(refId)) return
        if (reference.playbackState == Player.STATE_IDLE || reference.playbackState == Player.STATE_ENDED) return

        val live = reference.isCurrentWindowLive
        val referenceBuffering = desiredPlaying().contains(refId) &&
            reference.playbackState == Player.STATE_BUFFERING
        val now = android.os.SystemClock.elapsedRealtime()

        all.forEach { (id, follower) ->
            if (id == refId || !firstFrame.contains(id)) return@forEach
            if (follower.playbackState == Player.STATE_IDLE || follower.playbackState == Player.STATE_ENDED) return@forEach

            if (referenceBuffering) {
                follower.setPlaybackSpeed(1f)
                if (follower.isPlaying) {
                    follower.pause()
                    pausedByReference.add(id)
                }
                return@forEach
            }

            if (pausedByReference.remove(id) &&
                desiredPlaying().contains(id) &&
                !follower.isPlaying
            ) follower.play()

            if (desiredPlaying().contains(id) &&
                !follower.isPlaying &&
                follower.playbackState == Player.STATE_READY
            ) follower.play()

            val delta = if (live) {
                val refOffset = reference.currentLiveOffset.takeIf { it != C.TIME_UNSET && it >= 0L }
                val followerOffset = follower.currentLiveOffset.takeIf { it != C.TIME_UNSET && it >= 0L }
                if (refOffset != null && followerOffset != null) followerOffset - refOffset else null
            } else {
                reference.currentPosition + (channelOffsetsMs[id] ?: 0L) - follower.currentPosition
            } ?: return@forEach

            val threshold = if (live) 1_500L else policy.hardSeekThresholdMs
            when (val decision = policy.decide(delta, canSeek = true, seekThresholdMs = threshold)) {
                SyncDecision(SyncAction.HOLD, 1f) -> follower.setPlaybackSpeed(1f)
                SyncDecision(SyncAction.SEEK, 1f) -> {
                    follower.setPlaybackSpeed(1f)
                    val target = if (live) {
                        max(0L, follower.currentPosition + delta)
                    } else {
                        max(0L, reference.currentPosition + (channelOffsetsMs[id] ?: 0L))
                    }
                    val duration = follower.duration
                    follower.seekTo(if (duration > 0L) target.coerceAtMost(duration) else target)
                    lastCorrectionSeekMs[id] = now
                }
                else -> when (decision.action) {
                    SyncAction.SPEED_UP, SyncAction.SLOW_DOWN -> {
                        if (now - (lastCorrectionSeekMs[id] ?: 0L) >= 250L) {
                            follower.setPlaybackSpeed(decision.playbackSpeed)
                        }
                    }
                    SyncAction.NORMAL, SyncAction.HOLD -> follower.setPlaybackSpeed(1f)
                    SyncAction.SEEK -> Unit
                }
            }
        }
    }
}
