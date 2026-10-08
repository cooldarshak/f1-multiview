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
class NativeTmePlaybackBackend(
    context: android.content.Context
) : MultiviewPlaybackBackend {
    private val engine = NativeTmeMultiviewEngine(context)

    override val status: MultiviewBackendStatus
        get() = MultiviewBackendStatus(
            kind = MultiviewBackendKind.NATIVE_TME,
            available = runCatching { GpacNativeTmeMerger().available }.getOrDefault(false),
            singlePlayer = true,
            reason = if (runCatching { GpacNativeTmeMerger().available }.getOrDefault(false))
                "GPAC hevcmerge -> one MediaCodec -> one Surface"
            else
                "ARM64 GPAC native merger is unavailable"
        )

    override fun canHandle(session: TiledMultiviewSession): Boolean = engine.canHandle(session)

    fun prepare(session: TiledMultiviewSession, source: StreamSource): Boolean =
        engine.prepare(session, source)

    fun attach(view: OpenTiledCompositorView) = engine.attach(view)

    fun selectFeeds(feedIds: List<String>) = engine.selectFeeds(feedIds)

    fun setSlots(slots: List<OpenTiledMultiviewEngine.OutputSlot>) = engine.setSlots(slots)

    fun play() = engine.play()
    fun pause() = engine.pause()
    fun release() = engine.release()
    fun diagnostics(): Map<String, String> = engine.diagnostics()
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
            session.tileWidth != null && session.tileWidth > 0 &&
            session.tileHeight != null && session.tileHeight > 0 &&
            session.feeds.mapNotNull { it.url?.takeIf(String::isNotBlank) }.distinct().size == 1

    fun canHandle(tme: TmePlayback, source: StreamSource): Boolean =
        tme.topology == TmeTopology.SINGLE_MOSAIC_SOURCE &&
            engine.canHandle(tme)

    fun requiresSecureOutput(source: StreamSource): Boolean =
        source.drmLicenseUrl != null

    fun prepare(tme: TmePlayback, source: StreamSource, referenceFeedId: String? = null): Boolean =
        engine.prepare(tme, source, referenceFeedId)
}
