package app.f1multiview.media

import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.Timeline
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
    private val source: MediaSource
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
    private var pendingError: IOException? = null
    private var periodPositionUs = 0L

    val state: State get() = stateValue
    val currentVideoFormat: Format? get() = videoFormat
    val currentVideoDrmSession: DrmSession? get() = videoDrmSession
    val positionUs: Long get() = periodPositionUs

    fun prepare() {
        checkThread()
        check(stateValue == State.NEW) { "Authorized CMAF source session can only be prepared once" }
        stateValue = State.PREPARING_SOURCE
        source.prepareSource(this, PlayerId("f1-cmaf-${stream.id}"), BandwidthMeter.NO_OP)
    }

    override fun onSourceInfoRefreshed(source: MediaSource, timeline: Timeline) {
        checkThread()
        if (stateValue == State.CLOSED || stateValue == State.FAILED || period != null) return
        try {
            if (timeline.periodCount == 0 || timeline.windowCount == 0) {
                throw IOException("Authorized F1 source returned an empty timeline")
            }
            source.enable(this)
            sourceEnabled = true
            val periodIndex = if (stream.isLive) timeline.periodCount - 1 else 0
            val periodInfo = timeline.getPeriod(periodIndex, Timeline.Period(), /* setIds= */ true)
            val window = timeline.getWindow(0, Timeline.Window())
            val defaultPosition = window.defaultPositionUs
            periodPositionUs = if (stream.isLive && defaultPosition != C.TIME_UNSET) {
                (defaultPosition - periodInfo.positionInWindowUs).coerceAtLeast(0L)
            } else {
                0L
            }
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

    override fun onPrepared(mediaPeriod: MediaPeriod) {
        checkThread()
        if (stateValue == State.CLOSED || stateValue == State.FAILED) return
        try {
            val groups = mediaPeriod.trackGroups
            var selectedGroup: androidx.media3.common.TrackGroup? = null
            for (index in 0 until groups.length) {
                val group = groups[index]
                if ((0 until group.length).any { group.getFormat(it).sampleMimeType?.startsWith("video/") == true }) {
                    selectedGroup = group
                    break
                }
            }
            val videoGroup = selectedGroup ?: throw IOException("Authorized F1 source has no video track")
            val selections: Array<ExoTrackSelection?> = arrayOf(
                FixedTrackSelection(videoGroup, /* track= */ 0, C.TRACK_TYPE_VIDEO)
            )
            val streams: Array<SampleStream?> = arrayOfNulls(1)
            val resetFlags = booleanArrayOf(false)
            mediaPeriod.selectTracks(
                selections,
                booleanArrayOf(false),
                streams,
                resetFlags,
                periodPositionUs
            )
            videoStream = streams[0] ?: throw IOException("Media3 did not create a video SampleStream")
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

    fun pump(positionUs: Long = periodPositionUs): ReadResult {
        checkThread()
        pendingError?.let { throw it }
        check(stateValue == State.READY) { "Authorized CMAF source is not ready: $stateValue" }
        val activePeriod = period ?: throw IOException("Media period was released")
        val activeStream = videoStream ?: throw IOException("Video sample stream is not selected")
        periodPositionUs = positionUs.coerceAtLeast(0L)
        // Retain a bounded rolling window behind the playback clock for live sessions.
        activePeriod.discardBuffer((periodPositionUs - LIVE_BACK_BUFFER_US).coerceAtLeast(0L), /* toKeyframe= */ true)
        activePeriod.continueLoading(LoadingInfo.Builder().setPlaybackPositionUs(periodPositionUs).build())
        activeStream.maybeThrowError()
        sampleBuffer.clear()
        val holder = androidx.media3.exoplayer.FormatHolder()
        return when (activeStream.readData(holder, sampleBuffer, /* readFlags= */ 0)) {
            C.RESULT_FORMAT_READ -> {
                videoFormat = holder.format
                videoDrmSession = holder.drmSession
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
        videoDrmSession = null
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

        fun open(stream: StreamSource, playbackLooper: Looper): AuthorizedCmafMediaSourceSession? {
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
                return AuthorizedCmafMediaSourceSession(stream, playbackLooper, drmManager, mediaSource)
            } catch (error: Throwable) {
                drmManager.release()
                throw error
            }
        }
    }
}
