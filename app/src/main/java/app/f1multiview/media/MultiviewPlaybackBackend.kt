package app.f1multiview.media

import app.f1multiview.core.playback.TiledMultiviewSession

/**
 * Capability boundary between the app's multiview orchestration and the physical
 * playback implementation.
 *
 * This interface deliberately does not pretend that a Media3 collection of players
 * is equivalent to Tiledmedia's native single-player backend. A native backend must
 * own the tiled retrieval/ABR/decoder/rendering path.
 */
enum class MultiviewBackendKind {
    NATIVE_TME,
    MEDIA3_MULTI_PLAYER_FALLBACK
}

data class MultiviewBackendStatus(
    val kind: MultiviewBackendKind,
    val available: Boolean,
    val singlePlayer: Boolean,
    val reason: String
)

interface MultiviewPlaybackBackend {
    val status: MultiviewBackendStatus

    /**
     * Returns true only when this backend can actually consume the supplied TME
     * session and own its physical playback pipeline.
     */
    fun canHandle(session: TiledMultiviewSession): Boolean
}

/**
 * The real Tiledmedia SDK is proprietary and is not bundled with this project.
 *
 * Keeping this as an explicit unavailable backend is intentional. It prevents the
 * rest of the app from silently treating TME JSON as though it were a playable
 * tiled stream.
 */
class NativeTmePlaybackBackend : MultiviewPlaybackBackend {
    override val status = MultiviewBackendStatus(
        kind = MultiviewBackendKind.NATIVE_TME,
        available = false,
        singlePlayer = true,
        reason = "Tiledmedia Player SDK is not bundled or licensed for this application"
    )

    override fun canHandle(session: TiledMultiviewSession): Boolean = false
}

/**
 * Existing Media3 path. It remains the safe fallback until a licensed native TME
 * backend is supplied. This backend must never be described as single-player.
 */
class Media3MultiPlayerFallbackBackend : MultiviewPlaybackBackend {
    override val status = MultiviewBackendStatus(
        kind = MultiviewBackendKind.MEDIA3_MULTI_PLAYER_FALLBACK,
        available = true,
        singlePlayer = false,
        reason = "Uses the existing Media3 logical-feed/physical-decoder architecture"
    )

    override fun canHandle(session: TiledMultiviewSession): Boolean = true
}
