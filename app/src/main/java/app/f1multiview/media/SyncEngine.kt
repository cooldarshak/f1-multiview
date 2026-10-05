package app.f1multiview.media
import androidx.media3.common.Player
import kotlin.math.abs
class SyncEngine(private val players: () -> Collection<Player> = { emptyList() }) {
    var toleranceMs = 180L
    var hardSeekThresholdMs = 1500L
    var correctionRate = 0.015f
    fun targetPosition(positions: List<Long>): Long? = positions.sorted().takeIf { it.isNotEmpty() }?.let { it[it.size / 2] }
    fun synchronize() {
        val active = players().filter { it.playbackState != Player.STATE_IDLE && it.currentPosition > 0 }
        if (active.size < 2) return
        val target = targetPosition(active.map { it.currentPosition }) ?: return
        active.forEach { player ->
            val delta = target - player.currentPosition
            when {
                abs(delta) > hardSeekThresholdMs -> { player.seekTo(target.coerceAtLeast(0L)); player.setPlaybackSpeed(1f) }
                abs(delta) > toleranceMs && player.isPlaying -> player.setPlaybackSpeed(if (delta > 0) 1f + correctionRate else 1f - correctionRate)
                else -> player.setPlaybackSpeed(1f)
            }
        }
    }
    fun resetSpeed() = players().forEach { it.setPlaybackSpeed(1f) }
}
