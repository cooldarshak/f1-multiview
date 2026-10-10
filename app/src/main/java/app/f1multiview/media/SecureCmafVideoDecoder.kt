package app.f1multiview.media

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaCrypto
import android.media.MediaFormat
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import androidx.media3.decoder.DecoderInputBuffer
import androidx.media3.exoplayer.drm.DrmSession
import androidx.media3.exoplayer.drm.FrameworkCryptoConfig
import java.io.IOException
import java.nio.ByteBuffer

internal data class SecureCodecCandidate(
    val name: String,
    val isEncoder: Boolean,
    val isHardwareAccelerated: Boolean,
    val supportsMimeType: Boolean,
    val supportsSecurePlayback: Boolean
)

/** Fail-closed selection policy: never choose software or non-secure decoder candidates. */
internal object SecureCodecCandidatePolicy {
    fun select(candidates: List<SecureCodecCandidate>): List<String> =
        candidates.asSequence()
            .filter {
                it.name.isNotBlank() &&
                    !it.isEncoder &&
                    it.isHardwareAccelerated &&
                    it.supportsMimeType &&
                    it.supportsSecurePlayback
            }
            .map(SecureCodecCandidate::name)
            .distinct()
            .toList()
}

/**
 * App-owned protected video decoder. It accepts already-demuxed Media3 samples but never delegates
 * protected video decoding to an ExoPlayer or reads decoded pixels back into GLES/CPU memory.
 *
 * The caller must own the SampleQueue, keep its DRM manager alive, claim a protected SurfaceView
 * lease, and schedule pump/drain calls on one playback thread.
 */
@OptIn(UnstableApi::class)
internal class SecureCmafVideoDecoder(
    private val feedId: String,
    private val format: Format,
    private val drmSession: DrmSession,
    private val surfaceLease: ProtectedSurfaceLease,
    private val isLeaseCurrent: (ProtectedSurfaceLease) -> Boolean,
    private val releaseTimeNsForPresentationTimeUs: (Long) -> Long
) : AutoCloseable {
    data class DrainResult(
        val outputBuffersReleased: Int,
        val outputFormat: MediaFormat?,
        val outputEnded: Boolean
    )

    private val ownerThread = Thread.currentThread()
    private var codec: MediaCodec? = null
    private var mediaCrypto: MediaCrypto? = null
    private var sessionAcquired = false
    private var closed = false
    private var inputEnded = false
    private var outputEnded = false
    private var selectedCodecName: String? = null
    private var outputFormat: MediaFormat? = null
    private val bufferInfo = MediaCodec.BufferInfo()

    val codecName: String?
        get() = selectedCodecName

    val isInputEnded: Boolean
        get() = inputEnded

    val isOutputEnded: Boolean
        get() = outputEnded

    init {
        checkPlaybackThread()
        if (!surfaceLease.secureFlagRequested) {
            throw IOException("Protected decoder requires a secure-flag-requested SurfaceView lease")
        }
        validateSurfaceLease()
        if (drmSession.state == DrmSession.STATE_ERROR) {
            throw IOException("Widevine session is in an error state")
        }
        if (drmSession.state != DrmSession.STATE_OPENED_WITH_KEYS) {
            throw IOException("Widevine session has not reached STATE_OPENED_WITH_KEYS")
        }
        drmSession.acquire(/* eventDispatcher= */ null)
        sessionAcquired = true
        try {
            configureCodec()
        } catch (error: Exception) {
            close()
            throw error
        }
    }

    /**
     * Queues one sample. If MediaCodec has no free input buffer, returns false without consuming
     * the caller's DecoderInputBuffer so the caller can retry it on the next pump.
     */
    fun queueSample(sample: DecoderInputBuffer): Boolean {
        checkUsable()
        if (sample.isEndOfStream) return queueEndOfStream(sample.timeUs)
        validateSurfaceLease()
        val activeCodec = codec ?: throw IOException("Secure decoder is not configured")
        val inputIndex = activeCodec.dequeueInputBuffer(/* timeoutUs= */ 0)
        if (inputIndex < 0) return false

        try {
            val sampleData = sample.data ?: throw IOException("Sample has no payload buffer")
            val sampleSize = sampleData.position()
            if (sampleSize <= 0) throw IOException("Sample payload is empty")
            val inputBuffer = activeCodec.getInputBuffer(inputIndex)
                ?: throw IOException("Secure decoder returned no input buffer")
            if (sampleSize > inputBuffer.capacity()) {
                throw IOException("Sample payload exceeds secure decoder input capacity")
            }
            inputBuffer.clear()
            val payload = sampleData.duplicate()
            payload.flip()
            inputBuffer.put(payload)

            val flags = if ((sample.flags and C.BUFFER_FLAG_KEY_FRAME) != 0) {
                MediaCodec.BUFFER_FLAG_KEY_FRAME
            } else {
                0
            }
            if (sample.isEncrypted) {
                val cryptoInfo = sample.cryptoInfo
                if (cryptoInfo.numSubSamples <= 0 || cryptoInfo.iv.isNullOrEmpty() || cryptoInfo.key.isNullOrEmpty()) {
                    throw IOException("Encrypted sample is missing valid crypto metadata")
                }
                if (cryptoInfo.mode != C.CRYPTO_MODE_AES_CTR && cryptoInfo.mode != C.CRYPTO_MODE_AES_CBC) {
                    throw IOException("Encrypted sample uses an unsupported AES mode")
                }
                activeCodec.queueSecureInputBuffer(
                    inputIndex,
                    /* offset= */ 0,
                    cryptoInfo.getFrameworkCryptoInfo(),
                    sample.timeUs,
                    flags
                )
            } else {
                activeCodec.queueInputBuffer(inputIndex, 0, sampleSize, sample.timeUs, flags)
            }
            return true
        } catch (error: Exception) {
            close()
            throw error
        }
    }

    fun queueEndOfStream(presentationTimeUs: Long): Boolean {
        checkUsable()
        if (inputEnded) return true
        val activeCodec = codec ?: throw IOException("Secure decoder is not configured")
        val inputIndex = activeCodec.dequeueInputBuffer(/* timeoutUs= */ 0)
        if (inputIndex < 0) return false
        activeCodec.queueInputBuffer(
            inputIndex,
            /* offset= */ 0,
            /* size= */ 0,
            presentationTimeUs,
            MediaCodec.BUFFER_FLAG_END_OF_STREAM
        )
        inputEnded = true
        return true
    }

    /** Releases decoded output directly to the secure SurfaceView surface. */
    fun drainOutput(maxOutputBuffers: Int = 32): DrainResult {
        checkUsable()
        require(maxOutputBuffers > 0)
        validateSurfaceLease()
        var released = 0
        repeat(maxOutputBuffers) {
            val activeCodec = codec ?: return DrainResult(released, outputFormat, outputEnded)
            val index = activeCodec.dequeueOutputBuffer(bufferInfo, /* timeoutUs= */ 0)
            when {
                index >= 0 -> {
                    val endOfStream = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                    try {
                        if (bufferInfo.size > 0) {
                            validateSurfaceLease()
                            val releaseTimeNs = releaseTimeNsForPresentationTimeUs(bufferInfo.presentationTimeUs)
                            if (releaseTimeNs <= 0L) {
                                throw IOException("Presentation clock returned a non-positive release timestamp")
                            }
                            activeCodec.releaseOutputBuffer(index, releaseTimeNs)
                            released++
                        } else {
                            activeCodec.releaseOutputBuffer(index, /* render= */ false)
                        }
                    } catch (error: Exception) {
                        runCatching { activeCodec.releaseOutputBuffer(index, /* render= */ false) }
                        close()
                        throw error
                    }
                    if (endOfStream) outputEnded = true
                }
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> return DrainResult(released, outputFormat, outputEnded)
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> outputFormat = activeCodec.outputFormat
                index == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> Unit
                else -> return DrainResult(released, outputFormat, outputEnded)
            }
        }
        return DrainResult(released, outputFormat, outputEnded)
    }

    private fun configureCodec() {
        val mimeType = format.sampleMimeType?.takeIf { it.startsWith("video/") }
            ?: throw IOException("Protected CMAF track has no supported video MIME type")
        if (format.width <= 0 || format.height <= 0) {
            throw IOException("Protected CMAF video format has invalid dimensions")
        }
        val cryptoConfig = drmSession.cryptoConfig as? FrameworkCryptoConfig
            ?: throw IOException("Widevine session did not expose FrameworkCryptoConfig")
        if (cryptoConfig.sessionId.isEmpty()) {
            throw IOException("Widevine session has an empty framework session ID")
        }

        val candidates = discoverSecureHardwareCodecs(mimeType)
        if (candidates.isEmpty()) {
            throw IOException("No hardware decoder advertises secure playback for $mimeType")
        }
        val mediaFormat = MediaFormat.createVideoFormat(mimeType, format.width, format.height)
        format.initializationData.forEachIndexed { index, bytes ->
            mediaFormat.setByteBuffer("csd-$index", ByteBuffer.wrap(bytes))
        }
        if (format.frameRate > 0f) mediaFormat.setFloat(MediaFormat.KEY_FRAME_RATE, format.frameRate)
        if (format.maxInputSize > 0) mediaFormat.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, format.maxInputSize)

        var lastFailure: Exception? = null
        for (candidateName in candidates) {
            validateSurfaceLease()
            var candidate: MediaCodec? = null
            var candidateCrypto: MediaCrypto? = null
            var started = false
            try {
                candidateCrypto = MediaCrypto(cryptoConfig.uuid, cryptoConfig.sessionId)
                candidate = MediaCodec.createByCodecName(candidateName)
                candidate.configure(mediaFormat, surfaceLease.surface, candidateCrypto, /* flags= */ 0)
                candidate.start()
                started = true
                codec = candidate
                mediaCrypto = candidateCrypto
                selectedCodecName = candidateName
                return
            } catch (error: Exception) {
                lastFailure = error
                if (candidate != null) {
                    if (started) runCatching { candidate.stop() }
                    runCatching { candidate.release() }
                }
                runCatching { candidateCrypto?.release() }
            }
        }
        throw IOException("All secure hardware decoder candidates failed for feed=$feedId mime=$mimeType", lastFailure)
    }

    private fun discoverSecureHardwareCodecs(mimeType: String): List<String> {
        val candidates = MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos.map { info ->
            val supportsMime = info.supportedTypes.any { it.equals(mimeType, ignoreCase = true) }
            val supportsSecurePlayback = supportsMime && runCatching {
                info.getCapabilitiesForType(mimeType)
                    .isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_SecurePlayback)
            }.getOrDefault(false)
            SecureCodecCandidate(
                name = info.name,
                isEncoder = info.isEncoder,
                isHardwareAccelerated = info.isHardwareAccelerated,
                supportsMimeType = supportsMime,
                supportsSecurePlayback = supportsSecurePlayback
            )
        }
        return SecureCodecCandidatePolicy.select(candidates)
    }

    private fun validateSurfaceLease() {
        if (!surfaceLease.secureFlagRequested ||
            !surfaceLease.surface.isValid ||
            !isLeaseCurrent(surfaceLease)
        ) {
            throw IOException("Protected SurfaceView lease is stale, detached, or not secure")
        }
    }

    private fun checkPlaybackThread() {
        check(Thread.currentThread() === ownerThread) {
            "SecureCmafVideoDecoder must be used from its creating playback thread"
        }
    }

    private fun checkUsable() {
        checkPlaybackThread()
        check(!closed) { "SecureCmafVideoDecoder is closed" }
    }

    private fun releaseCodec() {
        codec?.let { active ->
            runCatching { active.stop() }
            runCatching { active.release() }
        }
        codec = null
        mediaCrypto?.let { runCatching { it.release() } }
        mediaCrypto = null
        selectedCodecName = null
    }

    override fun close() {
        if (closed) return
        checkPlaybackThread()
        closed = true
        releaseCodec()
        if (sessionAcquired) {
            runCatching { drmSession.release(/* eventDispatcher= */ null) }
            sessionAcquired = false
        }
    }
}
