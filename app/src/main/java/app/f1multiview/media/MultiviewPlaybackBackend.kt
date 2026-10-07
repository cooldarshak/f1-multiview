package app.f1multiview.media

import app.f1multiview.core.playback.TiledMultiviewSession
import app.f1multiview.data.f1tv.TmePlayback
import app.f1multiview.data.f1tv.TmeTopology
import app.f1multiview.model.StreamSource

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
    OPEN_TME,
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


/**
 * Our open-source tiled backend. It is intentionally narrower than the proprietary
 * Tiledmedia SDK: it only claims native single-player capability when the provider
 * gives us one actual mosaic/tiled media URL.
 */
class OpenTiledMultiviewBackend(
    private val engine: OpenTiledMultiviewEngine
) : MultiviewPlaybackBackend {
    override val status = MultiviewBackendStatus(
        kind = MultiviewBackendKind.OPEN_TME,
        available = true,
        singlePlayer = true,
        reason = "Open tiled backend using one Media3 player for a genuine single-source mosaic"
    )

    override fun canHandle(session: TiledMultiviewSession): Boolean =
        session.feeds.size > 1 &&
            session.tileWidth > 0 &&
            session.tileHeight > 0 &&
            session.feeds.mapNotNull { it.url?.takeIf(String::isNotBlank) }.distinct().size == 1

    fun canHandle(tme: TmePlayback, source: StreamSource): Boolean =
        source.drmLicenseUrl == null &&
            tme.topology == TmeTopology.SINGLE_MOSAIC_SOURCE &&
            engine.canHandle(tme)

    fun prepare(tme: TmePlayback, source: StreamSource, referenceFeedId: String? = null): Boolean =
        engine.prepare(tme, source, referenceFeedId)
}
