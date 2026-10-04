package app.f1multiview.media

import android.content.Context
import android.content.res.Configuration
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.drm.DefaultDrmSessionManager
import androidx.media3.exoplayer.drm.HttpMediaDrmCallback
import android.os.Handler
import android.os.Looper
import app.f1multiview.core.playback.Quality
import app.f1multiview.model.StreamSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

@OptIn(UnstableApi::class)
class PlayerPool(context: Context) {
    private val appContext = context.applicationContext
    private val players = linkedMapOf<String, ExoPlayer>()
    private var audioPlayerId: String? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val _errors = MutableStateFlow<Map<String, String>>(emptyMap())
    val errors: StateFlow<Map<String, String>> = _errors.asStateFlow()

    private val audioAttributes = AudioAttributes.Builder()
        .setUsage(C.USAGE_MEDIA)
        .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
        .build()

    fun get(id: String): ExoPlayer = players.getOrPut(id) {
        ExoPlayer.Builder(appContext, DefaultRenderersFactory(appContext).setEnableDecoderFallback(true))
            .setLoadControl(ProductionLoadControl.create())
            .build()
            .also { player ->
                player.setAudioAttributes(audioAttributes, true)
                player.volume = 0f
                player.videoScalingMode = C.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING
                player.setHandleAudioBecomingNoisy(true)

                // AUTO is intentionally conservative on phones. The user can raise
                // quality from the controls inside an opened player.
                val isTv = (appContext.resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK) ==
                    Configuration.UI_MODE_TYPE_TELEVISION
                val initialBuilder = player.trackSelectionParameters.buildUpon()
                    .setPreferredVideoMimeTypes(MimeTypes.VIDEO_H264, MimeTypes.VIDEO_H265)
                if (!isTv) initialBuilder.setMaxVideoSize(1920, 1080)
                player.trackSelectionParameters = initialBuilder.build()

                player.addListener(object : Player.Listener {
                    override fun onPlayerError(error: PlaybackException) {
                        _errors.value = _errors.value + (id to (error.message ?: error.errorCodeName))
                        if (isDecoderFailure(error)) recoverFromDecoderFailure(id, player)
                    }

                    override fun onPlaybackStateChanged(playbackState: Int) {
                        if (playbackState == Player.STATE_READY || playbackState == Player.STATE_BUFFERING) {
                            _errors.value = _errors.value - id
                        }
                    }
                })
            }
    }

    fun load(stream: StreamSource) {
        val url = stream.url ?: return
        val player = get(stream.id)

        if (player.currentMediaItem?.localConfiguration?.uri?.toString() == url) return

        val mediaItem = MediaItem.Builder()
            .setUri(url)
            .apply {
                if (url.contains(".mpd", true)) setMimeType(MimeTypes.APPLICATION_MPD)
                if (url.contains(".m3u8", true)) setMimeType(MimeTypes.APPLICATION_M3U8)

                stream.drmLicenseUrl?.let { license ->
                    setDrmConfiguration(
                        MediaItem.DrmConfiguration.Builder(C.WIDEVINE_UUID)
                            .setLicenseUri(license)
                            .setLicenseRequestHeaders(
                                stream.drmRequestHeaders.ifEmpty { stream.requestHeaders }
                            )
                                .build()
                    )
                }
            }
            .build()

        val dataSource = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setUserAgent(stream.requestHeaders["User-Agent"] ?: "Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36 Chrome/140.0 Mobile Safari/537.36")
            .setDefaultRequestProperties(stream.requestHeaders)
        val drmHeaders = stream.drmRequestHeaders.ifEmpty { stream.requestHeaders }
        val drmDataSource = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setUserAgent(stream.requestHeaders["User-Agent"] ?: "Mozilla/5.0")
            .setDefaultRequestProperties(drmHeaders)
        val licenseUrl = stream.drmLicenseUrl
        val mediaSourceFactory = if (!licenseUrl.isNullOrBlank()) {
            val callback = HttpMediaDrmCallback(licenseUrl, true, drmDataSource)
            drmHeaders.forEach { (name, value) -> callback.setKeyRequestProperty(name, value) }
            val drmManager = DefaultDrmSessionManager.Builder()
                .setMultiSession(false)
                .build(callback)
            DefaultMediaSourceFactory(dataSource).setDrmSessionManagerProvider { drmManager }
        } else {
            DefaultMediaSourceFactory(dataSource)
        }

        player.setMediaSource(mediaSourceFactory.createMediaSource(mediaItem))
        player.prepare()
    }

    fun setQuality(id: String, quality: Quality) {
        players[id]?.let { applyQuality(it, quality) }
    }

    fun setQuality(quality: Quality) {
        players.values.forEach { applyQuality(it, quality) }
    }

    private fun applyQuality(player: ExoPlayer, quality: Quality) {
        val isTv = (appContext.resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK) ==
            Configuration.UI_MODE_TYPE_TELEVISION

        val builder = player.trackSelectionParameters.buildUpon()

        when (quality) {
            Quality.UHD -> builder
                .setMaxVideoSize(3840, 2160)
                .setPreferredVideoMimeTypes(MimeTypes.VIDEO_H265, MimeTypes.VIDEO_H264)

            Quality.FHD -> builder
                .setMaxVideoSize(1920, 1080)
                .setPreferredVideoMimeTypes(MimeTypes.VIDEO_H264, MimeTypes.VIDEO_H265)

            Quality.HD -> builder
                .setMaxVideoSize(1280, 720)
                .setPreferredVideoMimeTypes(MimeTypes.VIDEO_H264, MimeTypes.VIDEO_H265)

            Quality.SD -> builder
                .setMaxVideoSize(854, 480)
                .setPreferredVideoMimeTypes(MimeTypes.VIDEO_H264, MimeTypes.VIDEO_H265)

            Quality.AUTO -> {
                if (isTv) {
                    builder
                        .clearVideoSizeConstraints()
                        .setPreferredVideoMimeTypes(MimeTypes.VIDEO_H264, MimeTypes.VIDEO_H265)
                } else {
                    builder
                        .setMaxVideoSize(1920, 1080)
                        .setPreferredVideoMimeTypes(MimeTypes.VIDEO_H264, MimeTypes.VIDEO_H265)
                }
            }
        }

        player.trackSelectionParameters = builder.build()
    }

    private fun isDecoderFailure(error: PlaybackException): Boolean {
        return error.errorCode == PlaybackException.ERROR_CODE_DECODER_INIT_FAILED ||
            error.errorCode == PlaybackException.ERROR_CODE_DECODING_FAILED ||
            error.errorCodeName.contains("DECODER", true) ||
            error.message?.contains("MediaCodecVideoRenderer", true) == true
    }

    private fun recoverFromDecoderFailure(id: String, player: ExoPlayer) {
        player.trackSelectionParameters = player.trackSelectionParameters
            .buildUpon()
            .setMaxVideoSize(1920, 1080)
            .setPreferredVideoMimeTypes(MimeTypes.VIDEO_H264, MimeTypes.VIDEO_H265)
            .build()

        player.prepare()
        player.playWhenReady = true
        _errors.value = _errors.value + (id to "Decoder failed; retrying at 1080p")
    }

    fun setAudioPlayer(id: String?) {
        audioPlayerId = id?.takeIf { players.containsKey(it) }
        players.forEach { (pid, player) ->
            player.volume = if (pid == audioPlayerId) 1f else 0f
        }
    }

    fun setMuted(id: String, muted: Boolean) {
        players[id]?.volume = if (muted) 0f else 1f
    }

    fun isMuted(id: String): Boolean = players[id]?.volume?.let { it <= 0.001f } ?: true

    /**
     * One-shot synchronization only. We deliberately do not run a continuous
     * seek loop because F1 TV feed types have different live latencies.
     * When both manifests expose Unix-epoch window start times, align the
     * secondary feeds to the main feed's presentation timestamp.
     */
    fun syncToMain(mainId: String) {
        val main = players[mainId] ?: return
        val mainEpoch = absolutePresentationTime(main) ?: return
        players.forEach { (id, player) ->
            if (id == mainId || player.currentTimeline.isEmpty) return@forEach
            val target = mainEpoch - windowStart(player)
            val duration = player.duration
            val clamped = if (duration > 0L) target.coerceIn(0L, duration) else target.coerceAtLeast(0L)
            player.seekTo(clamped)
        }
    }

    private fun windowStart(player: ExoPlayer): Long {
        if (player.currentTimeline.isEmpty) return C.TIME_UNSET
        val window = Timeline.Window()
        player.currentTimeline.getWindow(player.currentMediaItemIndex, window)
        return window.windowStartTimeMs
    }

    private fun absolutePresentationTime(player: ExoPlayer): Long? {
        val start = windowStart(player)
        if (start == C.TIME_UNSET) return null
        return start + player.currentPosition
    }

    fun play(id: String) {
        get(id).play()
    }

    fun pause(id: String) {
        get(id).pause()
    }

    fun playAll() {
        if (audioPlayerId == null) setAudioPlayer(players.keys.firstOrNull())
        val ids = players.keys.toList()
        ids.forEachIndexed { index, id ->
            mainHandler.postDelayed({ players[id]?.play() }, index * 600L)
        }
    }

    fun pauseAll() {
        players.values.forEach { it.pause() }
    }

    fun retain(ids: Set<String>) {
        players.keys.filterNot(ids::contains).toList().forEach(::clear)
        if (audioPlayerId !in players.keys) setAudioPlayer(null)
    }

    fun clear(id: String) {
        players.remove(id)?.release()
        if (audioPlayerId == id) setAudioPlayer(null)
    }

    fun release() {
        mainHandler.removeCallbacksAndMessages(null)
        players.values.forEach { it.release() }
        players.clear()
        audioPlayerId = null
    }

    fun all(): Collection<ExoPlayer> = players.values
}

@OptIn(UnstableApi::class)
private object ProductionLoadControl {
    fun create(): androidx.media3.exoplayer.LoadControl =
        androidx.media3.exoplayer.DefaultLoadControl.Builder()
            .setBufferDurationsMs(1_500, 8_000, 500, 1_000)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()
}
