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
    private val selectedQualities = mutableMapOf<String, Quality>()
    private var audioPlayerId: String? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val lastLiveSeekMs = mutableMapOf<String, Long>()
    private val syncPausedByReference = mutableSetOf<String>()
    private var syncMainId: String? = null
    private val syncRunnable = object : Runnable {
        override fun run() {
            val mainId = syncMainId
            if (mainId != null && players.containsKey(mainId)) {
                syncToMainOnce(mainId)
                mainHandler.postDelayed(this, 1_000L)
            }
        }
    }
    private val desiredPlaying = mutableSetOf<String>()
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
                // Multiview owns several ExoPlayers. They must not compete for Android audio focus.
                // Only the selected main player's volume is audible; audio focus is therefore
                // handled outside individual players.
                player.setAudioAttributes(audioAttributes, false)
                player.volume = 0f
                player.videoScalingMode = C.VIDEO_SCALING_MODE_SCALE_TO_FIT
                player.setHandleAudioBecomingNoisy(true)

                // Keep the reference feed at up to 1080p and constrain secondary feeds to 720p.
                // This reduces simultaneous decoder/network pressure without forcing the
                // main feed down to 720p on a flagship phone.
                val isMain = id == audioPlayerId
                player.trackSelectionParameters = buildQualityParameters(
                    player = player,
                    quality = selectedQualities[id] ?: Quality.AUTO,
                    isMain = isMain,
                    preserveAudioSetting = false
                )

                player.addListener(object : Player.Listener {
                    override fun onPlayerError(error: PlaybackException) {
                        _errors.value = _errors.value + (id to (error.message ?: error.errorCodeName))
                        if (isDecoderFailure(error)) recoverFromDecoderFailure(id, player)
                        else recoverFromSourceFailure(id, player)
                    }

                    override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
                        val selected = selectedQualities[id] ?: Quality.AUTO
                        if (selected != Quality.AUTO) applyQuality(player, selected, id == audioPlayerId)
                    }

                    override fun onPlaybackStateChanged(playbackState: Int) {
                        if (playbackState == Player.STATE_READY) {
                            sourceRecoveryAttempts.remove(id)
                            _errors.value = _errors.value - id
                            if (id in desiredPlaying && !player.isPlaying) player.play()
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

        // Preserve an explicit quality choice across media-source reloads.
        val isMain = stream.id == audioPlayerId
        player.trackSelectionParameters = buildQualityParameters(
            player = player,
            quality = selectedQualities[stream.id] ?: Quality.AUTO,
            isMain = isMain,
            preserveAudioSetting = false
        )

        player.setMediaSource(mediaSourceFactory.createMediaSource(mediaItem))
        player.prepare()
    }

    fun setQuality(id: String, quality: Quality) {
        selectedQualities[id] = quality
        players[id]?.let { applyQuality(it, quality, id == audioPlayerId) }
    }

    fun setQuality(quality: Quality) {
        players.keys.forEach { id ->
            selectedQualities[id] = quality
            players[id]?.let { player -> applyQuality(player, quality, id == audioPlayerId) }
        }
    }

    fun getQuality(id: String): Quality = selectedQualities[id] ?: Quality.AUTO

    /** Returns the actual video resolutions currently exposed by Media3. */
    fun availableVideoResolutions(id: String): List<Pair<Int, Int>> {
        val player = players[id] ?: return emptyList()
        return player.currentTracks.groups
            .filter { it.type == C.TRACK_TYPE_VIDEO }
            .flatMap { group ->
                (0 until group.length).mapNotNull { index ->
                    val format = group.getTrackFormat(index)
                    if (format.width > 0 && format.height > 0) format.width to format.height else null
                }
            }
            .distinct()
            .sortedByDescending { it.second }
    }

    /** Includes tracks Media3 reports as supported when capability checks are relaxed, matching the reference TV quality picker. */
    fun availableVideoResolutionsForQualityMenu(id: String): List<Pair<Int,Int>> {
        val player = players[id] ?: return emptyList()
        return player.currentTracks.groups
            .filter { it.type == C.TRACK_TYPE_VIDEO }
            .flatMap { group ->
                (0 until group.length).mapNotNull { index ->
                    val format = group.getTrackFormat(index)
                    if (format.width > 0 && format.height > 0 && group.isTrackSupported(index, true)) format.width to format.height else null
                }
            }
            .distinct()
            .sortedByDescending { it.second }
    }

    private fun applyQuality(player: ExoPlayer, quality: Quality, isMain: Boolean) {
        player.trackSelectionParameters = buildQualityParameters(
            player = player,
            quality = quality,
            isMain = isMain,
            preserveAudioSetting = true
        )
    }

    /**
     * Explicit quality is pinned with matching minimum and maximum dimensions.
     * Auto remains adaptive within the feed's resource budget.
     */
    private fun buildQualityParameters(
        player: ExoPlayer,
        quality: Quality,
        isMain: Boolean,
        preserveAudioSetting: Boolean
    ): androidx.media3.common.TrackSelectionParameters {
        val builder = player.trackSelectionParameters.buildUpon()
            

        if (!preserveAudioSetting) {
            builder.setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, !isMain)
        }

        when (quality) {
            Quality.UHD -> {
                val target = targetResolution(player, 2160, 3840, 2160)
                builder.setMinVideoSize(target.first, target.second)
                    .setMaxVideoSize(target.first, target.second)
                    .setForceHighestSupportedBitrate(true)
            }
            Quality.FHD -> {
                val target = targetResolution(player, 1080, 1920, 1080)
                builder.setMinVideoSize(target.first, target.second)
                    .setMaxVideoSize(target.first, target.second)
                    .setForceHighestSupportedBitrate(true)
            }
            Quality.HD -> {
                val target = targetResolution(player, 720, 1280, 720)
                builder.setMinVideoSize(target.first, target.second)
                    .setMaxVideoSize(target.first, target.second)
                    .setForceHighestSupportedBitrate(true)
            }
            Quality.SD -> {
                val target = targetResolution(player, 480, 854, 480)
                builder.setMinVideoSize(target.first, target.second)
                    .setMaxVideoSize(target.first, target.second)
                    .setForceHighestSupportedBitrate(true)
            }
            Quality.AUTO -> builder
                .setMinVideoSize(0, 0)
                .setMaxVideoSize(if (isMain) Int.MAX_VALUE else 1280, if (isMain) Int.MAX_VALUE else 720)
                .setForceHighestSupportedBitrate(true)
        }
        return builder.build()
    }

    private fun targetResolution(player: ExoPlayer, desiredHeight: Int, fallbackWidth: Int, fallbackHeight: Int): Pair<Int,Int> {
        val formats = player.currentTracks.groups
            .filter { it.type == C.TRACK_TYPE_VIDEO }
            .flatMap { group -> (0 until group.length).map { group.getTrackFormat(it) } }
            .filter { it.width > 0 && it.height > 0 }
            .distinctBy { it.width to it.height }
        if (formats.isEmpty()) return fallbackWidth to fallbackHeight
        val candidates = formats.filter { it.height >= desiredHeight - 32 && it.height <= desiredHeight + 256 }
        val best = candidates.maxWithOrNull(compareBy<androidx.media3.common.Format> { it.height }.thenBy { it.width })
            ?: formats.minByOrNull { kotlin.math.abs(it.height - desiredHeight) }
        return if (best != null) best.width to best.height else fallbackWidth to fallbackHeight
    }

    private fun isDecoderFailure(error: PlaybackException): Boolean {
        return error.errorCode == PlaybackException.ERROR_CODE_DECODER_INIT_FAILED ||
            error.errorCode == PlaybackException.ERROR_CODE_DECODING_FAILED ||
            error.errorCodeName.contains("DECODER", true) ||
            error.message?.contains("MediaCodecVideoRenderer", true) == true
    }

    private val sourceRecoveryAttempts = mutableMapOf<String, Int>()

    private fun recoverFromSourceFailure(id: String, player: ExoPlayer) {
        val attempts = sourceRecoveryAttempts[id] ?: 0
        if (attempts >= 2) return
        val requested = selectedQualities[id] ?: Quality.AUTO
        val fallback = when (requested) {
            Quality.UHD -> Quality.FHD
            Quality.FHD -> Quality.HD
            Quality.HD -> Quality.SD
            Quality.SD -> Quality.AUTO
            Quality.AUTO -> return
        }
        sourceRecoveryAttempts[id] = attempts + 1
        selectedQualities[id] = fallback
        player.trackSelectionParameters = buildQualityParameters(
            player = player,
            quality = fallback,
            isMain = id == audioPlayerId,
            preserveAudioSetting = true
        )
        player.prepare()
        player.playWhenReady = true
        _errors.value = _errors.value + (id to "Playback source failed; retrying at " + fallback.name)
    }

    private fun recoverFromDecoderFailure(id: String, player: ExoPlayer) {
        val isMain = id == audioPlayerId
        val requested = selectedQualities[id] ?: Quality.AUTO
        val recoveryQuality = when {
            requested == Quality.UHD -> Quality.FHD
            requested == Quality.FHD && !isMain -> Quality.HD
            else -> requested
        }
        if (recoveryQuality != requested) selectedQualities[id] = recoveryQuality
        player.trackSelectionParameters = buildQualityParameters(
            player = player,
            quality = recoveryQuality,
            isMain = isMain,
            preserveAudioSetting = true
        )
        player.prepare()
        player.playWhenReady = true
        _errors.value = _errors.value + (id to "Decoder failed; retrying at " + recoveryQuality.name)
    }

    fun setAudioPlayer(id: String?) {
        audioPlayerId = id
        players.forEach { (pid, player) ->
            val isMain = pid == audioPlayerId
            player.volume = if (isMain) 1f else 0f
            player.trackSelectionParameters = buildQualityParameters(
                player = player,
                quality = selectedQualities[pid] ?: Quality.AUTO,
                isMain = isMain,
                preserveAudioSetting = false
            )
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
    /**
     * Synchronize the selected live feeds against the main feed.
     *
     * Live F1 feeds do not necessarily expose identical media-window positions, so
     * currentLiveOffset is the primary clock. Small drift is corrected with a tiny
     * playback-rate nudge (the same approach used by F1OpenViewer); large drift gets
     * one seek with a cooldown so we never create a rewind/seek loop.
     */
    /**
     * Align all selected feeds to the main feed.
     *
     * Replays/VOD use the main player's media position as the authoritative clock.
     * This is intentionally a seek-only operation: continuously rate-correcting a VOD
     * replay makes the reference stream stutter and can repeatedly flush follower buffers.
     *
     * Live feeds use live-edge offset when Media3 exposes it. Small drift is corrected
     * gently; a large drift is corrected once with a cooldown.
     */
    fun syncToMain(mainId: String) {
        syncMainId = mainId
        mainHandler.removeCallbacks(syncRunnable)
        syncToMainOnce(mainId)
        mainHandler.postDelayed(syncRunnable, 1_000L)
    }

    private fun syncToMainOnce(mainId: String) {
        val main = players[mainId] ?: return
        if (main.playbackState == Player.STATE_IDLE || main.playbackState == Player.STATE_ENDED) return

        val now = android.os.SystemClock.elapsedRealtime()
        val live = main.isCurrentWindowLive

        // The main feed is the reference. If it is buffering while the user still
        // expects it to play, hold followers instead of repeatedly seeking them.
        val mainBuffering = desiredPlaying.contains(mainId) &&
            main.playbackState == Player.STATE_BUFFERING

        players.forEach { (id, player) ->
            if (id == mainId) return@forEach
            if (player.playbackState == Player.STATE_IDLE || player.playbackState == Player.STATE_ENDED) return@forEach

            if (mainBuffering) {
                player.setPlaybackSpeed(1f)
                if (player.isPlaying) {
                    player.pause()
                    syncPausedByReference.add(id)
                }
                return@forEach
            }

            if (syncPausedByReference.remove(id) && desiredPlaying.contains(id) && !player.isPlaying) {
                player.play()
            }

            if (desiredPlaying.contains(id) && !player.isPlaying && player.playbackState == Player.STATE_READY) {
                player.play()
            }

            val correctionMs: Long? = if (live) {
                val mainOffset = main.currentLiveOffset.takeIf { it != C.TIME_UNSET && it >= 0L }
                val followerOffset = player.currentLiveOffset.takeIf { it != C.TIME_UNSET && it >= 0L }
                if (mainOffset != null && followerOffset != null) followerOffset - mainOffset else null
            } else {
                // For VOD/replay, the main player's media position is the authoritative
                // clock. This is deliberately NOT based on each manifest's windowStartTimeMs:
                // different feed manifests can expose different epoch/window metadata.
                main.currentPosition - player.currentPosition
            }

            val correction = correctionMs ?: return@forEach
            val absCorrection = kotlin.math.abs(correction)

            if (absCorrection >= 1_500L &&
                now - (lastLiveSeekMs[id] ?: 0L) >= 5_000L
            ) {
                player.setPlaybackSpeed(1f)
                val target = if (live) {
                    (player.currentPosition + correction).coerceAtLeast(0L)
                } else {
                    main.currentPosition.coerceAtLeast(0L)
                }
                val duration = player.duration
                player.seekTo(if (duration > 0L) target.coerceAtMost(duration) else target)
                lastLiveSeekMs[id] = now
            } else if (absCorrection >= 250L) {
                // Correct only followers. The reference feed is never rate-adjusted.
                val rate = if (correction > 0L) 1.05f else 0.95f
                player.setPlaybackSpeed(rate)
            } else {
                player.setPlaybackSpeed(1f)
            }
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
        desiredPlaying.add(id)
        get(id).play()
    }

    fun pause(id: String) {
        desiredPlaying.remove(id)
        get(id).pause()
    }

    fun playAll() {
        if (audioPlayerId == null) setAudioPlayer(players.keys.firstOrNull())
        players.forEach { (id, player) ->
            desiredPlaying.add(id)
            player.playWhenReady = true
            if (player.playbackState != Player.STATE_IDLE && player.playbackState != Player.STATE_ENDED) {
                player.play()
            }
        }
    }

    fun pauseAll() {
        players.keys.forEach { desiredPlaying.remove(it) }
        players.values.forEach { it.pause() }
    }

    fun retain(ids: Set<String>) {
        players.keys.filterNot(ids::contains).toList().forEach(::clear)
        if (audioPlayerId !in players.keys) setAudioPlayer(null)
    }

    fun clear(id: String) {
        if (id == syncMainId) {
            syncMainId = null
            mainHandler.removeCallbacks(syncRunnable)
        }
        syncPausedByReference.remove(id)
        desiredPlaying.remove(id)
        players.remove(id)?.release()
        selectedQualities.remove(id)
        if (audioPlayerId == id) setAudioPlayer(null)
    }

    fun release() {
        mainHandler.removeCallbacksAndMessages(null)
        syncMainId = null
        syncPausedByReference.clear()
        players.values.forEach { it.release() }
        players.clear()
        desiredPlaying.clear()
        audioPlayerId = null
        selectedQualities.clear()
    }

    fun all(): Collection<ExoPlayer> = players.values
}

@OptIn(UnstableApi::class)
private object ProductionLoadControl {
    fun create(): androidx.media3.exoplayer.LoadControl =
        androidx.media3.exoplayer.DefaultLoadControl.Builder()
            .setBufferDurationsMs(3_000, 10_000, 1_000, 2_000)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()
}
