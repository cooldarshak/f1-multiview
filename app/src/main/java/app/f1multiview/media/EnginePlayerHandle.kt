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

class EnginePlayerHandle internal constructor(
    private val engine: UnifiedMultiviewEngine,
    val id: String
) {
    private val player: ExoPlayer?
        get() = engine.backendPlayerOrNull(id)

    val currentPosition: Long get() = engine.currentPositionMs(id, player?.currentPosition ?: 0L)
    val duration: Long get() = engine.durationMs(id, player?.duration ?: C.TIME_UNSET)
    val isPlaying: Boolean get() = engine.isFeedPlaying(id, player?.isPlaying == true)
    val playbackState: Int get() = engine.playbackState(id, player?.playbackState ?: Player.STATE_IDLE)
    val currentMediaItem: MediaItem? get() = player?.currentMediaItem
    val currentTracks: Tracks get() = player?.currentTracks ?: Tracks.EMPTY
    val videoSize: VideoSize get() = player?.videoSize ?: VideoSize(0, 0, 0, 0f)
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

    fun play() { engine.play(id) }
    fun pause() { engine.pause(id) }
    fun prepare() { if (player != null) engine.prepare(id) }
    fun seekTo(positionMs: Long) { engine.seekTo(id, positionMs) }
    fun seekToDefaultPosition() { engine.seekToDefaultPosition(id) }
    fun setPlaybackParameters(parameters: PlaybackParameters) {
        engine.setPlaybackParameters(id, parameters)
    }
    fun setPlaybackSpeed(speed: Float) {
        engine.setPlaybackSpeed(id, speed)
    }

    fun addListener(listener: Player.Listener) { player?.addListener(listener) }
    fun removeListener(listener: Player.Listener) { player?.removeListener(listener) }

    fun setVideoSurfaceView(surfaceView: SurfaceView) {
        if (player != null) engine.attachSurfaceView(id, surfaceView)
    }
    fun clearVideoSurfaceView(surfaceView: SurfaceView) {
        // Cleanup must remain possible after the engine closes playback admission.
        engine.detachSurfaceView(id, surfaceView)
    }
    fun setVideoTextureView(textureView: TextureView) {
        if (player != null) engine.attachTextureView(id, textureView)
    }
    fun clearVideoTextureView(textureView: TextureView) {
        // Cleanup must remain possible after the engine closes playback admission.
        engine.detachTextureView(id, textureView)
    }
}
