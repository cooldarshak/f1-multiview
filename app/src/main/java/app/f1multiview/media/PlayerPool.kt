package app.f1multiview.media

import android.content.Context
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import app.f1multiview.model.StreamSource
import app.f1multiview.core.playback.Quality
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

@OptIn(UnstableApi::class)
class PlayerPool(context: Context) {
    private val appContext = context.applicationContext
    private val players = linkedMapOf<String, ExoPlayer>()
    private var audioPlayerId: String? = null
    private val _errors = MutableStateFlow<Map<String, String>>(emptyMap())
    val errors: StateFlow<Map<String, String>> = _errors.asStateFlow()
    private val audioAttributes = AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build()
    fun get(id: String): ExoPlayer = players.getOrPut(id) {
        ExoPlayer.Builder(appContext).setLoadControl(ProductionLoadControl.create()).build().also { player ->
            player.setAudioAttributes(audioAttributes, true)
            player.volume = 0f
            player.videoScalingMode = C.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING
            player.setHandleAudioBecomingNoisy(true)
            player.addListener(object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) { _errors.value = _errors.value + (id to (error.message ?: error.errorCodeName)) }
                override fun onPlaybackStateChanged(playbackState: Int) { if (playbackState == Player.STATE_READY || playbackState == Player.STATE_BUFFERING) _errors.value = _errors.value - id }
            })
        }
    }
    fun load(stream: StreamSource) {
        val url = stream.url ?: return
        val player = get(stream.id)
        if (player.currentMediaItem?.localConfiguration?.uri?.toString() == url) return
        val mediaItem = MediaItem.Builder().setUri(url).apply {
            stream.drmLicenseUrl?.let { license ->
                setDrmConfiguration(MediaItem.DrmConfiguration.Builder(C.WIDEVINE_UUID)
                    .setLicenseUri(license).setLicenseRequestHeaders(stream.drmRequestHeaders.ifEmpty { stream.requestHeaders }).setMultiSession(true).build())
            }
        }.build()
        val dataSource = DefaultHttpDataSource.Factory().setAllowCrossProtocolRedirects(true).setDefaultRequestProperties(stream.requestHeaders)
        player.setMediaSource(DefaultMediaSourceFactory(dataSource).createMediaSource(mediaItem))
        player.prepare()
    }
    fun setQuality(quality: Quality) {
        players.values.forEach { player ->
            val builder = player.trackSelectionParameters.buildUpon()
            when (quality) {
                Quality.UHD -> builder.setMaxVideoSize(3840, 2160)
                Quality.FHD -> builder.setMaxVideoSize(1920, 1080)
                Quality.HD -> builder.setMaxVideoSize(1280, 720)
                Quality.SD -> builder.setMaxVideoSize(854, 480)
                Quality.AUTO -> builder.clearVideoSizeConstraints()
            }
            player.trackSelectionParameters = builder.build()
        }
    }
    fun setAudioPlayer(id: String?) { audioPlayerId = id?.takeIf { players.containsKey(it) }; players.forEach { (pid,p) -> p.volume = if (pid == audioPlayerId) 1f else 0f } }
    fun play(id: String) { setAudioPlayer(id); get(id).play() }
    fun pause(id: String) { get(id).pause() }
    fun playAll() { if (audioPlayerId == null) setAudioPlayer(players.keys.firstOrNull()); players.values.forEach { it.play() } }
    fun pauseAll() { players.values.forEach { it.pause() } }
    fun retain(ids: Set<String>) { players.keys.filterNot(ids::contains).toList().forEach(::clear); if (audioPlayerId !in players.keys) setAudioPlayer(null) }
    fun clear(id: String) { players.remove(id)?.release(); if (audioPlayerId == id) setAudioPlayer(null) }
    fun release() { players.values.forEach { it.release() }; players.clear(); audioPlayerId = null }
    fun all(): Collection<ExoPlayer> = players.values
}
@OptIn(UnstableApi::class)
private object ProductionLoadControl {
    fun create(): androidx.media3.exoplayer.LoadControl =
        androidx.media3.exoplayer.DefaultLoadControl.Builder().setBufferDurationsMs(1_500,8_000,500,1_000).setPrioritizeTimeOverSizeThresholds(true).build()
}
