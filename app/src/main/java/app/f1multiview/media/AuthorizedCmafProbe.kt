package app.f1multiview.media

import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.decoder.DecoderInputBuffer
import androidx.media3.exoplayer.FormatHolder
import androidx.media3.exoplayer.drm.DrmSession
import app.f1multiview.model.StreamSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * Device diagnostic for the authorized DASH -> CMAF -> SampleQueue -> Widevine-session boundary.
 *
 * A PASS means a protected init segment was extracted and the Media3 DRM session reached
 * STATE_OPENED_WITH_KEYS. It does not claim secure MediaCodec configuration or video rendering.
 */
@OptIn(UnstableApi::class)
internal object AuthorizedCmafProbe {
    data class Result(
        val manifestHost: String?,
        val representationId: String?,
        val width: Int,
        val height: Int,
        val bytesFetched: Int,
        val trackCount: Int,
        val drmSessionStates: Map<Int, Int>,
        val widevineKeysReady: Boolean
    )

    suspend fun run(
        stream: StreamSource,
        maxWidth: Int = 1280,
        maxHeight: Int = 720,
        playbackLooper: Looper = Looper.getMainLooper()
    ): Result {
        if (!DrmProtectionPolicy.requiresProtectedOutput(stream)) {
            throw IOException("Selected feed is not marked as DRM protected")
        }
        if (DrmProtectionPolicy.missingLicenseEndpoint(stream)) {
            throw IOException("No authorized Widevine license endpoint is configured")
        }

        val session = withContext(Dispatchers.Main.immediate) {
            AuthorizedWidevineSampleSession.open(stream, playbackLooper)
        } ?: throw IOException("Authorized Widevine session could not be initialized")

        try {
            val pipelineResult = withContext(Dispatchers.IO) {
                AuthorizedDashCmafPipeline(
                    stream = stream,
                    maxWidth = maxWidth,
                    maxHeight = maxHeight
                ).prepareFirstSegment(session.sampleOutput)
            }

            val trackSessions = withContext(Dispatchers.Main.immediate) {
                session.sampleOutput.sampleQueues.mapNotNull { (trackId, queue) ->
                    val holder = FormatHolder()
                    val buffer = DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_NORMAL)
                    val result = queue.read(holder, buffer, 0, /* loadingFinished= */ false)
                    if (result == C.RESULT_FORMAT_READ) {
                        holder.drmSession?.let { trackId to it }
                    } else {
                        null
                    }
                }
            }
            if (trackSessions.isEmpty()) {
                throw IOException("No DRM session was attached to the extracted track formats")
            }

            fun snapshotStates(): Map<Int, Int> =
                trackSessions.associate { (trackId, drmSession) -> trackId to drmSession.state }

            var states = snapshotStates()
            var attempts = 0
            while (
                attempts < MAX_KEY_WAIT_ATTEMPTS &&
                states.values.any { it != DrmSession.STATE_OPENED_WITH_KEYS && it != DrmSession.STATE_ERROR }
            ) {
                delay(KEY_POLL_INTERVAL_MS)
                states = snapshotStates()
                attempts++
            }

            return Result(
                manifestHost = pipelineResult.resolvedManifestUri.host,
                representationId = pipelineResult.videoPlan.format.id,
                width = pipelineResult.videoPlan.format.width,
                height = pipelineResult.videoPlan.format.height,
                bytesFetched = pipelineResult.extraction.bytesFetched,
                trackCount = pipelineResult.extraction.trackCount,
                drmSessionStates = states,
                widevineKeysReady = states.isNotEmpty() &&
                    states.values.all { it == DrmSession.STATE_OPENED_WITH_KEYS }
            )
        } finally {
            withContext(NonCancellable + Dispatchers.Main.immediate) {
                session.close()
            }
        }
    }

    private const val MAX_KEY_WAIT_ATTEMPTS = 100
    private const val KEY_POLL_INTERVAL_MS = 100L
}
