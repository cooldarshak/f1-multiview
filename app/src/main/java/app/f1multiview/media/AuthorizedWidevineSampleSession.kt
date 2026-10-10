package app.f1multiview.media

import android.os.Looper
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.drm.DefaultDrmSessionManager
import androidx.media3.exoplayer.drm.DrmSessionEventListener
import app.f1multiview.model.StreamSource

/**
 * Owns the DRM-session-manager lifecycle used by the app-owned CMAF SampleQueue boundary.
 *
 * Open on the playback thread and close only after the extractor/output queues are no longer used.
 * This owns a distinct manager; it must not share the ExoPlayer-owned manager instance.
 */
@OptIn(UnstableApi::class)
internal class AuthorizedWidevineSampleSession private constructor(
    val sampleOutput: CmafSampleQueueOutput,
    private val drmSessionManager: DefaultDrmSessionManager
) : AutoCloseable {
    private var closed = false

    override fun close() {
        if (closed) return
        closed = true
        try {
            sampleOutput.close()
        } finally {
            drmSessionManager.release()
        }
    }

    companion object {
        /**
         * Returns null when the authorized response has no license endpoint. Protected callers
         * must treat null as a hard stop and must not retry through a clear or non-DRM path.
         */
        fun open(
            stream: StreamSource,
            playbackLooper: Looper
        ): AuthorizedWidevineSampleSession? {
            require(DrmProtectionPolicy.requiresProtectedOutput(stream)) {
                "Authorized Widevine sample sessions are only for protected feeds"
            }
            val manager = AuthorizedWidevineDrmSessionFactory.createSessionManager(stream) ?: return null
            try {
                manager.setPlayer(playbackLooper, PlayerId("f1-multiview-cmaf"))
                manager.prepare()
                val dispatcher = DrmSessionEventListener.EventDispatcher()
                val output = CmafSampleQueueOutput(
                    drmSessionManager = manager,
                    drmEventDispatcher = dispatcher
                )
                return AuthorizedWidevineSampleSession(output, manager)
            } catch (failure: Throwable) {
                manager.release()
                throw failure
            }
        }
    }
}
