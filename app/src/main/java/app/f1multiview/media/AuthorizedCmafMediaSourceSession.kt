package app.f1multiview.media

import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.Timeline
import androidx.media3.common.TrackGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.decoder.DecoderInputBuffer
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.drm.DefaultDrmSessionManager
import androidx.media3.exoplayer.drm.DrmSession
import androidx.media3.exoplayer.source.MediaPeriod
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.SampleStream
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import androidx.media3.exoplayer.trackselection.FixedTrackSelection
import androidx.media3.exoplayer.upstream.Allocator
import androidx.media3.exoplayer.upstream.BandwidthMeter
import androidx.media3.exoplayer.upstream.DefaultAllocator
import androidx.media3.exoplayer.LoadingInfo
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import app.f1multiview.model.StreamSource
import app.f1multiview.core.playback.Quality
import kotlin.math.abs
import java.io.IOException

/**
 * Owns an authorized Media3 HLS/DASH source and exposes its real demuxed video samples to the
 * app-owned secure decoder. This reuses Media3's manifest/chunk parsers, authenticated data source,
 * license callback and Widevine session manager; it does not create a second ExoPlayer.
 *
 * All lifecycle methods and pump() must be called on the supplied playback looper. The owner must
 * render output only through a generation-current protected SurfaceView lease.
 */
@OptIn(UnstableApi::class)
internal class AuthorizedCmafMediaSourceSession private constructor(
    private val stream: StreamSource,
    private val playbackLooper: Looper,
    private val drmManager: DefaultDrmSessionManager,
    private val source: MediaSource,
    private var quality: Quality,
    private var autoMaxWidth: Int,
    private var autoMaxHeight: Int
) : AutoCloseable, MediaSource.MediaSourceCaller, MediaPeriod.Callback {

    enum class State { NEW, PREPARING_SOURCE, PREPARING_PERIOD, READY, FAILED, CLOSED }
    enum class ReadKind { FORMAT, SAMPLE, NOTHING, END_OF_STREAM }

    data class ReadResult(
        val kind: ReadKind,
        val format: Format? = null,
        val drmSession: DrmSession? = null,
        val sample: DecoderInputBuffer? = null
    )

    private val ownerThread = Thread.currentThread()
    private val allocator: Allocator = DefaultAllocator(/* trimOnReset= */ true, 64 * 1024)
    private val sampleBuffer = DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_DIRECT)
    private var stateValue = State.NEW
    private var sourceEnabled = false
    private var period: MediaPeriod? = null
    private var videoStream: SampleStream? = null
    private var videoFormat: Format? = null
    private var videoDrmSession: DrmSession? = null
    private var selectedVideoGroup: TrackGroup? = null
    private var selectedVideoTrackIndex = -1
    private val qualityManager = QualityManager()
    private var pendingError: IOException? = null
    private var periodPositionUs = 0L
    private var defaultPositionUs = 0L
    private var durationUs = C.TIME_UNSET.toLong()
    private var playbackSpeed = 1f
    private var latestTimeline: Timeline? = null
    private var currentPeriodUid: Any? = null
    private var periodPositionInWindowUs = 0L
    private var timelineAbsoluteOffsetUs = 0L
    private var lastKnownPeriodWindowPositionUs: Long? = null

    val state: State get() = stateValue
    val currentVideoFormat: Format? get() = videoFormat
    val currentVideoDrmSession: DrmSession? get() = videoDrmSession
    val positionUs: Long get() = periodPositionUs
    val periodWindowOffsetUs: Long get() = periodPositionInWindowUs + timelineAbsoluteOffsetUs
    val durationMs: Long get() = if (durationUs == C.TIME_UNSET.toLong()) C.TIME_UNSET else durationUs / 1_000L

    fun toGlobalPresentationTimeUs(periodPresentationTimeUs: Long): Long =
        periodPresentationTimeUs + periodWindowOffsetUs

    fun globalPositionUsForWindow(windowPositionUs: Long): Long =
        windowPositionUs + timelineAbsoluteOffsetUs

    fun windowPositionUsForGlobal(globalPositionUs: Long): Long =
        globalPositionUs - timelineAbsoluteOffsetUs

    fun prepare() {
        checkThread()
        check(stateValue == State.NEW) { "Authorized CMAF source session can only be prepared once" }
        stateValue = State.PREPARING_SOURCE
        source.prepareSource(this, PlayerId("f1-cmaf-${stream.id}"), BandwidthMeter.NO_OP)
    }

    override fun onSourceInfoRefreshed(source: MediaSource, timeline: Timeline) {
        checkThread()
        if (stateValue == State.CLOSED || stateValue == State.FAILED) return
        latestTimeline = timeline
        try {
            if (timeline.periodCount == 0 || timeline.windowCount == 0) {
                throw IOException("Authorized F1 source returned an empty timeline")
            }
            val window = timeline.getWindow(0, Timeline.Window())
            durationUs = window.durationUs
            if (period != null) {
                val currentIndex = (0 until timeline.periodCount).firstOrNull {
                    timeline.getUidOfPeriod(it) == currentPeriodUid
                }
                if (currentIndex != null) {
                    val currentInfo = timeline.getPeriod(currentIndex, Timeline.Period(), /* setIds= */ true)
                    updateTimelineOffset(window, currentInfo, samePeriod = true)
                }
                return
            }
            if (!sourceEnabled) {
                source.enable(this)
                sourceEnabled = true
            }
            val periodIndex = if (stream.isLive) timeline.periodCount - 1 else 0
            val periodInfo = timeline.getPeriod(periodIndex, Timeline.Period(), /* setIds= */ true)
            updateTimelineOffset(window, periodInfo, samePeriod = false)
            currentPeriodUid = requireNotNull(periodInfo.uid)
            val defaultPosition = window.defaultPositionUs
            periodPositionUs = if (stream.isLive && defaultPosition != C.TIME_UNSET) {
                (defaultPosition - periodPositionInWindowUs).coerceAtLeast(0L)
            } else {
                0L
            }
            defaultPositionUs = periodPositionUs
            val newPeriod = source.createPeriod(
                MediaSource.MediaPeriodId(requireNotNull(periodInfo.uid)),
                allocator,
                periodPositionUs
            )
            period = newPeriod
            stateValue = State.PREPARING_PERIOD
            newPeriod.prepare(this, periodPositionUs)
        } catch (error: Exception) {
            fail(error)
        }
    }

    private fun updateTimelineOffset(
        window: Timeline.Window,
        periodInfo: Timeline.Period,
        samePeriod: Boolean
    ) {
        val previousPositionInWindowUs = lastKnownPeriodWindowPositionUs
        val hasAbsoluteWindowStart = window.windowStartTimeMs != C.TIME_UNSET
        if (stream.isLive && hasAbsoluteWindowStart) {
            timelineAbsoluteOffsetUs = window.windowStartTimeMs * 1_000L
        } else if (stream.isLive && samePeriod && previousPositionInWindowUs != null) {
            // If the live window slides but the source omits wall-clock start time, preserve
            // this period's global timestamp by compensating for its changed window-relative offset.
            timelineAbsoluteOffsetUs += previousPositionInWindowUs - periodInfo.positionInWindowUs
        } else if (!stream.isLive) {
            timelineAbsoluteOffsetUs = 0L
        }
        periodPositionInWindowUs = periodInfo.positionInWindowUs
        lastKnownPeriodWindowPositionUs = periodInfo.positionInWindowUs
    }

    override fun onPrepared(mediaPeriod: MediaPeriod) {
        checkThread()
        if (stateValue == State.CLOSED || stateValue == State.FAILED) return
        try {
            selectVideoTrack(mediaPeriod)
            stateValue = State.READY
        } catch (error: Exception) {
            fail(error)
        }
    }

    override fun onContinueLoadingRequested(source: MediaPeriod) {
        // The owner drives bounded pump() calls on the playback looper.
    }

    fun seekToUs(positionUs: Long): Long {
        checkThread()
        check(stateValue == State.READY) { "Authorized CMAF source is not ready: $stateValue" }
        val activePeriod = period ?: throw IOException("Media period was released")
        periodPositionUs = activePeriod.seekToUs(positionUs.coerceAtLeast(0L)).coerceAtLeast(0L)
        sampleBuffer.clear()
        return periodPositionUs
    }

    fun seekToWindowPositionUs(windowPositionUs: Long): Long =
        seekToUs((windowPositionUs - periodPositionInWindowUs).coerceAtLeast(0L))

    fun seekToGlobalPositionUs(globalPositionUs: Long): Long =
        seekToUs((globalPositionUs - periodWindowOffsetUs).coerceAtLeast(0L))

    fun seekToDefaultPosition(): Long = seekToUs(defaultPositionUs)

    fun pumpGlobalPosition(globalPositionUs: Long): ReadResult =
        pump((globalPositionUs - periodWindowOffsetUs).coerceAtLeast(0L))

    fun advanceToLatestLivePeriod(): Boolean {
        checkThread()
        if (!stream.isLive || stateValue != State.READY) return false
        val timeline = latestTimeline ?: return false
        if (timeline.periodCount == 0 || timeline.windowCount == 0) return false
        val latestIndex = timeline.periodCount - 1
        val latestInfo = timeline.getPeriod(latestIndex, Timeline.Period(), /* setIds= */ true)
        val latestUid = requireNotNull(latestInfo.uid)
        if (latestUid == currentPeriodUid) return false
        val window = timeline.getWindow(0, Timeline.Window())
        val oldPeriod = period
        period = null
        if (oldPeriod != null) source.releasePeriod(oldPeriod)
        videoStream = null
        videoFormat = null
        releaseVideoDrmSession()
        selectedVideoGroup = null
        selectedVideoTrackIndex = -1
        updateTimelineOffset(window, latestInfo, samePeriod = false)
        currentPeriodUid = latestUid
        periodPositionUs = if (window.defaultPositionUs != C.TIME_UNSET) {
            (window.defaultPositionUs - periodPositionInWindowUs).coerceAtLeast(0L)
        } else {
            0L
        }
        defaultPositionUs = periodPositionUs
        val next = source.createPeriod(MediaSource.MediaPeriodId(latestUid), allocator, periodPositionUs)
        period = next
        stateValue = State.PREPARING_PERIOD
        next.prepare(this, periodPositionUs)
        return true
    }

    fun setVideoQuality(quality: Quality, autoMaxWidth: Int, autoMaxHeight: Int) {
        checkThread()
        require(autoMaxWidth > 0 && autoMaxHeight > 0)
        this.quality = quality
        this.autoMaxWidth = autoMaxWidth
        this.autoMaxHeight = autoMaxHeight
        period?.takeIf { stateValue == State.READY }?.let(::selectVideoTrack)
    }

    fun availableVideoResolutions(): List<Pair<Int, Int>> =
        period?.trackGroups?.let { groups ->
            (0 until groups.length).flatMap { groupIndex ->
                val group = groups[groupIndex]
                (0 until group.length).mapNotNull { trackIndex ->
                    val format = group.getFormat(trackIndex)
                    if (format.sampleMimeType?.startsWith("video/") == true && format.width > 0 && format.height > 0) {
                        format.width to format.height
                    } else null
                }
            }.distinct().sortedByDescending { it.second }
        } ?: emptyList()

    fun qualityAvailable(quality: Quality): Boolean {
        if (quality == Quality.AUTO) return true
        return availableVideoResolutions().any { it.second >= qualityManager.minimumHeight(quality) }
    }

    private fun selectVideoTrack(mediaPeriod: MediaPeriod) {
        val candidates = mutableListOf<Triple<TrackGroup, Int, Format>>()
        val groups = mediaPeriod.trackGroups
        for (groupIndex in 0 until groups.length) {
            val group = groups[groupIndex]
            for (trackIndex in 0 until group.length) {
                val format = group.getFormat(trackIndex)
                if (format.sampleMimeType?.startsWith("video/") == true) {
                    candidates += Triple(group, trackIndex, format)
                }
            }
        }
        if (candidates.isEmpty()) throw IOException("Authorized F1 source has no video track")
        val sized = candidates.filter { it.third.width > 0 && it.third.height > 0 }
        val selected = when (quality) {
            Quality.AUTO -> sized.filter {
                it.third.width <= autoMaxWidth && it.third.height <= autoMaxHeight
            }.maxWithOrNull(compareBy<Triple<TrackGroup, Int, Format>> { it.third.height }
                .thenBy { it.third.width }.thenBy { it.third.bitrate })
                ?: sized.minWithOrNull(compareBy<Triple<TrackGroup, Int, Format>> { it.third.height }
                    .thenBy { it.third.width }.thenBy { it.third.bitrate })
                ?: candidates.first()
            else -> {
                val desiredHeight = qualityManager.minimumHeight(quality)
                val near = sized.filter {
                    it.third.height >= desiredHeight - 32 && it.third.height <= desiredHeight + 256
                }
                near.maxWithOrNull(compareBy<Triple<TrackGroup, Int, Format>> { it.third.height }
                    .thenBy { it.third.width }.thenBy { it.third.bitrate })
                    ?: sized.minByOrNull { abs(it.third.height - desiredHeight) }
                    ?: candidates.first()
            }
        }
        val (group, trackIndex, _) = selected
        if (selectedVideoGroup === group && selectedVideoTrackIndex == trackIndex && videoStream != null) return

        // MediaPeriod selection arrays are indexed by the complete TrackGroupArray, not by
        // selected tracks. A one-element array breaks manifests exposing multiple track groups.
        val selectedGroupIndex = (0 until groups.length).firstOrNull { groups[it] === group }
            ?: throw IOException("Selected video TrackGroup is no longer present in the MediaPeriod")
        val streams = arrayOfNulls<SampleStream>(groups.length)
        selectedVideoGroup?.let { previousGroup ->
            val previousGroupIndex = (0 until groups.length).firstOrNull { groups[it] === previousGroup }
            if (previousGroupIndex != null) streams[previousGroupIndex] = videoStream
        }
        val selections = arrayOfNulls<ExoTrackSelection>(groups.length)
        selections[selectedGroupIndex] = FixedTrackSelection(group, trackIndex, C.TRACK_TYPE_VIDEO)
        val mayRetainStreamFlags = BooleanArray(groups.length)
        val resetFlags = BooleanArray(groups.length)

        mediaPeriod.selectTracks(
            selections,
            mayRetainStreamFlags,
            streams,
            resetFlags,
            periodPositionUs
        )
        videoStream = streams[selectedGroupIndex]
            ?: throw IOException("Media3 did not create a video SampleStream at group=$selectedGroupIndex of ${groups.length}")
        selectedVideoGroup = group
        selectedVideoTrackIndex = trackIndex
        videoFormat = null
        // A track change invalidates the prior track's DRM reference. Release it through
        // the same owner path rather than dropping the reference and leaking a session.
        releaseVideoDrmSession()
        AppLogger.i(
            "AuthorizedCmafSource",
            "VIDEO_TRACK_SELECTED feed=${stream.id} group=$selectedGroupIndex/${groups.length} " +
                "track=$trackIndex mime=${selected.third.sampleMimeType} " +
                "size=${selected.third.width}x${selected.third.height} bitrate=${selected.third.bitrate}"
        )
    }

    fun setPlaybackSpeed(speed: Float) {
        checkThread()
        require(speed in MIN_PLAYBACK_SPEED..MAX_PLAYBACK_SPEED) { "Playback speed is outside the supported range" }
        playbackSpeed = speed
    }

    fun pump(positionUs: Long = periodPositionUs): ReadResult {
        checkThread()
        pendingError?.let { throw it }
        check(stateValue == State.READY) { "Authorized CMAF source is not ready: $stateValue" }
        val activePeriod = period ?: throw IOException("Media period was released")
        val activeStream = videoStream ?: throw IOException("Video sample stream is not selected")
        periodPositionUs = positionUs.coerceAtLeast(0L)
        // Retain a bounded rolling window behind the playback clock for live sessions.
        activePeriod.discardBuffer((periodPositionUs - LIVE_BACK_BUFFER_US).coerceAtLeast(0L), /* toKeyframe= */ true)
        activePeriod.continueLoading(
            LoadingInfo.Builder()
                .setPlaybackPositionUs(periodPositionUs)
                .setPlaybackSpeed(playbackSpeed)
                .build()
        )
        activeStream.maybeThrowError()
        sampleBuffer.clear()
        val holder = androidx.media3.exoplayer.FormatHolder()
        return when (activeStream.readData(holder, sampleBuffer, /* readFlags= */ 0)) {
            C.RESULT_FORMAT_READ -> {
                val format = holder.format ?: throw IOException("Media3 emitted a video format result without a format")
                videoFormat = format
                // A manually driven MediaPeriod has no ExoPlayer renderer to acquire the DRM
                // session on our behalf. SampleStream's FormatHolder may therefore carry no
                // session even though the track is protected. Acquire it explicitly from the
                // same Media3 manager configured for this authorized source and keep it alive
                // until the track changes or this source session closes.
                val streamSession = holder.drmSession
                when {
                    streamSession != null && videoDrmSession !== streamSession -> {
                        releaseVideoDrmSession()
                        // FormatHolder exposes the session used by Media3's sample stream, but
                        // does not transfer an acquired reference to this manually-driven
                        // renderer. Take our own reference before retaining it or passing it to
                        // SecureCmafVideoDecoder, which acquires a separate decoder-lifetime ref.
                        streamSession.acquire(/* eventDispatcher= */ null)
                        videoDrmSession = streamSession
                    }
                    streamSession == null && videoDrmSession == null -> {
                        videoDrmSession = drmManager.acquireSession(/* eventDispatcher= */ null, format)
                    }
                }
                val initData = format.drmInitData
                AppLogger.i(
                    "AuthorizedCmafSource",
                    "VIDEO_FORMAT_DRM feed=${stream.id} mime=${format.sampleMimeType} " +
                        "drmInitData=${initData != null} schemeCount=${initData?.schemeDataCount ?: 0} " +
                        "holderSession=${streamSession != null} retainedSession=${videoDrmSession != null} " +
                        "sessionState=${videoDrmSession?.state ?: -1} " +
                        "cryptoType=${videoDrmSession?.cryptoConfig?.javaClass?.simpleName ?: "none"}"
                )
                ReadResult(ReadKind.FORMAT, videoFormat, videoDrmSession)
            }
            C.RESULT_BUFFER_READ -> {
                if (sampleBuffer.isEndOfStream) {
                    ReadResult(ReadKind.END_OF_STREAM, videoFormat, videoDrmSession, sampleBuffer)
                } else {
                    ReadResult(ReadKind.SAMPLE, videoFormat, videoDrmSession, sampleBuffer)
                }
            }
            C.RESULT_NOTHING_READ -> ReadResult(ReadKind.NOTHING, videoFormat, videoDrmSession)
            else -> throw IOException("Unexpected Media3 SampleStream read result")
        }
    }

    private fun releaseVideoDrmSession() {
        val activeSession = videoDrmSession ?: return
        videoDrmSession = null
        runCatching { activeSession.release(/* eventDispatcher= */ null) }
    }

    private fun fail(error: Exception) {
        val io = if (error is IOException) error else IOException("Authorized CMAF source preparation failed", error)
        pendingError = io
        stateValue = State.FAILED
        AppLogger.e("AuthorizedCmafSource", "SOURCE_FAILED feed=${stream.id} message=${io.message}", io)
    }

    private fun checkThread() {
        check(Thread.currentThread() === ownerThread && Looper.myLooper() == playbackLooper) {
            "AuthorizedCmafMediaSourceSession must be used on its playback looper"
        }
    }

    override fun close() {
        if (stateValue == State.CLOSED) return
        checkThread()
        stateValue = State.CLOSED
        videoStream = null
        videoFormat = null
        releaseVideoDrmSession()
        period?.let { active ->
            runCatching { source.releasePeriod(active) }
            period = null
        }
        if (sourceEnabled) {
            runCatching { source.disable(this) }
            sourceEnabled = false
        }
        runCatching { source.releaseSource(this) }
        runCatching { drmManager.release() }
        allocator.trim()
    }

    companion object {
        private const val LIVE_BACK_BUFFER_US = 2_000_000L
        private const val MIN_PLAYBACK_SPEED = 0.25f
        private const val MAX_PLAYBACK_SPEED = 2.0f

        fun open(
            stream: StreamSource,
            playbackLooper: Looper,
            quality: Quality = Quality.AUTO,
            autoMaxWidth: Int = Int.MAX_VALUE,
            autoMaxHeight: Int = Int.MAX_VALUE
        ): AuthorizedCmafMediaSourceSession? {
            require(DrmProtectionPolicy.requiresProtectedOutput(stream)) {
                "Authorized CMAF source session is only for protected feeds"
            }
            val configuration = AuthorizedWidevineDrmSessionFactory.configurationFor(stream) ?: return null
            val drmManager = configuration.createSessionManager()
            try {
                check(Looper.myLooper() == playbackLooper) { "Open must run on the playback looper" }
                drmManager.setPlayer(playbackLooper, PlayerId("f1-cmaf-${stream.id}"))
                drmManager.prepare()
                val url = requireNotNull(stream.url) { "Authorized stream URL is missing" }
                val mediaItem = MediaItem.Builder()
                    .setUri(url)
                    .apply {
                        if (url.contains(".mpd", ignoreCase = true)) {
                            setMimeType(androidx.media3.common.MimeTypes.APPLICATION_MPD)
                        } else if (url.contains(".m3u8", ignoreCase = true)) {
                            setMimeType(androidx.media3.common.MimeTypes.APPLICATION_M3U8)
                        }
                        setDrmConfiguration(
                            MediaItem.DrmConfiguration.Builder(C.WIDEVINE_UUID)
                                .setLicenseUri(configuration.licenseUrl)
                                .setLicenseRequestHeaders(configuration.requestHeaders)
                                .build()
                        )
                    }
                    .build()
                val mediaSource = DefaultMediaSourceFactory(AuthorizedStreamDataSourceFactory.create(stream))
                    .setDrmSessionManagerProvider { drmManager }
                    .createMediaSource(mediaItem)
                return AuthorizedCmafMediaSourceSession(
                    stream, playbackLooper, drmManager, mediaSource,
                    quality, autoMaxWidth, autoMaxHeight
                )
            } catch (error: Throwable) {
                drmManager.release()
                throw error
            }
        }
    }
}
