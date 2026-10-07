package app.f1multiview.media

import android.view.SurfaceView
import android.view.TextureView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.Format
import androidx.media3.common.Timeline
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.exoplayer.ExoPlayer

/**
 * UI-safe playback handle.
 *
 * Compose never owns an ExoPlayer instance. All playback, decoder, DRM and surface
 * operations remain owned by UnifiedMultiviewEngine; this handle is only a stable
 * per-feed control/observation facade.
 */
class EnginePlayerHandle internal constructor(
    private val engine: UnifiedMultiviewEngine,
    val id: String
) {
    private val player: ExoPlayer
        get() = engine.backendPlayer(id)

    val currentPosition: Long get() = player.currentPosition
    val duration: Long get() = player.duration
    val isPlaying: Boolean get() = player.isPlaying
    val playbackState: Int get() = player.playbackState
    val currentMediaItem: MediaItem? get() = player.currentMediaItem
    val currentTracks: Tracks get() = player.currentTracks
    val videoSize: VideoSize get() = player.videoSize
    val videoFormat: Format? get() = player.videoFormat
    val trackSelectionParameters: TrackSelectionParameters get() = player.trackSelectionParameters
    var trackSelectionParametersMutable: TrackSelectionParameters
        get() = player.trackSelectionParameters
        set(value) { player.trackSelectionParameters = value }
    var playWhenReady: Boolean
        get() = player.playWhenReady
        set(value) { player.playWhenReady = value }
    var volume: Float
        get() = player.volume
        set(value) { player.volume = value }
    var videoScalingMode: Int
        get() = player.videoScalingMode
        set(value) { player.videoScalingMode = value }

    fun play() = engine.play(id)
    fun pause() = engine.pause(id)
    fun prepare() = engine.prepare(id)
    fun seekTo(positionMs: Long) = engine.seekTo(id, positionMs)
    fun seekToDefaultPosition() = engine.seekToDefaultPosition(id)
    fun setPlaybackParameters(parameters: PlaybackParameters) = engine.setPlaybackParameters(id, parameters)
    fun setPlaybackSpeed(speed: Float) = engine.setPlaybackSpeed(id, speed)

    fun addListener(listener: Player.Listener) = player.addListener(listener)
    fun removeListener(listener: Player.Listener) = player.removeListener(listener)

    fun setTrackSelectionParameters(value: TrackSelectionParameters) {
        player.trackSelectionParameters = value
    }

    fun setVideoSurfaceView(surfaceView: SurfaceView) = engine.attachSurfaceView(id, surfaceView)
    fun clearVideoSurfaceView(surfaceView: SurfaceView) = engine.detachSurfaceView(id, surfaceView)
    fun setVideoTextureView(textureView: TextureView) = engine.attachTextureView(id, textureView)
    fun clearVideoTextureView(textureView: TextureView) = engine.detachTextureView(id, textureView)
}
