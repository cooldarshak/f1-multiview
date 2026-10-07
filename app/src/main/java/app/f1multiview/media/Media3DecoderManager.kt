package app.f1multiview.media

import android.content.Context
import android.content.res.Configuration
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.drm.DefaultDrmSessionManager
import androidx.media3.exoplayer.drm.FrameworkMediaDrm
import androidx.media3.exoplayer.drm.DummyExoMediaDrm
import androidx.media3.exoplayer.drm.UnsupportedDrmException
import androidx.media3.exoplayer.drm.HttpMediaDrmCallback
import android.os.Handler
import android.os.Looper
import app.f1multiview.core.playback.Quality
import app.f1multiview.model.StreamSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

@OptIn(UnstableApi::class)
data class VideoDiagnostics(
    val width: Int,
    val height: Int,
    val bitrate: Int,
    val codec: String?,
    val hdr: Boolean,
    val colorTransfer: Int,
    val colorSpace: Int,
    val bitDepth: Int
)

class Media3DecoderManager(context: Context) {
    // Four simultaneous video feeds are the validated safe ceiling on the current
    // target device. Keep this guard here as well as in the ViewModel so an accidental
    // caller cannot instantiate a fifth decoder and crash the process.
    private val maxVideoFeeds = 4
    // Phase 2 resource guard: secondary feeds are deliberately constrained before decoder pressure rises.
    private val appContext = context.applicationContext
    private val players = linkedMapOf<String, ExoPlayer>()
    private val resourceManager = DecoderResourceManager(maxVideoDecoders = maxVideoFeeds)
    private val qualityManager = QualityManager()
    private val selectedQualities = mutableMapOf<String, Quality>()
    private val streamKinds = mutableMapOf<String, app.f1multiview.model.StreamKind>()
    private val streams = mutableMapOf<String, StreamSource>()
    private val l3SecondaryFallback = mutableSetOf<String>()
    private val decoderRecoveryAttempts = mutableMapOf<String, Int>()
    private var audioPlayerId: String? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val startupRequestedAtMs = mutableMapOf<String, Long>()
    private val startupFirstFrameAtMs = mutableMapOf<String, Long>()
    private val startupPlayingAtMs = mutableMapOf<String, Long>()
    private val firstFrameRendered = mutableSetOf<String>()
    private var playAllRequested = false
    private val syncController = MultiviewSyncController(
        players = { players },
        desiredPlaying = { desiredPlaying }
    )
    private val desiredPlaying = mutableSetOf<String>()
    private val _errors = MutableStateFlow<Map<String, String>>(emptyMap())
    val errors: StateFlow<Map<String, String>> = _errors.asStateFlow()

    private val audioAttributes = AudioAttributes.Builder()
        .setUsage(C.USAGE_MEDIA)
        .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
        .build()

    fun hasDecoder(id: String): Boolean = players.containsKey(id)
    fun activeDecoderIds(): Set<String> = players.keys.toSet()

    fun get(id: String): ExoPlayer = players[id] ?: error("Decoder not allocated for feed " + id)

    private fun createPlayer(id: String): ExoPlayer = players.getOrPut(id) {
        ExoPlayer.Builder(appContext, F1TvRenderersFactory(appContext))
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
                    // Keep audio selected for every multiview player. Audible
                    // routing is controlled by volume in setAudioPlayer/setMuted.
                    preserveAudioSetting = true
                )

                player.addListener(object : Player.Listener {
                    override fun onPlayerError(error: PlaybackException) {
                        _errors.value = _errors.value + (id to (error.message ?: error.errorCodeName))
                        if (isDecoderFailure(error)) recoverFromDecoderFailure(id, player, error)
                        else recoverFromSourceFailure(id, player)
                    }

                    override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
                        val selected = selectedQualities[id] ?: Quality.AUTO
                        if (selected != Quality.AUTO) {
                            applyQuality(player, selected, id == audioPlayerId)
                        } else {
                            applyAutoResourceBudget(id, player)
                        }
                    }

                    override fun onPlaybackStateChanged(playbackState: Int) {
                        if (playbackState == Player.STATE_READY) {
                            sourceRecoveryAttempts.remove(id)
                            decoderRecoveryAttempts.remove(id)
                            _errors.value = _errors.value - id
                            startupRequestedAtMs[id]?.let { startedAt ->
                                val elapsed = android.os.SystemClock.elapsedRealtime() - startedAt
                                Log.i("Media3DecoderManager", "STARTUP_READY id=$id elapsedMs=$elapsed players=${players.size}")
                            }
                            if (playAllRequested) desiredPlaying.add(id)
                            if (id in desiredPlaying) {
                                // READY means Media3 can play immediately, but it does not mean
                                // the first video frame has reached the surface yet.
                                if (playAllRequested) {
                                    scheduleStartupPlayback(id)
                                } else if (!player.isPlaying) {
                                    // Preserve direct/user-initiated play(id) behavior outside
                                    // the multiview startup scheduler.
                                    player.playWhenReady = true
                                    player.play()
                                }
                            }
                        }
                    }

                    override fun onRenderedFirstFrame() {
                            val firstFrameAt = android.os.SystemClock.elapsedRealtime()
                            firstFrameRendered.add(id)
                            startupFirstFrameAtMs[id] = firstFrameAt
                            startupRequestedAtMs[id]?.let { requestedAt ->
                                Log.i("Media3DecoderManager", "STARTUP_FIRST_FRAME id=" + id + " elapsedMs=" + (firstFrameAt-requestedAt) + " players=" + players.size)
                            }
                            syncController.markFirstFrame(id)
                        }

                        override fun onIsPlayingChanged(isPlaying: Boolean) {
                            if (isPlaying && id !in startupPlayingAtMs) {
                                val playingAt = android.os.SystemClock.elapsedRealtime()
                                startupPlayingAtMs[id] = playingAt
                                startupRequestedAtMs.remove(id)?.let { requestedAt ->
                                    Log.i("Media3DecoderManager", "STARTUP_PLAYING id=" + id + " elapsedMs=" + (playingAt-requestedAt) + " players=" + players.size)
                                }
                            }
                        }
                    }
                )
            }
    }

    fun load(stream: StreamSource, forceReload: Boolean = false): Boolean {
        val url = stream.url ?: return false
        streamKinds[stream.id] = stream.kind
        streams[stream.id] = stream
        if (playAllRequested) desiredPlaying.add(stream.id)
        val allocation = resourceManager.request(
            stream = stream,
            isReference = stream.id == audioPlayerId,
            quality = selectedQualities[stream.id] ?: Quality.AUTO
        )
        val lease = allocation.lease
        if (lease == null) {
            _errors.value = _errors.value + (stream.id to "No decoder resource available; feed remains logical and decoderless")
            Log.i("Media3DecoderManager", "RESOURCE_WAIT feed=" + stream.id + " active=" + resourceManager.activeLeases().size + "/" + resourceManager.capacity())
            return false
        }
        val player = createPlayer(stream.id)

        if (!forceReload && player.currentMediaItem?.localConfiguration?.uri?.toString() == url) return true

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

        val baseDataSource = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setUserAgent(stream.requestHeaders["User-Agent"] ?: "Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36 Chrome/140.0 Mobile Safari/537.36")
            .setDefaultRequestProperties(stream.requestHeaders)
        val dataSource = if (url.contains(".m3u8", true)) {
            F1CmafHlsDrmFixingDataSource.Factory(baseDataSource)
        } else {
            baseDataSource
        }
        val drmHeaders = buildMap {
            putAll(stream.drmRequestHeaders.ifEmpty { stream.requestHeaders })
            stream.playToken?.takeIf { it.isNotBlank() }?.let { put("Cookie", "playToken=" + it) }
        }
        val drmDataSource = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setUserAgent(stream.requestHeaders["User-Agent"] ?: "Mozilla/5.0")
            .setDefaultRequestProperties(drmHeaders)
        val licenseUrl = stream.drmLicenseUrl
        val mediaSourceFactory = if (!licenseUrl.isNullOrBlank()) {
            val callback = HttpMediaDrmCallback(licenseUrl, true, drmDataSource)
            drmHeaders.forEach { (name, value) -> callback.setKeyRequestProperty(name, value) }
            val drmBuilder = DefaultDrmSessionManager.Builder()
                .setMultiSession(false)

            // Targeted fallback for secondary feeds when the device's secure decoder
            // pool is exhausted. Normal playback remains at the native Widevine level.
            if (stream.id in l3SecondaryFallback && android.os.Build.VERSION.SDK_INT >= 28) {
                drmBuilder.setUuidAndExoMediaDrmProvider(C.WIDEVINE_UUID) { uuid ->
                    try {
                        FrameworkMediaDrm.newInstance(uuid).also { mediaDrm ->
                            val current = runCatching { mediaDrm.getPropertyString("securityLevel") }.getOrNull()
                            if (current != "L3") {
                                runCatching { mediaDrm.setPropertyString("securityLevel", "L3") }
                            }
                            android.util.Log.i(
                                "Media3DecoderManager",
                                "Secondary " + stream.id + ": Widevine security level=" +
                                    runCatching { mediaDrm.getPropertyString("securityLevel") }.getOrDefault("unknown")
                            )
                        }
                    } catch (_: UnsupportedDrmException) {
                        DummyExoMediaDrm()
                    }
                }
            }
            val drmManager = drmBuilder.build(callback)
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
            // Do not disable secondary audio at load time. All feeds must retain
            // an initialized audio track so switching the audible feed is instant.
            preserveAudioSetting = true
        )

        startupRequestedAtMs[stream.id] = android.os.SystemClock.elapsedRealtime()
        startupFirstFrameAtMs.remove(stream.id)
        startupPlayingAtMs.remove(stream.id)
        firstFrameRendered.remove(stream.id)
        Log.i("Media3DecoderManager", "STARTUP_LOAD id=" + stream.id + " players=" + players.size + " main=" + (stream.id == audioPlayerId))
        player.setMediaSource(mediaSourceFactory.createMediaSource(mediaItem))
        player.prepare()
        return true
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

    fun currentVideoDiagnostics(id: String): VideoDiagnostics? {
        val player = players[id] ?: return null
        val selected = player.currentTracks.groups
            .filter { it.type == C.TRACK_TYPE_VIDEO }
            .flatMap { group ->
                (0 until group.length).mapNotNull { index ->
                    if (group.isTrackSelected(index)) group.getTrackFormat(index) else null
                }
            }
            .maxWithOrNull(compareBy<androidx.media3.common.Format> { it.height }.thenBy { it.bitrate })
            ?: return null
        val color = selected.colorInfo
        return VideoDiagnostics(
            width = selected.width,
            height = selected.height,
            bitrate = selected.bitrate,
            codec = selected.codecs,
            hdr = androidx.media3.common.ColorInfo.isTransferHdr(color),
            colorTransfer = color?.colorTransfer ?: -1,
            colorSpace = color?.colorSpace ?: -1,
            bitDepth = color?.lumaBitdepth ?: -1
        )
    }

    fun qualityAvailable(id: String, quality: Quality): Boolean {
        val resolutions = availableVideoResolutionsForQualityMenu(id)
        if (resolutions.isEmpty()) return false
        return when (quality) {
            Quality.AUTO -> true
            Quality.UHD -> resolutions.any { it.second >= 2160 }
            Quality.FHD -> resolutions.any { it.second >= 1080 }
            Quality.HD -> resolutions.any { it.second >= 720 }
            Quality.SD -> resolutions.any { it.second >= 480 }
        }
    }

    /** Includes tracks Media3 reports as supported when capability checks are relaxed, matching the reference TV quality picker. */
    fun availableVideoResolutionsForQualityMenu(id: String): List<Pair<Int,Int>> {
        val player = players[id] ?: return emptyList()
        return player.currentTracks.groups
            .filter { it.type == C.TRACK_TYPE_VIDEO }
            .flatMap { group ->
                (0 until group.length).mapNotNull { index ->
                    val format = group.getTrackFormat(index)
                    if (format.width > 0 && format.height > 0 && (group.getTrackSupport(index) == C.FORMAT_HANDLED || group.getTrackSupport(index) == C.FORMAT_EXCEEDS_CAPABILITIES)) format.width to format.height else null
                }
            }
            .distinct()
            .sortedByDescending { it.second }
    }

    private fun applyAutoResourceBudget(id: String, player: ExoPlayer) {
        if (id == audioPlayerId) return

        val budget = resourceManager.autoVideoBudget(isReference = false, activeDecoderCount = resourceManager.activeLeases().size)
        val maxHeight = budget.maxHeight
        val maxWidth = budget.maxWidth

        val candidates = player.currentTracks.groups
            .filter { it.type == C.TRACK_TYPE_VIDEO }
            .flatMap { group ->
                (0 until group.length).map { index ->
                    Triple(group, index, group.getTrackFormat(index))
                }
            }
            .filter { (_, _, format) -> format.width > 0 && format.height > 0 }
            .filter { (_, _, format) -> format.height <= maxHeight && format.width <= maxWidth }
            .filter { (group, index, _) ->
                val support = group.getTrackSupport(index)
                support == C.FORMAT_HANDLED || support == C.FORMAT_EXCEEDS_CAPABILITIES
            }

        if (candidates.isEmpty()) return

        val selected = candidates.sortedWith(
            compareByDescending<Triple<androidx.media3.common.Tracks.Group, Int, androidx.media3.common.Format>> { (_, _, format) ->
                format.sampleMimeType.equals(MimeTypes.VIDEO_H264, true)
            }.thenByDescending { (_, _, format) -> format.height }
                .thenByDescending { (_, _, format) -> format.width }
                .thenByDescending { (_, _, format) -> format.bitrate }
        ).firstOrNull() ?: return

        val group = selected.first
        val index = selected.second
        val alreadySelected = player.currentTracks.groups
            .filter { it.type == C.TRACK_TYPE_VIDEO }
            .any { g -> (0 until g.length).any { i -> g === group && i == index && g.isTrackSelected(i) } }

        if (!alreadySelected) {
            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                .setMaxVideoSize(maxWidth, maxHeight)
                .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, index))
                .build()
        }
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
                    .setForceHighestSupportedBitrate(false)
            }
            Quality.FHD -> {
                val target = targetResolution(player, 1080, 1920, 1080)
                builder.setMinVideoSize(target.first, target.second)
                    .setMaxVideoSize(target.first, target.second)
                    .setForceHighestSupportedBitrate(false)
            }
            Quality.HD -> {
                val target = targetResolution(player, 720, 1280, 720)
                builder.setMinVideoSize(target.first, target.second)
                    .setMaxVideoSize(target.first, target.second)
                    .setForceHighestSupportedBitrate(false)
            }
            Quality.SD -> {
                val target = targetResolution(player, 480, 854, 480)
                builder.setMinVideoSize(target.first, target.second)
                    .setMaxVideoSize(target.first, target.second)
                    .setForceHighestSupportedBitrate(false)
            }
            Quality.AUTO -> {
                val secondaryMaxHeight = if (!isMain && players.size >= 4) 360 else 480
                val secondaryMaxWidth = if (!isMain && players.size >= 4) 640 else 854
                builder.setMinVideoSize(0, 0)
                    .setMaxVideoSize(
                        if (isMain) Int.MAX_VALUE else secondaryMaxWidth,
                        if (isMain) Int.MAX_VALUE else secondaryMaxHeight
                    )
                    .setForceHighestSupportedBitrate(false)
            }
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
        if (attempts >= 3) return
        val requested = selectedQualities[id] ?: Quality.AUTO
        val fallback = when (requested) {
            Quality.UHD -> Quality.FHD
            Quality.FHD -> Quality.HD
            Quality.HD -> Quality.SD
            Quality.SD -> Quality.AUTO
            Quality.AUTO -> Quality.AUTO
        }
        sourceRecoveryAttempts[id] = attempts + 1
        if (fallback != requested) selectedQualities[id] = fallback
        player.trackSelectionParameters = buildQualityParameters(
            player = player,
            quality = fallback,
            isMain = id == audioPlayerId,
            preserveAudioSetting = true
        )
        // Retry transient CDN/manifest/network failures before surfacing an error.
        mainHandler.postDelayed({
            if (players[id] === player) {
                player.prepare()
                player.playWhenReady = true
            }
        }, 700L * (attempts + 1))
        _errors.value = _errors.value + (id to if (fallback == requested) {
            "Playback interrupted; retrying (" + (attempts + 1) + "/3)"
        } else {
            "Playback source failed; retrying at " + fallback.name
        })
    }

    private fun isLikelySecureDecoderCapacityFailure(id: String, error: PlaybackException): Boolean {
        val stream = streams[id]
        val secureCandidate = id != audioPlayerId &&
            players.size >= 4 &&
            stream?.drmLicenseUrl?.isNullOrBlank() == false
        if (!secureCandidate) return false

        val evidence = buildList {
            add(error.message.orEmpty())
            add(error.errorCodeName)
            var cause: Throwable? = error.cause
            repeat(8) {
                if (cause == null) return@repeat
                add(cause?.javaClass?.name.orEmpty())
                add(cause?.message.orEmpty())
                cause = cause?.cause
            }
        }.joinToString(" | ").lowercase()

        val resourceEvidence = listOf(
            "resourcebusyexception",
            "insufficient resource",
            "insufficientresources",
            "resource busy",
            "too many",
            "resource exhausted",
            "resource limit",
            "secure decoder",
            "securedecoder",
            "omx.error.insufficientresources",
            "error_insufficient_resources"
        ).any(evidence::contains)

        Log.w(
            "Media3DecoderManager",
            "Decoder failure id=$id players=${players.size} drm=true capacityEvidence=$resourceEvidence " +
                "code=${error.errorCodeName} message=${error.message}"
        )
        return resourceEvidence || (
            players.size >= 4 &&
            error.errorCode == PlaybackException.ERROR_CODE_DECODER_INIT_FAILED &&
            evidence.contains("mediacodec")
        )
    }

    private fun recoverFromDecoderFailure(id: String, player: ExoPlayer, error: PlaybackException) {
        val isMain = id == audioPlayerId
        val requested = selectedQualities[id] ?: Quality.AUTO
        val attempts = decoderRecoveryAttempts[id] ?: 0

        // Only attempt the L3 fallback for a secondary DRM stream when the failure
        // has evidence consistent with secure-decoder/resource exhaustion. Do not
        // downgrade every decoder error: a bad codec/manifest must follow the normal
        // recovery ladder instead.
        val capacityFailure = isLikelySecureDecoderCapacityFailure(id, error)
        if (!isMain && capacityFailure && id !in l3SecondaryFallback && android.os.Build.VERSION.SDK_INT >= 28) {
            l3SecondaryFallback.add(id)
            decoderRecoveryAttempts[id] = attempts + 1
            player.stop()
            player.clearMediaItems()
            _errors.value = _errors.value + (
                id to "Secondary secure decoder capacity suspected; retrying with Widevine L3"
            )
            mainHandler.postDelayed({
                if (players[id] === player) {
                    streams[id]?.let { load(it, forceReload = true) }
                    player.playWhenReady = true
                }
            }, 250L)
            return
        }
        val recoveryQuality = when {
            requested == Quality.UHD -> Quality.FHD
            requested == Quality.FHD && !isMain -> Quality.HD
            requested == Quality.HD && !isMain -> Quality.SD
            requested == Quality.AUTO && !isMain -> Quality.SD
            requested == Quality.AUTO && isMain -> Quality.FHD
            else -> requested
        }

        decoderRecoveryAttempts[id] = attempts + 1
        if (recoveryQuality != requested) selectedQualities[id] = recoveryQuality

        player.stop()
        player.trackSelectionParameters = buildQualityParameters(
            player = player,
            quality = recoveryQuality,
            isMain = isMain,
            preserveAudioSetting = true
        )
        mainHandler.postDelayed({
            if (players[id] === player) {
                player.prepare()
                player.playWhenReady = true
            }
        }, 250L)

        val message = if (!isMain && requested == Quality.AUTO) {
            "Decoder capacity reached; retrying secondary at " + recoveryQuality.name
        } else {
            "Decoder failed; retrying at " + recoveryQuality.name
        }
        _errors.value = _errors.value + (id to message)
    }

    fun setAudioPlayer(id: String?) {
        audioPlayerId = id
        resourceManager.updateReference(id)
        players.forEach { (pid, player) ->
            val isMain = pid == audioPlayerId
            // Audio selection must remain enabled on every feed. We switch the
            // audible feed with volume only; disabling/re-enabling the renderer
            // caused a secondary feed to remain silent after switching from main.
            player.volume = if (isMain) 1f else 0f
            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
                .build()
        }
    }

    fun setMuted(id: String, muted: Boolean) {
        if (players[id] == null) return

        // Keep audio tracks selected on all multiview players and use volume as
        // the sole mute/audible-feed switch. This avoids tearing down a secondary
        // audio renderer when the user moves audio from MAIN to another feed.
        players.forEach { (otherId, player) ->
            player.volume = if (!muted && otherId == id) 1f else 0f
            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
                .build()
        }
        if (!muted) audioPlayerId = id
    }

    fun isMuted(id: String): Boolean =
        players[id]?.volume?.let { it <= 0.001f } ?: true

    /** Central synchronization entry point. */
    fun syncToMain(mainId: String, channelOffsetsMs: Map<String, Long>) {
        syncController.setReference(mainId)
        syncController.synchronize(channelOffsetsMs)
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

    /**
     * Start every prepared multiview feed immediately.
     *
     * There is no artificial startup delay. Media3/decoder initialization is allowed to
     * determine when each feed can actually render. Startup timing and first-frame metrics
     * provide the evidence needed if the device later proves that resource back-pressure
     * is required.
     */
    private fun scheduleStartupPlayback(id: String) {
        if (!playAllRequested || id !in desiredPlaying) return
        val player = players[id] ?: return
        if (player.playbackState == Player.STATE_READY || player.playbackState == Player.STATE_BUFFERING) {
            player.playWhenReady = true
            player.play()
            Log.i(
                "Media3DecoderManager",
                "STARTUP_PLAY id=" + id + " immediate=true players=" + players.size
            )
        }
    }

    fun playAll() {
        // Persist the intent so feeds that are still being prepared will also start
        // automatically when they reach READY.
        playAllRequested = true
        if (audioPlayerId == null) setAudioPlayer(players.keys.firstOrNull())
        val ordered = players.keys.toList()
        ordered.forEach { desiredPlaying.add(it) }
        // Do not artificially delay any feed. Prepared players are allowed to start
        // immediately; decoder/resource behavior is measured rather than assumed.
        ordered.forEach { id ->
            scheduleStartupPlayback(id)
        }
    }

    fun pauseAll() {
        playAllRequested = false
        players.keys.forEach { desiredPlaying.remove(it) }
        players.values.forEach { it.pause(); it.playWhenReady = false }
    }

    fun stopAll() {
        playAllRequested = false
        players.keys.forEach { desiredPlaying.remove(it) }
        players.values.forEach { it.stop(); it.playWhenReady = false }
        syncController.stop()
    }

    fun retain(ids: Set<String>) {
        players.keys.filterNot(ids::contains).toList().forEach(::clear)
        if (audioPlayerId !in players.keys) setAudioPlayer(null)
    }

    fun clear(id: String) {
        syncController.onFeedRemoved(id)
        desiredPlaying.remove(id)
        players.remove(id)?.release()
        resourceManager.release(id)
        selectedQualities.remove(id)
        streamKinds.remove(id)
        streams.remove(id)
        decoderRecoveryAttempts.remove(id)
        l3SecondaryFallback.remove(id)
        startupRequestedAtMs.remove(id)
        startupFirstFrameAtMs.remove(id)
        startupPlayingAtMs.remove(id)
        firstFrameRendered.remove(id)
        if (audioPlayerId == id) setAudioPlayer(null)
    }

    fun prepare(id: String) { players[id]?.prepare() }

    fun seekTo(id: String, positionMs: Long) { players[id]?.seekTo(positionMs) }

    fun seekToDefaultPosition(id: String) { players[id]?.seekToDefaultPosition() }

    fun setPlaybackParameters(id: String, parameters: androidx.media3.common.PlaybackParameters) {
        players[id]?.setPlaybackParameters(parameters)
    }

    fun setPlaybackSpeed(id: String, speed: Float) {
        players[id]?.setPlaybackSpeed(speed)
    }

    fun attachSurfaceView(id: String, surface: android.view.SurfaceView) {
        players[id]?.setVideoSurfaceView(surface)
    }

    fun detachSurfaceView(id: String, surface: android.view.SurfaceView) {
        players[id]?.clearVideoSurfaceView(surface)
    }

    fun attachTextureView(id: String, texture: android.view.TextureView) {
        players[id]?.setVideoTextureView(texture)
    }

    fun detachTextureView(id: String, texture: android.view.TextureView) {
        players[id]?.clearVideoTextureView(texture)
    }

    fun release() {
        mainHandler.removeCallbacksAndMessages(null)
        syncController.reset()
        players.values.forEach { it.release() }
        players.clear()
        resourceManager.reset()
        desiredPlaying.clear()
        audioPlayerId = null
        selectedQualities.clear()
        streamKinds.clear()
        streams.clear()
        decoderRecoveryAttempts.clear()
        l3SecondaryFallback.clear()
        startupRequestedAtMs.clear()
        startupFirstFrameAtMs.clear()
        startupPlayingAtMs.clear()
        firstFrameRendered.clear()
    }

    fun decoderResourceDiagnostics(): Map<String, String> = buildMap {
        put("capacity", resourceManager.capacity().toString())
        put("active", resourceManager.activeLeases().size.toString())
        put("available", resourceManager.availableSlots().toString())
        resourceManager.activeLeases().forEach { lease ->
            put("slot." + lease.slotId, lease.feedId + ":priority=" + lease.priority + ":reference=" + lease.isReference)
        }
    }

    /** Lightweight runtime diagnostics used to measure startup impact on-device. */
    fun playbackStartupDiagnostics(): Map<String, String> =
        players.mapValues { (id, player) ->
            "state=" + player.playbackState +
                ",isPlaying=" + player.isPlaying +
                ",firstFrame=" + firstFrameRendered.contains(id) +
                ",positionMs=" + player.currentPosition +
                ",bufferedMs=" + player.bufferedPosition +
                ",decoderSlot=" + (resourceManager.lease(id)?.slotId ?: -1)
        }
    fun all(): Collection<ExoPlayer> = players.values
}

@OptIn(UnstableApi::class)
private object ProductionLoadControl {
    fun create(): androidx.media3.exoplayer.LoadControl =
        androidx.media3.exoplayer.DefaultLoadControl.Builder()
            .setBufferDurationsMs(5_000, 20_000, 1_500, 5_000)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()
}
