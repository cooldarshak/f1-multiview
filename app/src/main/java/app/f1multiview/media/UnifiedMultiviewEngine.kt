package app.f1multiview.media

import android.content.Context
import android.os.Looper
import android.view.SurfaceView
import android.view.TextureView
import androidx.media3.exoplayer.ExoPlayer
import app.f1multiview.core.playback.Quality
import app.f1multiview.model.StreamSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
/**
 * App-owned playback coordinator. Media3 remains available for a single authorized feed;
 * independent multi-feed rendering is not routed through multiple ExoPlayers.
 *
 * Until the clear synthetic three-feed prototype has passed device validation and an app-owned
 * authorized F1 demux/DRM/decoder pipeline has been integrated, this coordinator fails visibly
 * closed for more than one visible video feed. Protected feeds may require independent secure
 * SurfaceView layers composed by Android; they must not be sampled into the clear GLES compositor.
 */
class UnifiedMultiviewEngine(context: Context) {
    private val appContext = context.applicationContext
    private val decoderManager = DecoderManager(context)
    private val feedRegistry = FeedRegistry()
    private val surfaceManager = MultiviewSurfaceManager(context)
    private val viewportScheduler = ViewportScheduler(maxDecoders = decoderManager.capacity())
    private val protectedPresentationClock = ProtectedPresentationClock()
    private val protectedRuntimes = linkedMapOf<String, ProtectedCmafFeedRuntime>()
    private val protectedRuntimeStatus = linkedMapOf<String, String>()
    private var multiFeedBlocked = false

    val errors: StateFlow<Map<String, String>> = decoderManager.errors
    private val _decoderGeneration = MutableStateFlow(0L)
    val decoderGeneration: StateFlow<Long> = _decoderGeneration
    private val _multiviewStatus = MutableStateFlow(
        "Single-feed Media3 playback is available. Three-feed own-engine prototype requires device validation before F1 multiview integration."
    )
    val multiviewStatus: StateFlow<String> = _multiviewStatus

    fun player(id: String): EnginePlayerHandle = EnginePlayerHandle(this, id)

    fun multiviewDiagnostics(): Map<String, String> =
        decoderResourceDiagnostics() + surfaceManager.secureSurfaceDiagnostics() + mapOf("multiviewStatus" to _multiviewStatus.value)

    internal fun backendPlayer(id: String): ExoPlayer {
        check(!multiFeedBlocked) { _multiviewStatus.value }
        return decoderManager.get(id)
    }

    internal fun backendPlayerOrNull(id: String): ExoPlayer? =
        if (multiFeedBlocked) null else decoderManager.getOrNull(id)

    fun load(stream: StreamSource, forceReload: Boolean = false): Boolean {
        feedRegistry.put(stream)
        if (multiFeedBlocked) {
            AppLogger.e("OwnMultiviewEngine", "LOAD_BLOCKED feed=${stream.id}; refusing independent-player fallback while multi-feed is selected")
            return false
        }
        return decoderManager.load(stream, forceReload)
    }

    fun retain(ids: Set<String>) {
        protectedRuntimes.keys.filterNot(ids::contains).toList().forEach(::closeProtectedRuntime)
        surfaceManager.boundFeedIds().filterNot(ids::contains).forEach { id ->
            surfaceManager.binding(id)?.let { binding ->
                surfaceManager.detach(id, binding.owner, binding.container)
            }
        }
        feedRegistry.retain(ids)
        decoderManager.retain(ids)
    }

    fun hasDecoder(id: String): Boolean = decoderManager.hasDecoder(id)
    fun activeDecoderIds(): Set<String> = decoderManager.activeDecoderIds()
    fun availableDecoderSlots(): Int = decoderManager.availableDecoderSlots()
    fun decoderCapacity(): Int = decoderManager.capacity()
    fun suspend(id: String) = decoderManager.suspend(id)

    fun clear(id: String) {
        closeProtectedRuntime(id)
        surfaceManager.binding(id)?.let { binding -> surfaceManager.detach(id, binding.owner, binding.container) }
        feedRegistry.remove(id)
        decoderManager.clear(id)
    }

    fun updateViewport(
        streams: List<StreamSource>,
        visibleIds: Set<String>,
        referenceId: String?,
        autoplay: Boolean = false
    ): Set<String> {
        streams.forEach(feedRegistry::put)
        val videoKinds = setOf(
            app.f1multiview.model.StreamKind.TRACK_MAP,
            app.f1multiview.model.StreamKind.F1_DASH_DATA,
            app.f1multiview.model.StreamKind.TIMING,
            app.f1multiview.model.StreamKind.TRACK
        )
        val visibleVideoCount = visibleIds.count { id ->
            streams.firstOrNull { it.id == id }?.kind !in videoKinds
        }
        if (visibleVideoCount > 1) {
            multiFeedBlocked = true
            val selectedVideoStreams = streams.filter { stream ->
                stream.id in visibleIds && stream.kind !in videoKinds
            }
            val mainFeedId = referenceId ?: selectedVideoStreams.firstOrNull()?.id
            if (mainFeedId == null) {
                _multiviewStatus.value = "Protected multiview blocked: no selected video feeds resolved"
                return emptySet()
            }
            protectedPresentationClock.setMaster(mainFeedId)
            // Release Media3 video decoders, but keep the AndroidView containers alive so each
            // authorized runtime can claim its own secure SurfaceView lease. No player fallback.
            decoderManager.retain(emptySet())
            protectedRuntimes.keys.filterNot(visibleIds::contains).toList().forEach(::closeProtectedRuntime)
            selectedVideoStreams.forEach { stream ->
                val compatibility = ProtectedFeedCompatibility.inspect(appContext, stream)
                AppLogger.i("F1MultiFeedCompatibility", "feed=${stream.id} ${compatibility.toLogFields()}")
                if (!DrmProtectionPolicy.requiresProtectedOutput(stream) ||
                    DrmProtectionPolicy.missingLicenseEndpoint(stream)
                ) {
                    closeProtectedRuntime(stream.id)
                    protectedRuntimeStatus[stream.id] =
                        "BLOCKED: feed requires authorized Widevine protection and a license endpoint"
                } else {
                    if (protectedRuntimes[stream.id]?.matches(stream) == false) closeProtectedRuntime(stream.id)
                    protectedRuntimes.getOrPut(stream.id) {
                        ProtectedCmafFeedRuntime(
                            stream = stream,
                            surfaceManager = surfaceManager,
                            clock = protectedPresentationClock,
                            playbackLooper = Looper.getMainLooper()
                        ) { feedId, status ->
                            protectedRuntimeStatus[feedId] = status
                            _multiviewStatus.value = "Own protected F1 pipeline: " +
                                protectedRuntimeStatus.toSortedMap().entries.joinToString(" | ") { "${it.key}: ${it.value}" }
                        }.also { it.start() }
                    }
                }
            }
            _multiviewStatus.value = "Own protected F1 pipeline: " +
                protectedRuntimeStatus.toSortedMap().entries.joinToString(" | ") { "${it.key}: ${it.value}" }
            AppLogger.i(
                "OwnMultiviewEngine",
                "MULTIVIEW_OWN_PIPELINE_START visibleVideoFeeds=$visibleVideoCount feeds=${selectedVideoStreams.joinToString(",") { it.id }}"
            )
            _decoderGeneration.value += 1L
            return selectedVideoStreams.map { it.id }.toSet()
        }

        if (multiFeedBlocked) {
            protectedRuntimes.keys.toList().forEach(::closeProtectedRuntime)
            protectedRuntimeStatus.clear()
            // The single-feed Media3 path gets a fresh surface binding after protected leases close.
            surfaceManager.clear()
        }
        multiFeedBlocked = false
        _multiviewStatus.value = if (visibleVideoCount == 1) {
            "Single authorized feed: Media3 playback. Own multi-feed renderer is not yet integrated with protected F1 media."
        } else {
            "No video feed selected."
        }
        if (referenceId != null) setAudioPlayer(referenceId)

        streams.forEachIndexed { index, stream -> decoderManager.preload(stream, index) }
        decoderManager.updatePreloadRanking(streams.map { it.id }, referenceId)
        val active = decoderManager.activeDecoderIds()
        val target = viewportScheduler.schedule(streams, visibleIds, referenceId, active)
        val targetIds = target.map { it.id }.toSet()

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
                if (!loaded) evicted?.let { restore -> load(restore) }
            }
            if (autoplay && decoderManager.hasDecoder(stream.id)) play(stream.id)
        }

        decoderManager.activeDecoderIds().filterNot(targetIds::contains).forEach(::suspend)
        _decoderGeneration.value += 1L
        return targetIds
    }

    fun setQuality(id: String, quality: Quality) = decoderManager.setQuality(id, quality)
    fun setQuality(quality: Quality) = decoderManager.setQuality(quality)
    fun getQuality(id: String): Quality = decoderManager.getQuality(id)
    fun availableVideoResolutions(id: String): List<Pair<Int, Int>> = decoderManager.availableVideoResolutions(id)
    fun availableVideoResolutionsForQualityMenu(id: String): List<Pair<Int, Int>> = decoderManager.availableVideoResolutionsForQualityMenu(id)
    fun currentVideoDiagnostics(id: String): VideoDiagnostics? = decoderManager.currentVideoDiagnostics(id)
    fun qualityAvailable(id: String, quality: Quality): Boolean = decoderManager.qualityAvailable(id, quality)
    fun setAudioPlayer(id: String?) { if (!multiFeedBlocked) decoderManager.setAudioPlayer(id) }
    fun setMuted(id: String, muted: Boolean) { if (!multiFeedBlocked) decoderManager.setMuted(id, muted) }
    fun isMuted(id: String): Boolean = if (multiFeedBlocked) true else decoderManager.isMuted(id)
    fun syncToMain(mainId: String) = syncToMain(mainId, emptyMap())
    fun syncToMain(mainId: String, channelOffsetsMs: Map<String, Long>) {
        if (!multiFeedBlocked) decoderManager.syncToMain(mainId, channelOffsetsMs)
    }
    fun prepare(id: String) { if (!multiFeedBlocked) decoderManager.prepare(id) }
    fun seekTo(id: String, positionMs: Long) { if (!multiFeedBlocked) decoderManager.seekTo(id, positionMs) }
    fun seekToDefaultPosition(id: String) { if (!multiFeedBlocked) decoderManager.seekToDefaultPosition(id) }
    fun setPlaybackParameters(id: String, parameters: androidx.media3.common.PlaybackParameters) {
        if (!multiFeedBlocked) decoderManager.setPlaybackParameters(id, parameters)
    }
    fun setPlaybackSpeed(id: String, speed: Float) { if (!multiFeedBlocked) decoderManager.setPlaybackSpeed(id, speed) }
    internal fun attachSurfaceView(id: String, surface: SurfaceView) {
        if (!multiFeedBlocked) decoderManager.attachSurfaceView(id, surface)
    }
    internal fun detachSurfaceView(id: String, surface: SurfaceView) {
        // Detach is resource cleanup, not playback admission; it must work while the gate is closed.
        decoderManager.detachSurfaceView(id, surface)
    }
    internal fun attachTextureView(id: String, texture: TextureView) {
        if (!multiFeedBlocked) decoderManager.attachTextureView(id, texture)
    }
    internal fun detachTextureView(id: String, texture: TextureView) {
        // Detach is resource cleanup, not playback admission; it must work while the gate is closed.
        decoderManager.detachTextureView(id, texture)
    }
    fun pause(id: String) {
        if (multiFeedBlocked) protectedRuntimes[id]?.pause() else decoderManager.pause(id)
    }
    fun play(id: String) {
        if (multiFeedBlocked) protectedRuntimes[id]?.play()
        else if (decoderManager.hasDecoder(id)) decoderManager.play(id)
    }
    fun playAll() {
        if (multiFeedBlocked) protectedRuntimes.values.forEach(ProtectedCmafFeedRuntime::play)
        else decoderManager.playAll()
    }
    fun pauseAll() {
        if (multiFeedBlocked) protectedRuntimes.values.forEach(ProtectedCmafFeedRuntime::pause)
        else decoderManager.pauseAll()
    }
    fun stopAll() {
        if (multiFeedBlocked) protectedRuntimes.keys.toList().forEach(::closeProtectedRuntime)
        else decoderManager.stopAll()
    }
    fun playbackStartupDiagnostics(): Map<String, String> = decoderManager.playbackStartupDiagnostics()
    fun decoderResourceDiagnostics(): Map<String, String> = decoderManager.decoderResourceDiagnostics()

    fun attachSurface(
        feedId: String,
        player: EnginePlayerHandle,
        stream: StreamSource,
        source: String,
        container: android.widget.FrameLayout,
        screenshotMode: Boolean = false,
        onVideoTap: (() -> Unit)? = null
    ) {
        if (multiFeedBlocked && !DrmProtectionPolicy.requiresProtectedOutput(stream)) return
        surfaceManager.bind(feedId, player, stream, source, container, screenshotMode, onVideoTap)
        if (multiFeedBlocked) protectedRuntimes[feedId]?.play()
    }

    fun updateSurface(feedId: String, player: EnginePlayerHandle, source: String) {
        if (!multiFeedBlocked) surfaceManager.update(feedId, player, source)
    }

    fun detachSurface(feedId: String, player: EnginePlayerHandle, container: android.widget.FrameLayout) {
        surfaceManager.detach(feedId, player, container)
        if (multiFeedBlocked) protectedRuntimes[feedId]?.pause()
    }

    fun renderSlots(): List<MultiviewRenderCoordinator.RenderSlot> = surfaceManager.renderSlots()
    fun isGpuComposable(feedId: String): Boolean = surfaceManager.isGpuComposable(feedId)

    private fun closeProtectedRuntime(id: String) {
        protectedRuntimes.remove(id)?.close()
        protectedRuntimeStatus.remove(id)
    }

    fun release() {
        protectedRuntimes.keys.toList().forEach(::closeProtectedRuntime)
        protectedRuntimeStatus.clear()
        multiFeedBlocked = false
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

