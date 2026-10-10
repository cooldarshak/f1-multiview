package app.f1multiview.media

import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.source.SampleQueue
import androidx.media3.exoplayer.drm.DrmSessionEventListener
import androidx.media3.exoplayer.drm.DrmSessionManager
import androidx.media3.exoplayer.upstream.DefaultAllocator
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput

/**
 * App-owned extractor sink that retains sample payloads in Media3 SampleQueues.
 *
 * Without a manager, queues expose parsed encryption metadata without acquiring DRM sessions.
 * With a manager and dispatcher, SampleQueue uses that manager for per-format session references.
 * This class does not decrypt samples, configure secure decoders, or authorize protected playback.
 */
@OptIn(UnstableApi::class)
internal class CmafSampleQueueOutput(
    allocationSize: Int = DEFAULT_ALLOCATION_SIZE,
    private val drmSessionManager: DrmSessionManager? = null,
    private val drmEventDispatcher: DrmSessionEventListener.EventDispatcher? = null
) : ExtractorOutput, AutoCloseable {
    private val allocator = DefaultAllocator(/* trimOnReset= */ true, allocationSize)
    private val queues = linkedMapOf<Int, SampleQueue>()
    private val trackTypes = linkedMapOf<Int, Int>()

    val sampleQueues: Map<Int, SampleQueue>
        get() = queues.toMap()

    val sampleTrackTypes: Map<Int, Int>
        get() = trackTypes.toMap()

    /** True only when queues were created with a caller-owned DRM session manager. */
    val isDrmManaged: Boolean
        get() = drmSessionManager != null

    var seekMap: SeekMap? = null
        private set

    private var closed = false

    init {
        require(allocationSize > 0) { "Allocation size must be positive" }
        require((drmSessionManager == null) == (drmEventDispatcher == null)) {
            "A DRM session manager and its event dispatcher must be supplied together"
        }
    }

    override fun track(id: Int, type: Int): TrackOutput {
        check(!closed) { "Extractor output is already closed" }
        trackTypes[id] = type
        return queues.getOrPut(id) {
            val manager = drmSessionManager
            val dispatcher = drmEventDispatcher
            if (manager == null || dispatcher == null) {
                SampleQueue.createWithoutDrm(allocator)
            } else {
                // The caller owns manager setup/lifecycle (setPlayer, prepare, release).
                // This queue acquires/releases per-format sessions through the documented API.
                SampleQueue.createWithDrm(allocator, manager, dispatcher)
            }
        }
    }

    override fun endTracks() = Unit

    override fun seekMap(seekMap: SeekMap) {
        this.seekMap = seekMap
    }

    override fun close() {
        if (closed) return
        closed = true
        queues.values.forEach(SampleQueue::release)
        queues.clear()
        trackTypes.clear()
        allocator.trim()
    }

    private companion object {
        const val DEFAULT_ALLOCATION_SIZE = 64 * 1024
    }
}
