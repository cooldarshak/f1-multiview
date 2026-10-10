package app.f1multiview.media

import android.content.Context
import android.os.Looper
import android.view.SurfaceView
import android.view.TextureView
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.common.Tracks
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.text.Cue
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
    private val protectedQualities = linkedMapOf<String, Quality>()
    private var selectedAudioFeedId: String? = null
    private var multiFeedBlocked = false

    val errors: StateFlow<Map<String, String>> = decoderManager.errors
    private val _decoderGeneration = MutableStateFlow(0L)
    val decoderGeneration: StateFlow<Long> = _decoderGeneration
    private val _multiviewStatus = MutableStateFlow(
        "Single-feed Media3 playback is available. Three-feed own-engine prototype requires device validation before F1 multiview integration."
    )
    val multiviewStatus: StateFlow<String> = _multiviewStatus

    fun player(id: String): EnginePlayerHandle = EnginePlayerHandle(this, id)

    val protectedAudioTrackVersion: StateFlow<Int> get() = decoderManager.protectedAudioTrackVersion
    val protectedSubtitleCues: StateFlow<Map<String, List<Cue>>> get() = decoderManager.protectedSubtitleCues

    internal fun currentTracks(id: String, fallback: Tracks): Tracks =
        if (multiFeedBlocked && decoderManager.hasAudioOnlyFeed(id)) decoderManager.currentTracks(id) else fallback

    internal fun trackSelectionParameters(
        id: String,
        fallback: TrackSelectionParameters
    ): TrackSelectionParameters =
        if (multiFeedBlocked && decoderManager.hasAudioOnlyFeed(id)) decoderManager.trackSelectionParameters(id) ?: fallback
        else fallback

    internal fun setTrackSelectionParameters(id: String, parameters: TrackSelectionParameters) {
        if (!multiFeedBlocked || (id == selectedAudioFeedId && decoderManager.hasAudioOnlyFeed(id))) {
            decoderManager.setTrackSelectionParameters(id, parameters)
        }
    }

    internal fun currentPositionMs(id: String, fallback: Long): Long =
        if (multiFeedBlocked && id in protectedRuntimes) protectedRuntimes[id]?.currentPositionMs() ?: 0L else fallback

    internal fun isFeedPlaying(id: String, fallback: Boolean): Boolean =
        if (multiFeedBlocked && id in protectedRuntimes) protectedRuntimes[id]?.isPlaying == true else fallback

    internal fun durationMs(id: String, fallback: Long): Long =
        if (multiFeedBlocked && id in protectedRuntimes) protectedRuntimes[id]?.durationMs ?: androidx.media3.common.C.TIME_UNSET else fallback

    internal fun playbackState(id: String, fallback: Int): Int =
        if (multiFeedBlocked && id in protectedRuntimes) when (protectedRuntimes[id]?.state) {
            ProtectedCmafFeedRuntime.State.PREPARING,
            ProtectedCmafFeedRuntime.State.WAITING_FOR_SURFACE_OR_KEYS -> androidx.media3.common.Player.STATE_BUFFERING
            ProtectedCmafFeedRuntime.State.PLAYING,
            ProtectedCmafFeedRuntime.State.PAUSED -> androidx.media3.common.Player.STATE_READY
            else -> androidx.media3.common.Player.STATE_IDLE
        } else fallback

    fun multiviewDiagnostics(): Map<String, String> =
        decoderResourceDiagnostics() + surfaceManager.secureSurfaceDiagnostics() +
            protectedRuntimeStatus.mapKeys { (feedId, _) -> "protectedFeed.$feedId.status" } +
            protectedRuntimes.flatMap { (feedId, runtime) ->
                runtime.diagnostics().map { (key, value) -> "protectedFeed.$feedId.$key" to value }
            }.toMap() +
            mapOf("multiviewStatus" to _multiviewStatus.value)

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

    fun isProtectedFeedSelected(id: String): Boolean = multiFeedBlocked && id in protectedRuntimes
    fun isProtectedFeedPlaying(id: String): Boolean = protectedRuntimes[id]?.isPlaying == true
    fun protectedFeedStatus(id: String): String? = protectedRuntimeStatus[id]

    fun hasDecoder(id: String): Boolean = decoderManager.hasDecoder(id)
    fun activeDecoderIds(): Set<String> = decoderManager.activeDecoderIds()
    fun availableDecoderSlots(): Int = decoderManager.availableDecoderSlots()
    fun decoderCapacity(): Int = decoderManager.capacity()
    fun suspend(id: String) = decoderManager.suspend(id)

    fun clear(id: String) {
        closeProtectedRuntime(id)
        if (selectedAudioFeedId == id) selectedAudioFeedId = null
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
        val visibleVideoCount = ProtectedMultiviewFeedPolicy.visibleVideoCount(streams, visibleIds)
        if (visibleVideoCount > 1) {
            val enteringProtectedMultiview = !multiFeedBlocked
            multiFeedBlocked = true
            val selectedVideoStreams = ProtectedMultiviewFeedPolicy.selectedVideoStreams(streams, visibleIds)
            val mainFeedId = referenceId ?: selectedVideoStreams.firstOrNull()?.id
            if (mainFeedId == null) {
                _multiviewStatus.value = "Protected multiview blocked: no selected video feeds resolved"
                return emptySet()
            }
            protectedPresentationClock.setMaster(mainFeedId)
            // Release Media3 video decoders only on entry. One authorized audio-only player may
            // remain active while every video feed uses the own secure MediaCodec runtime.
            if (enteringProtectedMultiview) decoderManager.retain(emptySet())
            protectedRuntimes.keys.filterNot(visibleIds::contains).toList().forEach(::closeProtectedRuntime)
            val desiredAudioId = selectedAudioFeedId?.takeIf { id -> selectedVideoStreams.any { it.id == id } } ?: mainFeedId
            val audioStream = selectedVideoStreams.firstOrNull { it.id == desiredAudioId }
            if (audioStream != null &&
                DrmProtectionPolicy.requiresProtectedOutput(audioStream) &&
                !DrmProtectionPolicy.missingLicenseEndpoint(audioStream)
            ) {
                if (selectedAudioFeedId != audioStream.id || !decoderManager.hasAudioOnlyFeed(audioStream.id)) {
                    if (!enteringProtectedMultiview) decoderManager.retain(emptySet())
                    if (!decoderManager.loadAudioOnly(audioStream)) {
                        selectedAudioFeedId = null
                        protectedRuntimeStatus["audio"] = "AUDIO_BLOCKED: authorized audio source could not be prepared"
                    } else {
                        selectedAudioFeedId = audioStream.id
                        decoderManager.setAudioPlayer(audioStream.id)
                        protectedRuntimeStatus.remove("audio")
                    }
                } else {
                    decoderManager.setAudioPlayer(audioStream.id)
                }
            } else {
                selectedAudioFeedId = null
                if (!enteringProtectedMultiview) decoderManager.retain(emptySet())
                decoderManager.setAudioPlayer(null)
                protectedRuntimeStatus["audio"] = "AUDIO_UNAVAILABLE: no selected feed has authorized Widevine audio"
            }
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
                            playbackLooper = Looper.getMainLooper(),
                            quality = protectedQualities[stream.id] ?: Quality.AUTO,
                            autoMaxWidth = if (stream.id == mainFeedId) Int.MAX_VALUE else 854,
                            autoMaxHeight = if (stream.id == mainFeedId) Int.MAX_VALUE else 480
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
            selectedAudioFeedId?.let(decoderManager::disableAudioOnlyMode)
            selectedAudioFeedId = null
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

    fun setQuality(id: String, quality: Quality) {
        if (multiFeedBlocked) {
            protectedQualities[id] = quality
            protectedRuntimes[id]?.setQuality(quality)
        } else decoderManager.setQuality(id, quality)
    }
    fun setQuality(quality: Quality) {
        if (multiFeedBlocked) {
            protectedRuntimes.keys.toList().forEach { id ->
                protectedQualities[id] = quality
                protectedRuntimes[id]?.setQuality(quality)
            }
        } else decoderManager.setQuality(quality)
    }
    fun getQuality(id: String): Quality =
        if (multiFeedBlocked) protectedQualities[id] ?: Quality.AUTO else decoderManager.getQuality(id)
    fun availableVideoResolutions(id: String): List<Pair<Int, Int>> =
        if (multiFeedBlocked) protectedRuntimes[id]?.availableVideoResolutions().orEmpty() else decoderManager.availableVideoResolutions(id)
    fun availableVideoResolutionsForQualityMenu(id: String): List<Pair<Int, Int>> =
        if (multiFeedBlocked) availableVideoResolutions(id) else decoderManager.availableVideoResolutionsForQualityMenu(id)
    fun currentVideoDiagnostics(id: String): VideoDiagnostics? =
        if (multiFeedBlocked) null else decoderManager.currentVideoDiagnostics(id)
    fun qualityAvailable(id: String, quality: Quality): Boolean =
        if (multiFeedBlocked) protectedRuntimes[id]?.qualityAvailable(quality) ?: (quality == Quality.AUTO)
        else decoderManager.qualityAvailable(id, quality)
    fun setAudioPlayer(id: String?) {
        if (!multiFeedBlocked) {
            selectedAudioFeedId = id
            decoderManager.setAudioPlayer(id)
            return
        }
        if (id == null) {
            selectedAudioFeedId = null
            decoderManager.retain(emptySet())
            decoderManager.setAudioPlayer(null)
            return
        }
        if (id !in protectedRuntimes) return
        val stream = feedRegistry.get(id) ?: return
        if (selectedAudioFeedId != id || !decoderManager.hasAudioOnlyFeed(id)) {
            decoderManager.retain(emptySet())
            if (!decoderManager.loadAudioOnly(stream)) {
                selectedAudioFeedId = null
                protectedRuntimeStatus["audio"] = "AUDIO_BLOCKED: authorized audio source could not be prepared"
                _multiviewStatus.value = "Own protected F1 pipeline: " +
                    protectedRuntimeStatus.toSortedMap().entries.joinToString(" | ") { "${it.key}: ${it.value}" }
                return
            }
        }
        selectedAudioFeedId = id
        protectedRuntimeStatus.remove("audio")
        decoderManager.setAudioPlayer(id)
        _multiviewStatus.value = "Own protected F1 pipeline: " +
            protectedRuntimeStatus.toSortedMap().entries.joinToString(" | ") { "${it.key}: ${it.value}" }
    }
    fun setMuted(id: String, muted: Boolean) {
        if (!multiFeedBlocked) decoderManager.setMuted(id, muted)
        else if (!muted) setAudioPlayer(id)
        else if (selectedAudioFeedId == id) decoderManager.setMuted(id, true)
    }
    fun isMuted(id: String): Boolean =
        if (multiFeedBlocked) !decoderManager.hasAudioOnlyFeed(id) || decoderManager.isMuted(id)
        else decoderManager.isMuted(id)
    fun isProtectedAudioAvailable(id: String): Boolean =
        multiFeedBlocked && decoderManager.hasAudioOnlyFeed(id)
    fun isProtectedAudioSelected(id: String): Boolean =
        multiFeedBlocked && selectedAudioFeedId == id && decoderManager.hasAudioOnlyFeed(id)
    fun syncToMain(mainId: String) = syncToMain(mainId, emptyMap())
    fun syncToMain(mainId: String, channelOffsetsMs: Map<String, Long>) {
        if (multiFeedBlocked) protectedPresentationClock.setMaster(mainId, channelOffsetsMs)
        else decoderManager.syncToMain(mainId, channelOffsetsMs)
    }
    fun prepare(id: String) { if (!multiFeedBlocked) decoderManager.prepare(id) }
    fun seekTo(id: String, positionMs: Long) {
        if (multiFeedBlocked) {
            val globalTargetUs = protectedRuntimes[protectedPresentationClock.masterFeedId]
                ?.let { it.globalPositionUsForWindow(positionMs * 1_000L) } ?: positionMs * 1_000L
            protectedPresentationClock.seekToPositionUs(globalTargetUs)
            protectedRuntimes.values.forEach { it.seekTo(positionMs) }
            selectedAudioFeedId?.let { decoderManager.seekTo(it, positionMs) }
        } else decoderManager.seekTo(id, positionMs)
    }
    fun seekToDefaultPosition(id: String) {
        if (multiFeedBlocked) {
            protectedPresentationClock.resetEpoch()
            protectedRuntimes.values.forEach(ProtectedCmafFeedRuntime::seekToDefaultPosition)
            selectedAudioFeedId?.let(decoderManager::seekToDefaultPosition)
        } else decoderManager.seekToDefaultPosition(id)
    }
    fun setPlaybackParameters(id: String, parameters: androidx.media3.common.PlaybackParameters) {
        if (multiFeedBlocked) {
            protectedPresentationClock.setPlaybackSpeed(parameters.speed)
            protectedRuntimes.values.forEach { it.setPlaybackSpeed(parameters.speed) }
            selectedAudioFeedId?.let { decoderManager.setPlaybackSpeed(it, parameters.speed) }
        } else decoderManager.setPlaybackParameters(id, parameters)
    }
    fun setPlaybackSpeed(id: String, speed: Float) {
        if (multiFeedBlocked) {
            protectedPresentationClock.setPlaybackSpeed(speed)
            protectedRuntimes.values.forEach { it.setPlaybackSpeed(speed) }
            selectedAudioFeedId?.let { decoderManager.setPlaybackSpeed(it, speed) }
        } else decoderManager.setPlaybackSpeed(id, speed)
    }
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
        if (multiFeedBlocked) {
            protectedRuntimes[id]?.pause()
            if (selectedAudioFeedId == id) decoderManager.pause(id)
        } else decoderManager.pause(id)
    }
    fun play(id: String) {
        if (multiFeedBlocked) {
            protectedRuntimes[id]?.play()
            if (selectedAudioFeedId == id) decoderManager.play(id)
        } else if (decoderManager.hasDecoder(id)) decoderManager.play(id)
    }
    fun playAll() {
        if (multiFeedBlocked) {
            protectedRuntimes.values.forEach(ProtectedCmafFeedRuntime::play)
            decoderManager.playAll()
        } else decoderManager.playAll()
    }
    fun pauseAll() {
        if (multiFeedBlocked) {
            protectedRuntimes.values.forEach(ProtectedCmafFeedRuntime::pause)
            decoderManager.pauseAll()
        } else decoderManager.pauseAll()
    }
    fun stopAll() {
        if (multiFeedBlocked) {
            protectedRuntimes.keys.toList().forEach(::closeProtectedRuntime)
            decoderManager.stopAll()
        } else decoderManager.stopAll()
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
    ): Long {
        if (multiFeedBlocked && !DrmProtectionPolicy.requiresProtectedOutput(stream)) return -1L
        val bindingGeneration = surfaceManager.bind(feedId, player, stream, source, container, screenshotMode, onVideoTap)
        if (multiFeedBlocked) protectedRuntimes[feedId]?.play()
        return bindingGeneration
    }

    fun updateSurface(feedId: String, player: EnginePlayerHandle, source: String) {
        if (!multiFeedBlocked) surfaceManager.update(feedId, player, source)
    }

    fun detachSurface(feedId: String, player: EnginePlayerHandle, container: android.widget.FrameLayout, expectedBindingGeneration: Long? = null) {
        surfaceManager.detach(feedId, player, container, expectedBindingGeneration)
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
        selectedAudioFeedId = null
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
    val protectedAudioTrackVersion: StateFlow<Int> = backend.protectedAudioTrackVersion
    val protectedSubtitleCues: StateFlow<Map<String, List<Cue>>> = backend.protectedSubtitleCues

    fun loadAudioOnly(stream: StreamSource): Boolean = backend.loadAudioOnly(stream)
    fun hasAudioOnlyFeed(id: String): Boolean = backend.hasAudioOnlyFeed(id)
    fun disableAudioOnlyMode(id: String) = backend.disableAudioOnlyMode(id)
    fun currentTracks(id: String): Tracks = backend.currentTracks(id)
    fun trackSelectionParameters(id: String): TrackSelectionParameters? = backend.trackSelectionParameters(id)
    fun setTrackSelectionParameters(id: String, parameters: TrackSelectionParameters) =
        backend.setTrackSelectionParameters(id, parameters)

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
    fun get(id: String): StreamSource? = feeds[id]
    fun remove(id: String) { feeds.remove(id) }
    fun retain(ids: Set<String>) { feeds.keys.filterNot(ids::contains).toList().forEach(feeds::remove) }
    fun clear() { feeds.clear() }
}

