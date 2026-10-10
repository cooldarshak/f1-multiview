package app.f1multiview.media

import android.os.Looper
import android.os.SystemClock
import android.view.Choreographer
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.decoder.DecoderInputBuffer
import androidx.media3.exoplayer.drm.DrmSession
import app.f1multiview.model.StreamSource
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
    private val onStatus: (String, String) -> Unit
) : AutoCloseable, Choreographer.FrameCallback {

    enum class State { NEW, PREPARING, WAITING_FOR_SURFACE_OR_KEYS, PLAYING, PAUSED, FAILED, CLOSED }

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
    private var renderedFrameCount = 0L
    private var stateValue = State.NEW

    val state: State get() = stateValue
    val isPlaying: Boolean get() = stateValue == State.PLAYING

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
            source = AuthorizedCmafMediaSourceSession.open(stream, playbackLooper)
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
        if (activeSource.state != AuthorizedCmafMediaSourceSession.State.READY) return

        if (pendingHardResync) {
            pendingHardResync = false
            decoder?.close()
            decoder = null
            pendingSample = null
            activeSource.seekToUs(clock.positionUsFor(stream.id))
            publish("SYNC_HARD_RESYNC: follower sought to shared main-feed clock")
        }

        val oldLease = surfaceLease
        if (decoder != null && oldLease != null && !surfaceManager.isCurrentProtectedSurfaceLease(oldLease)) {
            decoder?.close()
            decoder = null
            surfaceLease = null
        }

        if (pendingSample == null && !eosQueued) {
            val result = activeSource.pump(clock.positionUsFor(stream.id))
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
                    if (stream.id == clock.masterFeedId && !clock.isReady) {
                        clock.establish(sample.timeUs)
                    }
                    pendingSample = sample
                }
                AuthorizedCmafMediaSourceSession.ReadKind.END_OF_STREAM -> {
                    pendingSample = result.sample
                    eosQueued = true
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
                }
            }
            activeDecoder.drainOutput()
        } else if (stateValue != State.PAUSED) {
            stateValue = State.WAITING_FOR_SURFACE_OR_KEYS
            publish("WAITING: Widevine keys, main-feed clock, or secure surface")
        }
    }

    private fun ensureSecureDecoder(activeSource: AuthorizedCmafMediaSourceSession) {
        if (decoder != null || !clock.isReady) return
        val format = activeSource.currentVideoFormat ?: return
        val drmSession = activeSource.currentVideoDrmSession ?: return
        if (drmSession.state == DrmSession.STATE_ERROR) {
            throw IOException("Widevine session entered an error state", drmSession.error)
        }
        if (drmSession.state != DrmSession.STATE_OPENED_WITH_KEYS) return
        var lease = surfaceLease
        if (lease == null || !surfaceManager.isCurrentProtectedSurfaceLease(lease)) {
            lease = surfaceManager.claimProtectedSurfaceForOwnDecoder(stream.id) ?: return
            surfaceLease = lease
        }
        decoder = SecureCmafVideoDecoder(
            feedId = stream.id,
            format = format,
            drmSession = drmSession,
            surfaceLease = lease,
            isLeaseCurrent = surfaceManager::isCurrentProtectedSurfaceLease,
            releaseTimeNsForPresentationTimeUs = clock::presentationTimeNs,
            onFrameRendered = { presentationTimeUs, renderTimeNs ->
                if (stateValue != State.PLAYING) {
                    stateValue = State.PLAYING
                    publish("FIRST_FRAME_RENDERED ptsUs=$presentationTimeUs renderTimeNs=$renderTimeNs")
                }
                renderedFrameCount++
                val observation = clock.observeRenderedFrame(stream.id, presentationTimeUs, renderTimeNs, stream.isLive)
                if (renderedFrameCount % 30L == 0L) {
                    AppLogger.i(
                        "ProtectedCmafSync",
                        "feed=${stream.id} renderedFrames=$renderedFrameCount driftMs=${observation.driftUs / 1000.0} correctionMs=${observation.correctionUs / 1000.0} hardResync=${observation.hardResync}"
                    )
                }
                if (observation.hardResync) pendingHardResync = true
            }
        )
        stateValue = State.WAITING_FOR_SURFACE_OR_KEYS
        publish("READY: secure hardware decoder configured")
    }

    private fun scheduleFrame() {
        if (!running || frameScheduled || stateValue == State.FAILED || stateValue == State.CLOSED) return
        frameScheduled = true
        choreographer.postFrameCallback(this)
    }

    private fun publish(message: String) {
        if (lastPublishedMessage == message) return
        lastPublishedMessage = message
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
    private val offsetsUs = linkedMapOf<String, Long>()

    val isReady: Boolean get() = basePresentationTimeUs != null && baseElapsedRealtimeNs != null

    fun setMaster(feedId: String, channelOffsetsMs: Map<String, Long> = emptyMap()) {
        require(feedId.isNotBlank())
        if (masterFeedId != feedId) {
            masterFeedId = feedId
            basePresentationTimeUs = null
            baseElapsedRealtimeNs = null
        }
        offsetsUs.clear()
        channelOffsetsMs.forEach { (id, offsetMs) -> offsetsUs[id] = offsetMs * 1_000L }
    }

    data class DriftObservation(val driftUs: Long, val correctionUs: Long, val hardResync: Boolean)

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
        val targetNs = baseNs + (presentationTimeUs - basePts - offsetUs) * 1_000L
        return maxOf(targetNs, SystemClock.elapsedRealtimeNanos() + MIN_PRESENTATION_LEAD_NS)
    }

    /** Current feed-local loading position derived from the shared main-feed clock. */
    fun positionUsFor(feedId: String): Long {
        val basePts = basePresentationTimeUs ?: return 0L
        val baseNs = baseElapsedRealtimeNs ?: return 0L
        val elapsedUs = (SystemClock.elapsedRealtimeNanos() - baseNs) / 1_000L
        return (basePts + elapsedUs + (offsetsUs[feedId] ?: 0L)).coerceAtLeast(0L)
    }

    private companion object {
        const val STARTUP_LEAD_NS = 120_000_000L
        const val MIN_PRESENTATION_LEAD_NS = 1_000_000L
        const val DRIFT_TOLERANCE_US = 50_000L
        const val VOD_HARD_SEEK_THRESHOLD_US = 500_000L
        const val LIVE_HARD_SEEK_THRESHOLD_US = 1_500_000L
        const val PLAYBACK_RATE_CORRECTION = 0.05
    }
}
