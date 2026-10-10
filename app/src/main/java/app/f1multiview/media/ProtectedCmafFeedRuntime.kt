package app.f1multiview.media

import android.os.Looper
import android.os.SystemClock
import android.view.Choreographer
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.decoder.DecoderInputBuffer
import androidx.media3.exoplayer.drm.DrmSession
import app.f1multiview.model.StreamSource
import app.f1multiview.core.playback.Quality
import java.io.IOException

/**
 * Drives one authorized protected feed without an ExoPlayer decoder.
 *
 * The media source, DRM session, sample stream, secure MediaCodec and protected SurfaceView remain
 * separate owned resources. This class is intentionally fail-closed and does not composite or read
 * back protected pixels. It is an integration runtime; device concurrency and sync still require
 * physical validation before the production gate can be opened.
 */
internal class ProtectedCmafFeedRuntime(
    private val stream: StreamSource,
    private val surfaceManager: MultiviewSurfaceManager,
    private val clock: ProtectedPresentationClock,
    private val playbackLooper: Looper,
    private var quality: Quality = Quality.AUTO,
    private val autoMaxWidth: Int = if (stream.id == clock.masterFeedId) Int.MAX_VALUE else 854,
    private val autoMaxHeight: Int = if (stream.id == clock.masterFeedId) Int.MAX_VALUE else 480,
    private val onStatus: (String, String) -> Unit
) : AutoCloseable, Choreographer.FrameCallback {

    enum class State { NEW, PREPARING, WAITING_FOR_SURFACE_OR_KEYS, WAITING_FOR_FIRST_FRAME, PLAYING, PAUSED, FAILED, CLOSED }

    private val ownerThread = Thread.currentThread()
    private val choreographer = Choreographer.getInstance()
    private var source: AuthorizedCmafMediaSourceSession? = null
    private var decoder: SecureCmafVideoDecoder? = null
    private var surfaceLease: ProtectedSurfaceLease? = null
    private var pendingSample: DecoderInputBuffer? = null
    private var eosQueued = false
    private var running = false
    private var frameScheduled = false
    private var lastFormat: Format? = null
    private var lastPublishedMessage: String? = null
    private var pendingHardResync = false
    private var pendingSeekUs: Long? = null
    private var pendingDefaultSeek = false
    private var renderedFrameCount = 0L
    private var queuedSampleCount = 0L
    private var lastSamplePtsUs: Long? = null
    private var lastRenderedPtsUs: Long? = null
    private var lastRenderedAtNs: Long? = null
    private var lastObservedSourceState: AuthorizedCmafMediaSourceSession.State? = null
    private var lastPlaybackError: String? = null
    private var firstFramePresentedAtNs: Long? = null
    private var lastCodecName: String? = null
    private var lastDrmSessionState: String? = null
    private var lastSourceState: String? = null
    private var lastVideoMime: String? = null
    private var lastVideoSize: String? = null
    private var lastSampleGlobalPtsUs: Long? = null
    private var lastRenderedGlobalPtsUs: Long? = null
    private var lastSurfaceGeneration: Long? = null
    private var lastSurfaceWidth: Int? = null
    private var lastSurfaceHeight: Int? = null
    private var stateValue = State.NEW

    val state: State get() = stateValue
    val isPlaying: Boolean get() = stateValue == State.PLAYING
    val durationMs: Long get() = source?.durationMs ?: androidx.media3.common.C.TIME_UNSET
    fun currentPositionMs(): Long {
        val activeSource = source ?: return 0L
        return if (clock.isReady) activeSource.windowPositionUsForGlobal(clock.positionUsFor(stream.id)) / 1_000L else 0L
    }

    fun globalPositionUsForWindow(windowPositionUs: Long): Long =
        source?.globalPositionUsForWindow(windowPositionUs) ?: windowPositionUs

    fun start() {
        checkThread()
        check(stateValue == State.NEW) { "Protected feed runtime can only start once" }
        if (!DrmProtectionPolicy.requiresProtectedOutput(stream) || DrmProtectionPolicy.missingLicenseEndpoint(stream)) {
            fail(IOException("Protected feed is missing its authorized Widevine configuration"))
            return
        }
        stateValue = State.PREPARING
        publish("PREPARING: authorized manifest and Widevine source")
        try {
            source = AuthorizedCmafMediaSourceSession.open(stream, playbackLooper, quality, autoMaxWidth, autoMaxHeight)
                ?: throw IOException("Authorized source did not provide a Widevine license endpoint")
            source!!.prepare()
            running = true
            scheduleFrame()
        } catch (error: Exception) {
            fail(error)
        }
    }

    fun play() {
        checkThread()
        if (stateValue == State.FAILED || stateValue == State.CLOSED) return
        running = true
        if (stateValue == State.PAUSED) stateValue = State.WAITING_FOR_SURFACE_OR_KEYS
        scheduleFrame()
    }

    fun seekTo(positionMs: Long) {
        checkThread()
        require(positionMs >= 0L)
        val activeSource = source ?: return
        if (activeSource.state == AuthorizedCmafMediaSourceSession.State.READY) {
            activeSource.seekToWindowPositionUs(positionMs * 1_000L)
        } else {
            pendingSeekUs = positionMs * 1_000L
            pendingDefaultSeek = false
        }
        decoder?.close()
        decoder = null
        pendingSample = null
        eosQueued = false
        stateValue = State.WAITING_FOR_SURFACE_OR_KEYS
        publish("SEEKING: ${positionMs}ms")
        scheduleFrame()
    }

    fun seekToDefaultPosition() {
        checkThread()
        val activeSource = source ?: return
        if (activeSource.state == AuthorizedCmafMediaSourceSession.State.READY) {
            activeSource.seekToDefaultPosition()
        } else {
            pendingDefaultSeek = true
            pendingSeekUs = null
        }
        decoder?.close()
        decoder = null
        pendingSample = null
        eosQueued = false
        stateValue = State.WAITING_FOR_SURFACE_OR_KEYS
        publish("SEEKING: default position")
        scheduleFrame()
    }

    fun setPlaybackSpeed(speed: Float) {
        checkThread()
        source?.setPlaybackSpeed(speed)
    }

    fun setQuality(newQuality: Quality) {
        checkThread()
        if (quality == newQuality) return
        quality = newQuality
        source?.setVideoQuality(newQuality, autoMaxWidth, autoMaxHeight)
        decoder?.close()
        decoder = null
        pendingSample = null
        stateValue = State.WAITING_FOR_SURFACE_OR_KEYS
        publish("QUALITY: $newQuality")
        scheduleFrame()
    }

    fun availableVideoResolutions(): List<Pair<Int, Int>> = source?.availableVideoResolutions().orEmpty()

    fun qualityAvailable(candidate: Quality): Boolean =
        candidate == Quality.AUTO || source?.qualityAvailable(candidate) == true

    /** Snapshot diagnostics distinguish a configured decoder from a frame actually presented. */
    fun diagnostics(): Map<String, String> {
        val activeSource = source
        val drm = activeSource?.currentVideoDrmSession
        val lease = surfaceLease
        val activeDecoder = decoder
        return mapOf(
            "state" to stateValue.name,
            "sourceState" to (activeSource?.state?.name ?: lastSourceState ?: "UNAVAILABLE"),
            "drmSessionState" to (drm?.state?.toString() ?: lastDrmSessionState ?: "UNAVAILABLE"),
            "drmKeysReady" to (drm?.state == DrmSession.STATE_OPENED_WITH_KEYS).toString(),
            "videoMime" to (activeSource?.currentVideoFormat?.sampleMimeType ?: lastVideoMime ?: "UNAVAILABLE"),
            "videoSize" to (activeSource?.currentVideoFormat?.let { "${it.width}x${it.height}" } ?: lastVideoSize ?: "UNAVAILABLE"),
            "decoderConfigured" to (activeDecoder?.codecName != null).toString(),
            "decoderName" to (activeDecoder?.codecName ?: lastCodecName ?: "UNAVAILABLE"),
            "outputSurfaceValid" to (lease?.surface?.isValid == true).toString(),
            "outputSurfaceProtected" to (lease?.secureFlagRequested == true).toString(),
            "outputSurfaceGeneration" to (lease?.generation?.toString() ?: lastSurfaceGeneration?.toString() ?: "UNAVAILABLE"),
            "outputSurfaceSize" to (lease?.let { "${it.width}x${it.height}" } ?: lastSurfaceWidth?.let { w -> "$w x ${lastSurfaceHeight ?: 0}" } ?: "UNAVAILABLE"),
            "samplesQueued" to queuedSampleCount.toString(),
            "lastSamplePtsUs" to (lastSamplePtsUs?.toString() ?: "UNAVAILABLE"),
            "lastSampleGlobalPtsUs" to (lastSampleGlobalPtsUs?.toString() ?: "UNAVAILABLE"),
            "firstFramePresented" to (renderedFrameCount > 0L).toString(),
            "renderedFrameCount" to renderedFrameCount.toString(),
            "firstFrameRenderedAtNs" to (firstFramePresentedAtNs?.toString() ?: "UNAVAILABLE"),
            "lastRenderedPtsUs" to (lastRenderedPtsUs?.toString() ?: "UNAVAILABLE"),
            "lastRenderedGlobalPtsUs" to (lastRenderedGlobalPtsUs?.toString() ?: "UNAVAILABLE"),
            "lastRenderedAtNs" to (lastRenderedAtNs?.toString() ?: "UNAVAILABLE"),
            "playbackError" to (lastPlaybackError ?: "")
        )
    }

    fun pause() {
        checkThread()
        if (stateValue == State.FAILED || stateValue == State.CLOSED) return
        running = false
        if (frameScheduled) {
            choreographer.removeFrameCallback(this)
            frameScheduled = false
        }
        stateValue = State.PAUSED
        publish("PAUSED")
    }

    override fun doFrame(frameTimeNanos: Long) {
        frameScheduled = false
        if (!running || stateValue == State.FAILED || stateValue == State.CLOSED) return
        try {
            pumpOnce()
        } catch (error: Exception) {
            val lease = surfaceLease
            if (lease != null && !surfaceManager.isCurrentProtectedSurfaceLease(lease)) {
                // Surface destruction invalidates the lease, not the authorized media session.
                decoder?.close()
                decoder = null
                surfaceLease = null
                stateValue = State.WAITING_FOR_SURFACE_OR_KEYS
                publish("WAITING: protected surface recreated")
            } else {
                fail(error)
                return
            }
        }
        scheduleFrame()
    }

    private fun pumpOnce() {
        checkThread()
        val activeSource = source ?: return
        if (activeSource.state == AuthorizedCmafMediaSourceSession.State.FAILED) {
            throw IOException("Authorized CMAF source failed")
        }
        if (activeSource.state != AuthorizedCmafMediaSourceSession.State.READY) {
            if (lastObservedSourceState != activeSource.state) {
                lastObservedSourceState = activeSource.state
                stateValue = if (activeSource.state == AuthorizedCmafMediaSourceSession.State.FAILED) State.FAILED else State.PREPARING
                publish("SOURCE_WAIT: ${activeSource.state}; awaiting authorized Media3 source/period preparation")
            }
            return
        }
        if (lastObservedSourceState != activeSource.state) {
            lastObservedSourceState = activeSource.state
            stateValue = State.WAITING_FOR_SURFACE_OR_KEYS
            publish("SOURCE_READY: authorized Media3 sample stream is prepared")
        }
        if (pendingDefaultSeek) {
            activeSource.seekToDefaultPosition()
            pendingDefaultSeek = false
            pendingSeekUs = null
        } else {
            pendingSeekUs?.let { activeSource.seekToWindowPositionUs(it) }
            pendingSeekUs = null
        }

        if (pendingHardResync) {
            pendingHardResync = false
            decoder?.close()
            decoder = null
            pendingSample = null
            activeSource.seekToGlobalPositionUs(clock.positionUsFor(stream.id))
            publish("SYNC_HARD_RESYNC: follower sought to shared main-feed clock")
        }

        val oldLease = surfaceLease
        if (decoder != null && oldLease != null && !surfaceManager.isCurrentProtectedSurfaceLease(oldLease)) {
            decoder?.close()
            decoder = null
            surfaceLease = null
        }

        if (pendingSample == null && !eosQueued) {
            val result = if (clock.isReady) activeSource.pumpGlobalPosition(clock.positionUsFor(stream.id)) else activeSource.pump()
            when (result.kind) {
                AuthorizedCmafMediaSourceSession.ReadKind.FORMAT -> {
                    val format = result.format ?: throw IOException("Video format result had no format")
                    if (lastFormat != null && lastFormat != format) {
                        decoder?.close()
                        decoder = null
                        surfaceLease = null
                    }
                    lastFormat = format
                }
                AuthorizedCmafMediaSourceSession.ReadKind.SAMPLE -> {
                    val sample = result.sample ?: throw IOException("Video sample result had no sample buffer")
                    if (sample.isEncrypted && result.drmSession == null) {
                        throw IOException("Encrypted video sample has no associated Widevine DRM session")
                    }
                    if (stream.id == clock.masterFeedId && !clock.isReady) {
                        clock.establish(activeSource.toGlobalPresentationTimeUs(sample.timeUs))
                    }
                    pendingSample = sample
                    lastSamplePtsUs = sample.timeUs
                    lastSampleGlobalPtsUs = activeSource.toGlobalPresentationTimeUs(sample.timeUs)
                    if (queuedSampleCount == 0L) {
                        AppLogger.i("ProtectedCmafRuntime", "FIRST_SAMPLE_READ feed=${stream.id} ptsUs=${sample.timeUs} " +
                            "encrypted=${sample.isEncrypted} keyFrame=${sample.isKeyFrame()} drmState=${result.drmSession?.state}")
                    }
                }
                AuthorizedCmafMediaSourceSession.ReadKind.END_OF_STREAM -> {
                    if (stream.isLive) {
                        if (activeSource.advanceToLatestLivePeriod()) {
                            decoder?.close()
                            decoder = null
                            pendingSample = null
                            eosQueued = false
                            lastFormat = null
                            stateValue = State.PREPARING
                            publish("LIVE_PERIOD_ADVANCE: waiting for next authorized DASH period")
                        } else {
                            stateValue = State.WAITING_FOR_SURFACE_OR_KEYS
                            publish("WAITING: next live period is not available yet")
                        }
                    } else {
                        pendingSample = result.sample
                        eosQueued = true
                    }
                }
                AuthorizedCmafMediaSourceSession.ReadKind.NOTHING -> Unit
            }
        }

        ensureSecureDecoder(activeSource)
        val activeDecoder = decoder
        if (activeDecoder != null) {
            pendingSample?.let { sample ->
                if (activeDecoder.queueSample(sample)) {
                    pendingSample = null
                    queuedSampleCount++
                    if (queuedSampleCount == 1L || queuedSampleCount % 30L == 0L) {
                        AppLogger.i("ProtectedCmafRuntime", "SAMPLE_QUEUED feed=${stream.id} count=$queuedSampleCount " +
                            "lastPtsUs=${sample.timeUs} encrypted=${sample.isEncrypted} codec=${activeDecoder.codecName} " +
                            "surfaceValid=${surfaceLease?.surface?.isValid == true}")
                    }
                }
            }
            activeDecoder.drainOutput()
        }
    }

    private fun ensureSecureDecoder(activeSource: AuthorizedCmafMediaSourceSession) {
        if (decoder != null) return
        if (!clock.isReady) {
            stateValue = State.PREPARING
            publish("WAITING_FOR_MASTER_CLOCK: awaiting the selected main feed's first sample")
            return
        }
        val format = activeSource.currentVideoFormat ?: run {
            stateValue = State.WAITING_FOR_SURFACE_OR_KEYS
            publish("WAITING_FOR_VIDEO_FORMAT: authorized source has not emitted a video format")
            return
        }
        val drmSession = activeSource.currentVideoDrmSession ?: run {
            stateValue = State.WAITING_FOR_SURFACE_OR_KEYS
            publish("WAITING_FOR_WIDEVINE_SESSION: Media3 has not attached a DRM session to the video track")
            return
        }
        if (drmSession.state == DrmSession.STATE_ERROR) {
            throw IOException("Widevine session entered an error state", drmSession.error)
        }
        if (drmSession.state != DrmSession.STATE_OPENED_WITH_KEYS) {
            stateValue = State.WAITING_FOR_SURFACE_OR_KEYS
            publish("WAITING_FOR_WIDEVINE_KEYS: drmSessionState=" + drmSession.state)
            return
        }
        var lease = surfaceLease
        if (lease == null || !surfaceManager.isCurrentProtectedSurfaceLease(lease)) {
            lease = surfaceManager.claimProtectedSurfaceForOwnDecoder(stream.id)
            if (lease == null) {
                stateValue = State.WAITING_FOR_SURFACE_OR_KEYS
                publish("WAITING_FOR_PROTECTED_SURFACE: secure SurfaceView lease is not currently available")
                return
            }
            surfaceLease = lease
        }
        AppLogger.i("ProtectedCmafRuntime", "SECURE_DECODER_PREPARE feed=${stream.id} " +
            "drmState=${drmSession.state} hasKeys=${drmSession.state == DrmSession.STATE_OPENED_WITH_KEYS} " +
            "mime=${format.sampleMimeType} size=${format.width}x${format.height} " +
            "surfaceGeneration=${lease.generation} surfaceId=${System.identityHashCode(lease.surface)} " +
            "surfaceValid=${lease.surface.isValid} secureFlag=${lease.secureFlagRequested} " +
            "surfaceSize=${lease.width}x${lease.height}")
        lastDrmSessionState = drmSession.state.toString()
        lastSourceState = activeSource.state.name
        lastVideoMime = format.sampleMimeType
        lastVideoSize = "${format.width}x${format.height}"
        lastSurfaceGeneration = lease.generation
        lastSurfaceWidth = lease.width
        lastSurfaceHeight = lease.height
        decoder = SecureCmafVideoDecoder(
            feedId = stream.id,
            format = format,
            drmSession = drmSession,
            surfaceLease = lease,
            isLeaseCurrent = surfaceManager::isCurrentProtectedSurfaceLease,
            releaseTimeNsForPresentationTimeUs = { presentationTimeUs ->
                val globalPtsUs = source?.toGlobalPresentationTimeUs(presentationTimeUs) ?: presentationTimeUs
                clock.presentationTimeNs(stream.id, globalPtsUs)
            },
            onFrameRendered = { presentationTimeUs, renderTimeNs ->
                lastRenderedPtsUs = presentationTimeUs
                lastRenderedAtNs = renderTimeNs
                if (firstFramePresentedAtNs == null) firstFramePresentedAtNs = renderTimeNs
                if (running && stateValue != State.PLAYING) {
                    stateValue = State.PLAYING
                    publish("FIRST_FRAME_RENDERED ptsUs=$presentationTimeUs renderTimeNs=$renderTimeNs")
                } else if (!running && stateValue != State.PAUSED) {
                    publish("FRAME_RENDERED_WHILE_NOT_RUNNING ptsUs=$presentationTimeUs renderTimeNs=$renderTimeNs")
                }
                renderedFrameCount++
                val globalPtsUs = source?.toGlobalPresentationTimeUs(presentationTimeUs) ?: presentationTimeUs
                lastRenderedGlobalPtsUs = globalPtsUs
                if (renderedFrameCount == 1L && stream.id != clock.masterFeedId) {
                    val calibratedOffsetUs = clock.calibrateFollowerOffset(stream.id, globalPtsUs, renderTimeNs)
                    if (calibratedOffsetUs != null) {
                        AppLogger.i("ProtectedCmafSync", "FIRST_FRAME_OFFSET_CALIBRATED feed=" + stream.id +
                            " offsetMs=" + (calibratedOffsetUs / 1000.0))
                    }
                }
                val observation = clock.observeRenderedFrame(stream.id, globalPtsUs, renderTimeNs, stream.isLive)
                if (renderedFrameCount % 30L == 0L) {
                    AppLogger.i(
                        "ProtectedCmafSync",
                        "feed=${stream.id} renderedFrames=$renderedFrameCount driftMs=${observation.driftUs / 1000.0} correctionMs=${observation.correctionUs / 1000.0} hardResync=${observation.hardResync}"
                    )
                }
                if (observation.hardResync) pendingHardResync = true
            }
        )
        lastCodecName = decoder?.codecName ?: lastCodecName
        stateValue = State.WAITING_FOR_FIRST_FRAME
        AppLogger.i("ProtectedCmafRuntime", "SECURE_DECODER_CONFIGURED feed=${stream.id} " +
            "codec=${decoder?.codecName ?: "unknown"} inputEnded=${decoder?.isInputEnded} " +
            "surfaceGeneration=${lease.generation} surfaceValid=${lease.surface.isValid} " +
            "drmState=${drmSession.state}")
        publish("WAITING_FOR_FIRST_FRAME: secure decoder configured; awaiting rendered-frame callback")
    }

    private fun scheduleFrame() {
        if (!running || frameScheduled || stateValue == State.FAILED || stateValue == State.CLOSED) return
        frameScheduled = true
        choreographer.postFrameCallback(this)
    }

    private fun publish(message: String) {
        if (lastPublishedMessage == message) return
        lastPublishedMessage = message
        val activeSource = source
        val drmSession = activeSource?.currentVideoDrmSession
        activeSource?.let { lastSourceState = it.state.name }
        drmSession?.let { lastDrmSessionState = it.state.toString() }
        val drmState = drmSession?.state?.toString() ?: "unavailable"
        val sourceState = activeSource?.state?.toString() ?: "unavailable"
        val lease = surfaceLease
        val activeDecoder = decoder
        AppLogger.i("ProtectedCmafRuntime", "FEED_STATE feed=${stream.id} state=$stateValue " +
            "message=$message sourceState=$sourceState drmState=$drmState " +
            "keysReady=${drmSession?.state == DrmSession.STATE_OPENED_WITH_KEYS} " +
            "mime=${activeSource?.currentVideoFormat?.sampleMimeType ?: "unknown"} " +
            "format=${activeSource?.currentVideoFormat?.width ?: 0}x${activeSource?.currentVideoFormat?.height ?: 0} " +
            "codec=${activeDecoder?.codecName ?: "none"} samplesQueued=$queuedSampleCount " +
            "lastSamplePtsUs=${lastSamplePtsUs ?: -1L} frames=${activeDecoder?.renderedFrameCount ?: 0L} " +
            "firstFrameAtNs=${activeDecoder?.firstFrameRenderedAtNs ?: -1L} " +
            "lastRenderedPtsUs=${lastRenderedPtsUs ?: -1L} lastRenderedAtNs=${lastRenderedAtNs ?: -1L} " +
            "surfaceGeneration=${lease?.generation ?: -1L} " +
            "surfaceValid=${lease?.surface?.isValid ?: false} secure=${lease?.secureFlagRequested ?: false} " +
            "surfaceSize=${lease?.width ?: 0}x${lease?.height ?: 0} " +
            "surfaceId=${lease?.surface?.let(System::identityHashCode) ?: -1}")
        onStatus(stream.id, message)
    }

    fun matches(candidate: StreamSource): Boolean = stream == candidate

    private fun fail(error: Exception) {
        running = false
        if (frameScheduled) {
            choreographer.removeFrameCallback(this)
            frameScheduled = false
        }
        stateValue = State.FAILED
        lastPlaybackError = error.javaClass.simpleName + ": " + (error.message ?: "no message")
        val failingSource = source
        val failingDrm = failingSource?.currentVideoDrmSession
        val failingLease = surfaceLease
        failingSource?.let { lastSourceState = it.state.name }
        failingDrm?.let { lastDrmSessionState = it.state.toString() }
        failingSource?.currentVideoFormat?.let { format ->
            lastVideoMime = format.sampleMimeType
            lastVideoSize = "${format.width}x${format.height}"
        }
        decoder?.codecName?.let { lastCodecName = it }
        failingLease?.let { lease ->
            lastSurfaceGeneration = lease.generation
            lastSurfaceWidth = lease.width
            lastSurfaceHeight = lease.height
        }
        AppLogger.e("ProtectedCmafRuntime", "FEED_FAILURE_DIAGNOSTICS feed=${stream.id} " +
            "error=${error.javaClass.simpleName}:${error.message} sourceState=${failingSource?.state} " +
            "drmState=${failingDrm?.state} keysReady=${failingDrm?.state == DrmSession.STATE_OPENED_WITH_KEYS} " +
            "mime=${failingSource?.currentVideoFormat?.sampleMimeType} " +
            "codec=${decoder?.codecName} queuedSamples=$queuedSampleCount " +
            "lastSamplePtsUs=${lastSamplePtsUs ?: -1L} renderedFrames=${decoder?.renderedFrameCount ?: 0L} " +
            "firstFrameAtNs=${decoder?.firstFrameRenderedAtNs ?: -1L} " +
            "lastRenderedPtsUs=${lastRenderedPtsUs ?: -1L} " +
            "surfaceGeneration=${failingLease?.generation ?: -1L} surfaceValid=${failingLease?.surface?.isValid ?: false} " +
            "surfaceSecure=${failingLease?.secureFlagRequested ?: false}")
        runCatching { decoder?.close() }
        decoder = null
        pendingSample = null
        runCatching { source?.close() }
        source = null
        AppLogger.e("ProtectedCmafRuntime", "FEED_FAILED id=${stream.id} message=${error.message}", error)
        publish("FAILED: ${error.message ?: error.javaClass.simpleName}; no player fallback")
    }

    private fun checkThread() {
        check(Thread.currentThread() === ownerThread && Looper.myLooper() == playbackLooper) {
            "Protected feed runtime must run on its playback looper"
        }
    }

    override fun close() {
        if (stateValue == State.CLOSED) return
        checkThread()
        running = false
        if (frameScheduled) {
            choreographer.removeFrameCallback(this)
            frameScheduled = false
        }
        stateValue = State.CLOSED
        runCatching { decoder?.close() }
        decoder = null
        pendingSample = null
        runCatching { source?.close() }
        source = null
        surfaceLease = null
        publish("CLOSED")
    }
}

/**
 * Shared presentation-time mapping for feeds using the same session timeline.
 *
 * This aligns decoder release timestamps when feed PTS values share a compatible timebase. It is
 * not a substitute for measuring F1 live-edge/PTS offsets; those remain part of device acceptance.
 */
internal class ProtectedPresentationClock {
    var masterFeedId: String = ""
        private set
    private var basePresentationTimeUs: Long? = null
    private var baseElapsedRealtimeNs: Long? = null
    private var playbackSpeed = 1f
    private val offsetsUs = linkedMapOf<String, Long>()
    private val explicitOffsetFeedIds = linkedSetOf<String>()
    private val calibratedFeedIds = linkedSetOf<String>()

    val isReady: Boolean get() = basePresentationTimeUs != null && baseElapsedRealtimeNs != null

    fun setMaster(feedId: String, channelOffsetsMs: Map<String, Long> = emptyMap()) {
        require(feedId.isNotBlank())
        if (masterFeedId != feedId) {
            masterFeedId = feedId
            basePresentationTimeUs = null
            baseElapsedRealtimeNs = null
            offsetsUs.clear()
            explicitOffsetFeedIds.clear()
            calibratedFeedIds.clear()
        }
        val incomingIds = channelOffsetsMs.keys
        (explicitOffsetFeedIds - incomingIds).forEach { id ->
            explicitOffsetFeedIds.remove(id)
            offsetsUs.remove(id)
            calibratedFeedIds.remove(id)
        }
        channelOffsetsMs.forEach { (id, offsetMs) ->
            offsetsUs[id] = offsetMs * 1_000L
            explicitOffsetFeedIds += id
            calibratedFeedIds.remove(id)
        }
    }

    /**
     * Aligns a follower's first rendered PTS to the master clock when no authoritative channel
     * offset is available. Repeated viewport updates must not erase this calibration.
     */
    fun calibrateFollowerOffset(feedId: String, presentationTimeUs: Long, renderTimeNs: Long): Long? {
        if (feedId == masterFeedId || feedId in explicitOffsetFeedIds || feedId in calibratedFeedIds) return null
        val basePts = basePresentationTimeUs ?: return null
        val baseNs = baseElapsedRealtimeNs ?: return null
        val expectedPtsUs = basePts + (renderTimeNs - baseNs) / 1_000L
        val offsetUs = presentationTimeUs - expectedPtsUs
        offsetsUs[feedId] = offsetUs
        calibratedFeedIds += feedId
        return offsetUs
    }

    data class DriftObservation(val driftUs: Long, val correctionUs: Long, val hardResync: Boolean)

    fun resetEpoch() {
        basePresentationTimeUs = null
        baseElapsedRealtimeNs = null
    }

    fun seekToPositionUs(positionUs: Long) {
        require(positionUs >= 0L)
        basePresentationTimeUs = positionUs
        baseElapsedRealtimeNs = SystemClock.elapsedRealtimeNanos() + STARTUP_LEAD_NS
    }

    fun setPlaybackSpeed(speed: Float) {
        require(speed in MIN_PLAYBACK_SPEED..MAX_PLAYBACK_SPEED) { "Playback speed is outside the supported range" }
        if (isReady) {
            val basePts = requireNotNull(basePresentationTimeUs)
            val baseNs = requireNotNull(baseElapsedRealtimeNs)
            val elapsedUs = ((SystemClock.elapsedRealtimeNanos() - baseNs) / 1_000.0 * playbackSpeed).toLong()
            basePresentationTimeUs = (basePts + elapsedUs).coerceAtLeast(0L)
            baseElapsedRealtimeNs = SystemClock.elapsedRealtimeNanos() + STARTUP_LEAD_NS
        }
        playbackSpeed = speed
    }

    fun establish(masterPresentationTimeUs: Long) {
        if (isReady) return
        check(masterFeedId.isNotBlank()) { "Master feed must be selected before establishing the clock" }
        basePresentationTimeUs = masterPresentationTimeUs
        baseElapsedRealtimeNs = SystemClock.elapsedRealtimeNanos() + STARTUP_LEAD_NS
    }

    fun observeRenderedFrame(
        feedId: String,
        presentationTimeUs: Long,
        renderTimeNs: Long,
        isLive: Boolean
    ): DriftObservation {
        val basePts = basePresentationTimeUs ?: return DriftObservation(0L, 0L, false)
        val baseNs = baseElapsedRealtimeNs ?: return DriftObservation(0L, 0L, false)
        if (feedId == masterFeedId) return DriftObservation(0L, 0L, false)
        val offsetUs = offsetsUs[feedId] ?: 0L
        val expectedPtsUs = basePts + (renderTimeNs - baseNs) / 1_000L + offsetUs
        val driftUs = presentationTimeUs - expectedPtsUs
        val hardThresholdUs = if (isLive) LIVE_HARD_SEEK_THRESHOLD_US else VOD_HARD_SEEK_THRESHOLD_US
        if (kotlin.math.abs(driftUs) >= hardThresholdUs) {
            return DriftObservation(driftUs, 0L, true)
        }
        val correctionUs = if (kotlin.math.abs(driftUs) >= DRIFT_TOLERANCE_US) {
            (-driftUs * PLAYBACK_RATE_CORRECTION).toLong()
        } else {
            0L
        }
        if (correctionUs != 0L) offsetsUs[feedId] = offsetUs + correctionUs
        return DriftObservation(driftUs, correctionUs, false)
    }

    fun presentationTimeNs(feedId: String, presentationTimeUs: Long): Long {
        val basePts = basePresentationTimeUs ?: throw IOException("Main-feed presentation clock is not ready")
        val baseNs = baseElapsedRealtimeNs ?: throw IOException("Main-feed presentation clock is not ready")
        val offsetUs = offsetsUs[feedId] ?: 0L
        val targetNs = baseNs + ((presentationTimeUs - basePts - offsetUs) * 1_000.0 / playbackSpeed).toLong()
        return maxOf(targetNs, SystemClock.elapsedRealtimeNanos() + MIN_PRESENTATION_LEAD_NS)
    }

    /** Current feed-local loading position derived from the shared main-feed clock. */
    fun positionUsFor(feedId: String): Long {
        val basePts = basePresentationTimeUs ?: return 0L
        val baseNs = baseElapsedRealtimeNs ?: return 0L
        val elapsedUs = ((SystemClock.elapsedRealtimeNanos() - baseNs) / 1_000.0 * playbackSpeed).toLong()
        return (basePts + elapsedUs + (offsetsUs[feedId] ?: 0L)).coerceAtLeast(0L)
    }

    private companion object {
        // One shared timestamp-scheduling margin, not a per-feed startup stagger.
        const val STARTUP_LEAD_NS = 5_000_000L
        const val MIN_PRESENTATION_LEAD_NS = 1_000_000L
        const val DRIFT_TOLERANCE_US = 50_000L
        const val VOD_HARD_SEEK_THRESHOLD_US = 500_000L
        const val LIVE_HARD_SEEK_THRESHOLD_US = 1_500_000L
        const val PLAYBACK_RATE_CORRECTION = 0.05
        const val MIN_PLAYBACK_SPEED = 0.25f
        const val MAX_PLAYBACK_SPEED = 2.0f
    }
}
