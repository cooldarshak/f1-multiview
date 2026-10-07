package app.f1multiview.media

import android.view.SurfaceView
import android.view.TextureView
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer

/**
 * UI-safe playback handle.
 *
 * Compose never owns an ExoPlayer instance. All playback, decoder, DRM and surface
 * operations remain owned by UnifiedMultiviewEngine; this handle is only a stable
 * per-feed control/observation facade.
 *
 * A logical feed may temporarily have no physical decoder lease. In that state the
 * handle remains valid and exposes safe idle/default values instead of crashing the UI.
 */
class EnginePlayerHandle internal constructor(
    private val engine: UnifiedMultiviewEngine,
    val id: String
) {
    private val player: ExoPlayer?
        get() = engine.backendPlayerOrNull(id)

    val currentPosition: Long get() = player?.currentPosition ?: 0L
    val duration: Long get() = player?.duration ?: C.TIME_UNSET
    val isPlaying: Boolean get() = player?.isPlaying == true
    val playbackState: Int get() = player?.playbackState ?: Player.STATE_IDLE
    val currentMediaItem: MediaItem? get() = player?.currentMediaItem
    val currentTracks: Tracks get() = player?.currentTracks ?: Tracks.EMPTY
    val videoSize: VideoSize get() = player?.videoSize ?: VideoSize(0, 0, 0f, 0)
    val videoFormat: Format? get() = player?.videoFormat

    var trackSelectionParameters: TrackSelectionParameters
        get() = player?.trackSelectionParameters ?: TrackSelectionParameters.DEFAULT
        set(value) { player?.trackSelectionParameters = value }

    var playWhenReady: Boolean
        get() = player?.playWhenReady == true
        set(value) { player?.playWhenReady = value }

    var volume: Float
        get() = player?.volume ?: 0f
        set(value) { player?.volume = value }

    var videoScalingMode: Int
        get() = player?.videoScalingMode ?: C.VIDEO_SCALING_MODE_SCALE_TO_FIT
        set(value) { player?.videoScalingMode = value }

    fun play() = engine.play(id)
    fun pause() = engine.pause(id)
    fun prepare() = engine.prepare(id)
    fun seekTo(positionMs: Long) = engine.seekTo(id, positionMs)
    fun seekToDefaultPosition() = engine.seekToDefaultPosition(id)
    fun setPlaybackParameters(parameters: PlaybackParameters) = engine.setPlaybackParameters(id, parameters)
    fun setPlaybackSpeed(speed: Float) = engine.setPlaybackSpeed(id, speed)

    fun addListener(listener: Player.Listener) {
        player?.addListener(listener)
    }

    fun removeListener(listener: Player.Listener) {
        player?.removeListener(listener)
    }

    fun setVideoSurfaceView(surfaceView: SurfaceView) = engine.attachSurfaceView(id, surfaceView)
    fun clearVideoSurfaceView(surfaceView: SurfaceView) = engine.detachSurfaceView(id, surfaceView)
    fun setVideoTextureView(textureView: TextureView) = engine.attachTextureView(id, textureView)
    fun clearVideoTextureView(textureView: TextureView) = engine.detachTextureView(id, textureView)
}
