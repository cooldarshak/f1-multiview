package app.f1multiview.media

import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.source.SampleQueue
import androidx.media3.exoplayer.upstream.DefaultAllocator
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput

/**
 * App-owned extractor sink that retains sample payloads in Media3 SampleQueues.
 *
 * This is an extraction boundary only. It deliberately creates queues without DRM session
 * management: the queues can expose parsed encryption metadata, but this class does not acquire a
 * Widevine session, decrypt samples, configure secure decoders, or authorize protected playback.
 */
@OptIn(UnstableApi::class)
internal class CmafSampleQueueOutput(
    allocationSize: Int = DEFAULT_ALLOCATION_SIZE
) : ExtractorOutput, AutoCloseable {
    private val allocator = DefaultAllocator(/* trimOnReset= */ true, allocationSize)

    val tracks: MutableMap<Int, TrackOutput> = linkedMapOf()
        private set

    val sampleQueues: Map<Int, SampleQueue>
        get() = tracks.mapValues { (_, output) ->
            check(output is SampleQueue) { "Unexpected non-SampleQueue track output" }
            output
        }

    var seekMap: SeekMap? = null
        private set

    private var closed = false

    init {
        require(allocationSize > 0) { "Allocation size must be positive" }
    }

    override fun track(id: Int, type: Int): TrackOutput {
        check(!closed) { "Extractor output is already closed" }
        return tracks.getOrPut(id) { SampleQueue.createWithoutDrm(allocator) }
    }

    override fun endTracks() = Unit

    override fun seekMap(seekMap: SeekMap) {
        this.seekMap = seekMap
    }

    override fun close() {
        if (closed) return
        closed = true
        sampleQueues.values.forEach(SampleQueue::release)
        tracks.clear()
        allocator.trim()
    }

    private companion object {
        const val DEFAULT_ALLOCATION_SIZE = 64 * 1024
    }
}
