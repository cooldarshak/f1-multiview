package app.f1multiview.media

import kotlin.math.abs

/**
 * Public-API equivalent of the control state exposed by the production TME MSE layer.
 * It does not copy proprietary implementation. It describes actions applied to our
 * single Media3 clock and physical decoder plan.
 */
sealed interface OpenTiledMseAction {
    data object Play : OpenTiledMseAction
    data object Pause : OpenTiledMseAction
    data class Seek(val positionMs: Long) : OpenTiledMseAction
    data class SetPlaybackRate(val rate: Float) : OpenTiledMseAction
    data class SetSyncMultiplier(val multiplier: Float) : OpenTiledMseAction
}

data class OpenTiledMseState(
    val referencePositionMs: Long = 0L,
    val playerPositionMs: Long = 0L,
    val driftMs: Long = 0L,
    val playbackRate: Float = 1f,
    val syncMultiplier: Float = 1f
)

class OpenTiledMseController(
    private val smallDriftMs: Long = 35L,
    private val largeDriftMs: Long = 250L,
    private val maxRateCorrection: Float = 0.04f
) {
    var state: OpenTiledMseState = OpenTiledMseState()
        private set

    fun reconcile(referencePositionMs: Long, playerPositionMs: Long): OpenTiledMseAction {
        val drift = referencePositionMs - playerPositionMs
        val action = when {
            abs(drift) >= largeDriftMs ->
                OpenTiledMseAction.Seek(referencePositionMs.coerceAtLeast(0L))
            abs(drift) >= smallDriftMs -> {
                val correction = (drift / 1200f).coerceIn(-maxRateCorrection, maxRateCorrection)
                OpenTiledMseAction.SetPlaybackRate(1f + correction)
            }
            else -> OpenTiledMseAction.SetPlaybackRate(1f)
        }
        val rate = when (action) {
            is OpenTiledMseAction.SetPlaybackRate -> action.rate
            else -> 1f
        }
        state = OpenTiledMseState(
            referencePositionMs = referencePositionMs,
            playerPositionMs = playerPositionMs,
            driftMs = drift,
            playbackRate = rate,
            syncMultiplier = rate
        )
        return action
    }

    fun reset(positionMs: Long) {
        state = OpenTiledMseState(referencePositionMs = positionMs, playerPositionMs = positionMs)
    }
}
