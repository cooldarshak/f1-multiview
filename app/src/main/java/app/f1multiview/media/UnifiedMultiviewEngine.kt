package app.f1multiview.media

import android.content.Context
import android.view.SurfaceView
import android.view.TextureView
import android.os.Handler
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import app.f1multiview.core.playback.Quality
import app.f1multiview.core.playback.TiledMultiviewSessionParser
import app.f1multiview.core.playback.toModel
import app.f1multiview.model.StreamSource

/**
 * Single ownership boundary for all multiview media state.
 *
 * The UI talks to this engine, not directly to a collection of players. The current
 * Media3 backend is intentionally kept behind DecoderManager so the decoder/resource
 * strategy can be replaced without changing the Compose layer.
 */
class UnifiedMultiviewEngine(context: Context) {
    private val tiledMultiviewController = TiledMultiviewController()
    private val nativeTmeBackend = NativeTmePlaybackBackend()
    private val media3FallbackBackend = Media3MultiPlayerFallbackBackend()
    private val openTiledEngine = OpenTiledMultiviewEngine { source ->
        val dataSourceFactory = DefaultHttpDataSource.Factory()
            .setDefaultRequestProperties(source.requestHeaders)
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(context).setDataSourceFactory(dataSourceFactory))
            .build()
    }
    private val openTiledBackend = OpenTiledMultiviewBackend(openTiledEngine)
    private var selectedMultiviewBackend: MultiviewPlaybackBackend = media3FallbackBackend
    private val _backendStatus = MutableStateFlow(media3FallbackBackend.status)
    val backendStatus: StateFlow<MultiviewBackendStatus> = _backendStatus
    private val decoderManager = DecoderManager(context)
    private val feedRegistry = FeedRegistry()
    private val surfaceManager = MultiviewSurfaceManager(context)
    private val viewportScheduler = ViewportScheduler(maxDecoders = decoderManager.capacity())

    val errors: StateFlow<Map<String, String>> = decoderManager.errors
    private val _decoderGeneration = MutableStateFlow(0L)
    val decoderGeneration: StateFlow<Long> = _decoderGeneration

    fun player(id: String): EnginePlayerHandle = EnginePlayerHandle(this, id)

    fun configureTiledMultiview(session: app.f1multiview.core.playback.TiledMultiviewSession) {
        selectedMultiviewBackend = if (nativeTmeBackend.canHandle(session)) {
            nativeTmeBackend
        } else {
            media3FallbackBackend
        }
        tiledMultiviewController.configure(session)
        _backendStatus.value = selectedMultiviewBackend.status
    }

    fun tiledMultiviewState(): TiledMultiviewController.State = tiledMultiviewController.state()

    /**
     * Runtime truth about the physical multiview backend. This is intentionally
     * exposed for diagnostics/UI so TME metadata cannot be mistaken for native
     * Tiledmedia playback.
     */
    fun multiviewBackendStatus(): MultiviewBackendStatus = selectedMultiviewBackend.status

    fun isOpenTiledActive(): Boolean =
        selectedMultiviewBackend === openTiledBackend

    fun openTiledView(context: Context): OpenTiledCompositorView =
        OpenTiledCompositorView(context)

    fun selectOpenTiledFeeds(feedIds: List<String>) {
        openTiledEngine.selectVisibleFeeds(feedIds)
    }

    fun attachOpenTiledView(view: OpenTiledCompositorView) {
        if (isOpenTiledActive()) openTiledEngine.attachTo(view)
    }

    fun attachOpenTiledSecureView(view: OpenTiledSecureSurfaceView) {
        if (isOpenTiledActive()) openTiledEngine.attachSecureTo(view)
    }

    fun detachOpenTiledSecureView(view: OpenTiledSecureSurfaceView) {
        view.setListener(null)
    }

    fun detachOpenTiledView(view: OpenTiledCompositorView) {
        view.setListener(null)
        view.releaseOutput()
    }

    fun setTiledFeeds(feedIds: List<String>) = tiledMultiviewController.setFeeds(feedIds)

    fun focusTiledFeed(feedId: String) = tiledMultiviewController.focus(feedId)

    fun setTiledAudio(feedId: String) = tiledMultiviewController.setAudio(feedId)

    fun setTiledSlots(slots: List<TiledMultiviewController.ViewSlot>) =
        tiledMultiviewController.setSlots(slots)


    internal fun backendPlayer(id: String): ExoPlayer = decoderManager.get(id)
    internal fun backendPlayerOrNull(id: String): ExoPlayer? = decoderManager.getOrNull(id)

    fun load(stream: StreamSource, forceReload: Boolean = false): Boolean {
        feedRegistry.put(stream)
        return decoderManager.load(stream, forceReload)
    }

    fun retain(ids: Set<String>) {
        surfaceManager.boundFeedIds().filterNot(ids::contains).forEach { id ->
            surfaceManager.binding(id)?.let { binding ->
                surfaceManager.detach(id, player(id))
            }
        }
        feedRegistry.retain(ids)
        decoderManager.retain(ids)
    }

    internal fun hasDecoder(id: String): Boolean = decoderManager.hasDecoder(id)
    fun activeDecoderIds(): Set<String> = decoderManager.activeDecoderIds()
    fun availableDecoderSlots(): Int = decoderManager.availableDecoderSlots()
    fun decoderCapacity(): Int = decoderManager.capacity()

    fun suspend(id: String) = decoderManager.suspend(id)

    fun clear(id: String) {
        if (surfaceManager.binding(id) != null) surfaceManager.detach(id, player(id))
        feedRegistry.remove(id)
        decoderManager.clear(id)
    }


    /**
     * Reconciles logical feeds with the physical decoder budget according to viewport visibility.
     * Reference/visible feeds win; already-active feeds are preferred over cold feeds at equal priority.
     */
    fun updateViewport(
        streams: List<StreamSource>,
        visibleIds: Set<String>,
        referenceId: String?,
        autoplay: Boolean = false
    ): Set<String> {
        streams.forEach(feedRegistry::put)

        // If F1 supplied TME metadata with the resolved reference feed, configure the
        // logical single-player multiview state before the physical fallback scheduler runs.
        // TME is a session-level playback contract. Prefer the reference feed's
        // payload rather than whichever secondary feed happened to resolve first.
        // This prevents a later feed resolution from silently replacing the
        // multiview session definition.
        val reference = streams.firstOrNull { it.id == referenceId }
        var openTiledPrepared = false
        reference
            ?.tmeJson
            ?.let(TiledMultiviewSessionParser::parse)
            ?.let { tme ->
                val source = reference ?: return@let
                if (openTiledBackend.canHandle(tme, source)) {
                    openTiledPrepared = openTiledBackend.prepare(tme, source, referenceId)
                    if (openTiledPrepared) {
                        selectedMultiviewBackend = openTiledBackend
                        _backendStatus.value = selectedMultiviewBackend.status
                        TiledMultiviewSessionParser.parse(source.tmeJson)
                            ?.also { tiledMultiviewController.configure(it) }
                    } else {
                        selectedMultiviewBackend = media3FallbackBackend
                        _backendStatus.value = selectedMultiviewBackend.status
                    }
                } else {
                    selectedMultiviewBackend = media3FallbackBackend
                    _backendStatus.value = selectedMultiviewBackend.status
                    configureTiledMultiview(tme.toModel())
                }
            }

        // A genuine single-source tiled session owns the only physical video player.
        // Do not preload, allocate, suspend, or reactivate the independent-feed
        // decoder manager after the open tiled backend has been selected.
        if (openTiledPrepared) return

        if (referenceId != null) setAudioPlayer(referenceId)

        // Warm logical feed sources before decoder scheduling. This is the key feed-rail
        // latency path: source/sample preload does not consume a physical video decoder.
        streams.forEachIndexed { index, stream -> decoderManager.preload(stream, index) }
        decoderManager.updatePreloadRanking(streams.map { it.id }, referenceId)
        val active = decoderManager.activeDecoderIds()
        val target = viewportScheduler.schedule(streams, visibleIds, referenceId, active)
        val targetIds = target.map { it.id }.toSet()

        // Reconcile transactionally. Never destroy an active feed until a decoder slot
        // has been made available for its replacement. If replacement loading fails,
        // restore the evicted feed so the visible multiview is not left with a blank tile.
        val evictionCandidates = active
            .filterNot(targetIds::contains)
            .mapNotNull { id -> streams.firstOrNull { it.id == id } }
            .toMutableList()

        target.forEach { stream ->
            if (!decoderManager.hasDecoder(stream.id)) {
                var evicted: StreamSource? = null
                if (decoderManager.availableDecoderSlots() == 0) {
                    evicted = if (evictionCandidates.isNotEmpty()) evictionCandidates.removeAt(0) else null
                    evicted?.let { suspend(it.id) }
                }

                val loaded = load(stream)
                if (!loaded) {
                    evicted?.let { restore ->
                        load(restore)
                    }
                }
            }
            if (autoplay && decoderManager.hasDecoder(stream.id)) {
                play(stream.id)
            }
        }

        // Remove any remaining decoders that are no longer in the viewport target.
        decoderManager.activeDecoderIds()
            .filterNot(targetIds::contains)
            .forEach(::suspend)

        // Decoder leases are intentionally dynamic. Notify the Compose surface layer whenever
        // a feed is allocated/released so a SurfaceView that was created before the decoder
        // existed gets rebound to the newly allocated ExoPlayer instead of staying black.
        _decoderGeneration.value += 1L
        return targetIds
    }

    fun setQuality(id: String, quality: Quality) {
        if (isOpenTiledActive()) openTiledEngine.setQuality(quality)
        else decoderManager.setQuality(id, quality)
    }
    fun setQuality(quality: Quality) {
        if (isOpenTiledActive()) openTiledEngine.setQuality(quality)
        else decoderManager.setQuality(quality)
    }
    fun setTiledAudioLanguage(language: String?) {
        if (isOpenTiledActive()) openTiledEngine.selectAudioLanguage(language)
    }
    fun setTiledSubtitleLanguage(language: String?) {
        if (isOpenTiledActive()) openTiledEngine.selectSubtitleLanguage(language)
    }
    fun getQuality(id: String): Quality = decoderManager.getQuality(id)
    fun availableVideoResolutions(id: String): List<Pair<Int, Int>> = decoderManager.availableVideoResolutions(id)
    fun availableVideoResolutionsForQualityMenu(id: String): List<Pair<Int, Int>> =
        decoderManager.availableVideoResolutionsForQualityMenu(id)
    fun currentVideoDiagnostics(id: String): VideoDiagnostics? = decoderManager.currentVideoDiagnostics(id)
    fun qualityAvailable(id: String, quality: Quality): Boolean = decoderManager.qualityAvailable(id, quality)

    fun setAudioPlayer(id: String?) {
        decoderManager.setAudioPlayer(id)
    }

    fun setMuted(id: String, muted: Boolean) = decoderManager.setMuted(id, muted)
    fun isMuted(id: String): Boolean = decoderManager.isMuted(id)

    fun syncToMain(mainId: String) = syncToMain(mainId, emptyMap())

    fun syncToMain(mainId: String, channelOffsetsMs: Map<String, Long>) {
        decoderManager.syncToMain(mainId, channelOffsetsMs)
    }

    fun play(id: String) = decoderManager.play(id)
    fun prepare(id: String) = decoderManager.prepare(id)
    fun seekTo(id: String, positionMs: Long) = decoderManager.seekTo(id, positionMs)
    fun seekToDefaultPosition(id: String) = decoderManager.seekToDefaultPosition(id)
    fun setPlaybackParameters(id: String, parameters: androidx.media3.common.PlaybackParameters) = decoderManager.setPlaybackParameters(id, parameters)
    fun setPlaybackSpeed(id: String, speed: Float) = decoderManager.setPlaybackSpeed(id, speed)
    internal fun attachSurfaceView(id: String, surface: SurfaceView) = decoderManager.attachSurfaceView(id, surface)
    internal fun detachSurfaceView(id: String, surface: SurfaceView) = decoderManager.detachSurfaceView(id, surface)
    internal fun attachTextureView(id: String, texture: TextureView) = decoderManager.attachTextureView(id, texture)
    internal fun detachTextureView(id: String, texture: TextureView) = decoderManager.detachTextureView(id, texture)
    fun pause(id: String) = decoderManager.pause(id)
    fun playAll() = decoderManager.playAll()
    fun pauseAll() = decoderManager.pauseAll()
    fun stopAll() = decoderManager.stopAll()

    fun playbackStartupDiagnostics(): Map<String, String> = decoderManager.playbackStartupDiagnostics()
    fun decoderResourceDiagnostics(): Map<String, String> = decoderManager.decoderResourceDiagnostics()

    fun attachSurface(feedId: String, player: EnginePlayerHandle, stream: StreamSource, source: String, screenshotMode: Boolean = false): android.widget.FrameLayout =
        surfaceManager.attach(feedId, player, stream, source, screenshotMode)

    fun updateSurface(feedId: String, player: EnginePlayerHandle, source: String) =
        surfaceManager.update(feedId, player, source)

    fun detachSurface(feedId: String, player: EnginePlayerHandle) =
        surfaceManager.detach(feedId, player)

    fun renderSlots(): List<MultiviewRenderCoordinator.RenderSlot> = surfaceManager.renderSlots()

    fun isGpuComposable(feedId: String): Boolean = surfaceManager.isGpuComposable(feedId)

    fun release() {
        tiledMultiviewController.clear()
        surfaceManager.clear()
        feedRegistry.clear()
        decoderManager.release()
    }

}

/** Decoder/resource ownership boundary. Media3 is the first backend implementation. */
private class DecoderManager(context: Context) {
    private val backend = Media3DecoderManager(context)

    val errors: StateFlow<Map<String, String>> = backend.errors

    fun hasDecoder(id: String): Boolean = backend.hasDecoder(id)
    fun capacity(): Int = backend.capacity()
    fun availableDecoderSlots(): Int = backend.availableDecoderSlots()
    fun activeDecoderIds(): Set<String> = backend.activeDecoderIds()
    fun get(id: String): ExoPlayer = backend.get(id)
    fun getOrNull(id: String): ExoPlayer? = backend.getOrNull(id)
    fun load(stream: StreamSource, forceReload: Boolean) = backend.load(stream, forceReload)
    fun retain(ids: Set<String>) = backend.retain(ids)
    fun suspend(id: String) = backend.suspend(id)
    fun preload(stream: StreamSource, rank: Int) = backend.preload(stream, rank)
    fun updatePreloadRanking(orderedIds: List<String>, referenceId: String?) = backend.updatePreloadRanking(orderedIds, referenceId)
    fun clear(id: String) = backend.clear(id)
    fun setQuality(id: String, quality: Quality) = backend.setQuality(id, quality)
    fun setQuality(quality: Quality) = backend.setQuality(quality)
    fun getQuality(id: String): Quality = backend.getQuality(id)
    fun availableVideoResolutions(id: String) = backend.availableVideoResolutions(id)
    fun availableVideoResolutionsForQualityMenu(id: String) = backend.availableVideoResolutionsForQualityMenu(id)
    fun currentVideoDiagnostics(id: String) = backend.currentVideoDiagnostics(id)
    fun qualityAvailable(id: String, quality: Quality) = backend.qualityAvailable(id, quality)
    fun setAudioPlayer(id: String?) = backend.setAudioPlayer(id)
    fun setMuted(id: String, muted: Boolean) = backend.setMuted(id, muted)
    fun isMuted(id: String) = backend.isMuted(id)
    fun syncToMain(mainId: String, channelOffsetsMs: Map<String, Long>) = backend.syncToMain(mainId, channelOffsetsMs)
    fun play(id: String) = backend.play(id)
    fun prepare(id: String) = backend.prepare(id)
    fun seekTo(id: String, positionMs: Long) = backend.seekTo(id, positionMs)
    fun seekToDefaultPosition(id: String) = backend.seekToDefaultPosition(id)
    fun setPlaybackParameters(id: String, parameters: androidx.media3.common.PlaybackParameters) = backend.setPlaybackParameters(id, parameters)
    fun setPlaybackSpeed(id: String, speed: Float) = backend.setPlaybackSpeed(id, speed)
    fun attachSurfaceView(id: String, surface: SurfaceView) = backend.attachSurfaceView(id, surface)
    fun detachSurfaceView(id: String, surface: SurfaceView) = backend.detachSurfaceView(id, surface)
    fun attachTextureView(id: String, texture: TextureView) = backend.attachTextureView(id, texture)
    fun detachTextureView(id: String, texture: TextureView) = backend.detachTextureView(id, texture)
    fun pause(id: String) = backend.pause(id)
    fun playAll() = backend.playAll()
    fun pauseAll() = backend.pauseAll()
    fun stopAll() = backend.stopAll()
    fun playbackStartupDiagnostics() = backend.playbackStartupDiagnostics()
    fun decoderResourceDiagnostics() = backend.decoderResourceDiagnostics()
    fun release() {
        openTiledEngine.release()
        backend.release()
    }
}

/** Logical feed registry. A feed is intentionally not equivalent to a decoder. */
private class FeedRegistry {
    private val feeds = linkedMapOf<String, StreamSource>()

    fun put(stream: StreamSource) { feeds[stream.id] = stream }
    fun remove(id: String) { feeds.remove(id) }
    fun retain(ids: Set<String>) { feeds.keys.filterNot(ids::contains).toList().forEach(feeds::remove) }
    fun clear() { feeds.clear() }
}

