package app.f1multiview.media

import android.content.Context
import android.view.SurfaceView
import android.view.TextureView
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.flow.StateFlow
import app.f1multiview.core.playback.Quality
import app.f1multiview.model.StreamSource

/**
 * Single ownership boundary for all multiview media state.
 *
 * The UI talks to this engine, not directly to a collection of players. The current
 * Media3 backend is intentionally kept behind DecoderManager so the decoder/resource
 * strategy can be replaced without changing the Compose layer.
 */
class UnifiedMultiviewEngine(context: Context) {
    private val decoderManager = DecoderManager(context)
    private val feedRegistry = FeedRegistry()
    private val playbackClock = PlaybackClock()
    private val surfaceManager = MultiviewSurfaceManager(context)

    val errors: StateFlow<Map<String, String>> = decoderManager.errors

    fun player(id: String): EnginePlayerHandle = EnginePlayerHandle(this, id)

    internal fun backendPlayer(id: String): ExoPlayer = decoderManager.get(id)

    fun load(stream: StreamSource, forceReload: Boolean = false): Boolean {
        feedRegistry.put(stream)
        return decoderManager.load(stream, forceReload)
    }

    fun retain(ids: Set<String>) {
        surfaceManager.boundFeedIds().filterNot(ids::contains).forEach { id ->
            surfaceManager.binding(id)?.let { binding ->
                if (decoderManager.hasDecoder(id)) surfaceManager.detach(id, player(id))
            }
        }
        feedRegistry.retain(ids)
        decoderManager.retain(ids)
    }

    internal fun hasDecoder(id: String): Boolean = decoderManager.hasDecoder(id)

    fun clear(id: String) {
        if (surfaceManager.binding(id) != null) surfaceManager.detach(id, player(id))
        feedRegistry.remove(id)
        playbackClock.onFeedRemoved(id)
        decoderManager.clear(id)
    }

    fun setQuality(id: String, quality: Quality) = decoderManager.setQuality(id, quality)
    fun setQuality(quality: Quality) = decoderManager.setQuality(quality)
    fun getQuality(id: String): Quality = decoderManager.getQuality(id)
    fun availableVideoResolutions(id: String): List<Pair<Int, Int>> = decoderManager.availableVideoResolutions(id)
    fun availableVideoResolutionsForQualityMenu(id: String): List<Pair<Int, Int>> =
        decoderManager.availableVideoResolutionsForQualityMenu(id)
    fun currentVideoDiagnostics(id: String): VideoDiagnostics? = decoderManager.currentVideoDiagnostics(id)
    fun qualityAvailable(id: String, quality: Quality): Boolean = decoderManager.qualityAvailable(id, quality)

    fun setAudioPlayer(id: String?) {
        playbackClock.setReference(id)
        decoderManager.setAudioPlayer(id)
    }

    fun setMuted(id: String, muted: Boolean) = decoderManager.setMuted(id, muted)
    fun isMuted(id: String): Boolean = decoderManager.isMuted(id)

    fun syncToMain(mainId: String) = syncToMain(mainId, emptyMap())

    fun syncToMain(mainId: String, channelOffsetsMs: Map<String, Long>) {
        playbackClock.setReference(mainId)
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

    fun release() {
        surfaceManager.clear()
        playbackClock.clear()
        feedRegistry.clear()
        decoderManager.release()
    }

}

/** Decoder/resource ownership boundary. Media3 is the first backend implementation. */
private class DecoderManager(context: Context) {
    private val backend = Media3DecoderManager(context)

    val errors: StateFlow<Map<String, String>> = backend.errors

    fun hasDecoder(id: String): Boolean = backend.hasDecoder(id)
    fun get(id: String): ExoPlayer = backend.get(id)
    fun load(stream: StreamSource, forceReload: Boolean) = backend.load(stream, forceReload)
    fun retain(ids: Set<String>) = backend.retain(ids)
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
    fun release() = backend.release()
}

/** Logical feed registry. A feed is intentionally not equivalent to a decoder. */
private class FeedRegistry {
    private val feeds = linkedMapOf<String, StreamSource>()

    fun put(stream: StreamSource) { feeds[stream.id] = stream }
    fun remove(id: String) { feeds.remove(id) }
    fun retain(ids: Set<String>) { feeds.keys.filterNot(ids::contains).toList().forEach(feeds::remove) }
    fun clear() { feeds.clear() }
}

/** Central reference-clock state. Decoder scheduling remains a backend concern for now. */
private class PlaybackClock {
    var referenceFeedId: String? = null
        private set

    fun setReference(id: String?) { referenceFeedId = id }
    fun onFeedRemoved(id: String) { if (referenceFeedId == id) referenceFeedId = null }
    fun clear() { referenceFeedId = null }
}