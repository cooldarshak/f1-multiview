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
import app.f1multiview.data.f1tv.TmePlaybackParser
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
    private var openTiledMuted = false
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

    /** Runtime diagnostics for the active multiview backend. No URLs or auth tokens. */
    fun multiviewDiagnostics(): Map<String, String> =
        if (isOpenTiledActive()) openTiledEngine.diagnostics()
        else decoderResourceDiagnostics()

    fun isOpenTiledActive(): Boolean =
        selectedMultiviewBackend === openTiledBackend

    fun openTiledView(context: Context, protectedOutput: Boolean = false): OpenTiledCompositorView =
        OpenTiledCompositorView(context, protectedOutput)

    fun protectedTiledCompositorSupported(): Boolean =
        OpenTiledCompositorView.supportsProtectedOutput()

    fun selectOpenTiledFeeds(feedIds: List<String>) {
        openTiledEngine.selectVisibleFeeds(feedIds)
    }

    fun setOpenTiledOutputSlots(slots: List<OpenTiledMultiviewEngine.OutputSlot>) {
        openTiledEngine.setOutputSlots(slots)
    }

    fun openTiledFrameOutput(): OpenTiledFrameOutput? =
        if (isOpenTiledActive()) openTiledEngine.frameOutput() else null

    fun startOpenTiledClockCorrection(referencePositionProvider: () -> Long, intervalMs: Long = 500L) {
        if (isOpenTiledActive()) openTiledEngine.startClockCorrection(referencePositionProvider, intervalMs)
    }

    fun stopOpenTiledClockCorrection() {
        openTiledEngine.stopClockCorrection()
    }

    fun attachOpenTiledView(view: OpenTiledCompositorView) {
        if (isOpenTiledActive()) openTiledEngine.attachTo(view)
    }

    fun attachOpenTiledSecureView(view: OpenTiledSecureSurfaceView) {
        if (isOpenTiledActive()) openTiledEngine.attachSecureTo(view)
    }

    fun detachOpenTiledSecureView(view: OpenTiledSecureSurfaceView) {
        view.clearOutputSurface()
        view.setListener(null)
    }

    fun detachOpenTiledView(view: OpenTiledCompositorView) {
        openTiledEngine.detachFrom(view)
        view.releaseOutput()
        view.setListener(null)
    }

    fun setTiledFeeds(feedIds: List<String>) = tiledMultiviewController.setFeeds(feedIds)

    fun focusTiledFeed(feedId: String) = tiledMultiviewController.focus(feedId)

    fun setTiledAudio(feedId: String) = tiledMultiviewController.setAudio(feedId)

    fun setTiledSlots(slots: List<TiledMultiviewController.ViewSlot>) =
        tiledMultiviewController.setSlots(slots)


    /**
     * Resolves a UI/logical feed to its physical player.
     *
     * A single-source TME session deliberately has one physical ExoPlayer for
     * every logical feed. This is the hard ownership boundary: no caller,
     * including older UI code, may accidentally fall through to the independent
     * decoder manager for a tiled feed.
     */
    internal fun backendPlayer(id: String): ExoPlayer =
        openTiledPlayerOrNull(id) ?: decoderManager.get(id)

    internal fun backendPlayerOrNull(id: String): ExoPlayer? =
        openTiledPlayerOrNull(id) ?: decoderManager.getOrNull(id)

    /**
     * Returns the single physical tiled player for a logical feed.
     *
     * Do not gate this on the logical feed ID being present in the published tile list.
     * UI state can race the TME session publication while the physical player already
     * exists. A genuine single-source TME session has exactly one physical player, so
     * any logical feed observed while that player is active must share it rather than
     * falling through to Media3DecoderManager.get(), which can report an unallocated
     * decoder for that logical ID.
     */
    internal fun openTiledPlayerOrNull(id: String): ExoPlayer? {
        val tiledPlayer = openTiledEngine.playerOrNull()
        return if (tiledPlayer != null && openTiledEngine.state.value.active) tiledPlayer else null
    }

    fun load(stream: StreamSource, forceReload: Boolean = false): Boolean {
        feedRegistry.put(stream)
        return decoderManager.load(stream, forceReload)
    }

    fun retain(ids: Set<String>) {
        AppLogger.d("Engine", "retain requested ids=${ids.joinToString(",")}")
        surfaceManager.boundFeedIds().filterNot(ids::contains).forEach { id ->
            surfaceManager.binding(id)?.let { binding ->
                surfaceManager.detach(id, binding.owner, binding.container)
            }
        }
        feedRegistry.retain(ids)
        decoderManager.retain(ids)
        AppLogger.d("Engine", "retain complete active=${decoderManager.activeDecoderIds()}")
    }

    internal fun hasDecoder(id: String): Boolean = decoderManager.hasDecoder(id)
    fun activeDecoderIds(): Set<String> = decoderManager.activeDecoderIds()
    fun availableDecoderSlots(): Int = decoderManager.availableDecoderSlots()
    fun decoderCapacity(): Int = decoderManager.capacity()

    fun suspend(id: String) = decoderManager.suspend(id)

    fun clear(id: String) {
        surfaceManager.binding(id)?.let { binding -> surfaceManager.detach(id, binding.owner, binding.container) }
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
        autoplay: Boolean = false,
        allowTiledBackend: Boolean = true
    ): Set<String> {
        streams.forEach(feedRegistry::put)

        val reference = streams.firstOrNull { it.id == referenceId }
        var openTiledPrepared = false

        if (allowTiledBackend) {
            // TME is an optional session-level backend. Standard Director multiview
            // deliberately does not enter this path because it must support mixed
            // video + tracker/data feeds and independently resizable surfaces.
            reference
                ?.tmeJson
                ?.let(TmePlaybackParser::parse)
                ?.let { tme ->
                    val source = reference ?: return@let
                    if (openTiledBackend.canHandle(tme, source)) {
                        openTiledPrepared = openTiledBackend.prepare(tme, source, referenceId)
                        if (openTiledPrepared) {
                            selectedMultiviewBackend = openTiledBackend
                            _backendStatus.value = selectedMultiviewBackend.status
                            tiledMultiviewController.configure(tme.toModel())
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
        } else if (isOpenTiledActive()) {
            // Returning to the standard independent-feed wall must release the single
            // TME player before decoder allocation starts. This is a real backend switch,
            // not a guard hiding the tiled player behind the UI.
            openTiledEngine.stopClockCorrection()
            openTiledEngine.release()
            tiledMultiviewController.clear()
            selectedMultiviewBackend = media3FallbackBackend
            _backendStatus.value = selectedMultiviewBackend.status
        }

        if (openTiledPrepared) {
            val referenceIsVideo = reference?.kind !in setOf(
                app.f1multiview.model.StreamKind.TRACK_MAP,
                app.f1multiview.model.StreamKind.F1_DASH_DATA,
                app.f1multiview.model.StreamKind.TIMING,
                app.f1multiview.model.StreamKind.TRACK
            )
            setAudioPlayer(referenceId.takeIf { referenceIsVideo })
            return streams.map { it.id }.toSet()
        }

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
        if (isOpenTiledActive()) {
            if (id == null) {
                openTiledEngine.player()?.volume = 0f
            } else if (!openTiledMuted) {
                openTiledEngine.player()?.volume = 1f
            }
        } else {
            decoderManager.setAudioPlayer(id)
        }
    }

    fun setMuted(id: String, muted: Boolean) {
        if (isOpenTiledActive()) {
            openTiledMuted = muted
            openTiledEngine.player()?.volume = if (muted) 0f else 1f
        } else {
            decoderManager.setMuted(id, muted)
        }
    }

    fun isMuted(id: String): Boolean =
        if (isOpenTiledActive()) openTiledMuted else decoderManager.isMuted(id)

    fun syncToMain(mainId: String) = syncToMain(mainId, emptyMap())

    fun syncToMain(mainId: String, channelOffsetsMs: Map<String, Long>) {
        if (!isOpenTiledActive()) decoderManager.syncToMain(mainId, channelOffsetsMs)
    }

    fun play(id: String) { if (isOpenTiledActive()) openTiledEngine.play() else decoderManager.play(id) }
    fun prepare(id: String) = decoderManager.prepare(id)
    fun seekTo(id: String, positionMs: Long) = decoderManager.seekTo(id, positionMs)
    fun seekToDefaultPosition(id: String) = decoderManager.seekToDefaultPosition(id)
    fun setPlaybackParameters(id: String, parameters: androidx.media3.common.PlaybackParameters) = decoderManager.setPlaybackParameters(id, parameters)
    fun setPlaybackSpeed(id: String, speed: Float) { if (isOpenTiledActive()) openTiledEngine.setPlaybackSpeed(speed) else decoderManager.setPlaybackSpeed(id, speed) }
    internal fun attachSurfaceView(id: String, surface: SurfaceView) = decoderManager.attachSurfaceView(id, surface)
    internal fun detachSurfaceView(id: String, surface: SurfaceView) = decoderManager.detachSurfaceView(id, surface)
    internal fun attachTextureView(id: String, texture: TextureView) = decoderManager.attachTextureView(id, texture)
    internal fun detachTextureView(id: String, texture: TextureView) = decoderManager.detachTextureView(id, texture)
    fun pause(id: String) { if (isOpenTiledActive()) openTiledEngine.pause() else decoderManager.pause(id) }
    fun playAll() { if (isOpenTiledActive()) openTiledEngine.play() else decoderManager.playAll() }
    fun pauseAll() { if (isOpenTiledActive()) openTiledEngine.pause() else decoderManager.pauseAll() }
    fun stopAll() { if (isOpenTiledActive()) openTiledEngine.pause() else decoderManager.stopAll() }
    fun seekOpenTiled(deltaMs: Long) {
        if (isOpenTiledActive()) {
            val p = openTiledEngine.player() ?: return
            openTiledEngine.seekTo((p.currentPosition + deltaMs).coerceAtLeast(0L))
        }
    }


    fun playbackStartupDiagnostics(): Map<String, String> = decoderManager.playbackStartupDiagnostics()
    fun decoderResourceDiagnostics(): Map<String, String> = decoderManager.decoderResourceDiagnostics()

    fun attachSurface(
        feedId: String,
        player: EnginePlayerHandle,
        stream: StreamSource,
        source: String,
        container: android.widget.FrameLayout,
        screenshotMode: Boolean = false
    ) = surfaceManager.bind(feedId, player, stream, source, container, screenshotMode)

    fun updateSurface(feedId: String, player: EnginePlayerHandle, source: String) =
        surfaceManager.update(feedId, player, source)

    fun detachSurface(feedId: String, player: EnginePlayerHandle, container: android.widget.FrameLayout) =
        surfaceManager.detach(feedId, player, container)

    fun renderSlots(): List<MultiviewRenderCoordinator.RenderSlot> = surfaceManager.renderSlots()

    fun isGpuComposable(feedId: String): Boolean = surfaceManager.isGpuComposable(feedId)

    fun release() {
        openTiledMuted = false
        tiledMultiviewController.clear()
        surfaceManager.clear()
        feedRegistry.clear()
        openTiledEngine.release()
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

