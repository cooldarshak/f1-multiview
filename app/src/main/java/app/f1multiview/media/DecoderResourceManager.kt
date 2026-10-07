package app.f1multiview.media

import app.f1multiview.core.playback.Quality
import app.f1multiview.model.StreamKind
import app.f1multiview.model.StreamSource

/**
 * Central decoder/resource allocator for multiview.
 *
 * A logical feed may exist without holding a decoder lease. A lease represents one
 * physical Media3 video playback pipeline. This distinction is the foundation for
 * later decoder reuse, viewport scheduling and >4 logical views.
 */
class DecoderResourceManager(
    private val maxVideoDecoders: Int = 4
) {
    data class DecoderLease(
        val slotId: Int,
        val feedId: String,
        val priority: Int,
        val isReference: Boolean
    )

    private data class Request(
        val feedId: String,
        val kind: StreamKind,
        val priority: Int,
        val isReference: Boolean,
        val quality: Quality
    )

    private val leases = linkedMapOf<String, DecoderLease>()
    private val requests = linkedMapOf<String, Request>()

    fun request(
        stream: StreamSource,
        isReference: Boolean = false,
        quality: Quality = Quality.AUTO
    ): DecoderLease? {
        val priority = priorityFor(stream.kind, isReference)
        requests[stream.id] = Request(
            feedId = stream.id,
            kind = stream.kind,
            priority = priority,
            isReference = isReference,
            quality = quality
        )

        leases[stream.id]?.let { return it }

        val usedSlots = leases.values.map { it.slotId }.toSet()
        val freeSlot = (0 until maxVideoDecoders).firstOrNull { it !in usedSlots }
        if (freeSlot != null) {
            return DecoderLease(freeSlot, stream.id, priority, isReference).also {
                leases[stream.id] = it
            }
        }

        // Never evict an active reference feed. For a full decoder budget, prefer
        // retaining the highest-priority feeds and leave the new logical feed
        // decoderless until the viewport/resource scheduler asks again.
        val victim = leases.values
            .filterNot { it.isReference }
            .minWithOrNull(compareBy<DecoderLease> { it.priority }.thenBy { it.slotId })

        if (victim != null && priority > victim.priority) {
            leases.remove(victim.feedId)
            return DecoderLease(victim.slotId, stream.id, priority, isReference).also {
                leases[stream.id] = it
            }
        }

        return null
    }

    fun release(feedId: String) {
        leases.remove(feedId)
        requests.remove(feedId)
    }

    fun updateReference(feedId: String?) {
        leases.replaceAll { id, lease ->
            lease.copy(
                isReference = id == feedId,
                priority = requests[id]?.let { priorityFor(it.kind, id == feedId) } ?: lease.priority
            )
        }
    }

    fun hasLease(feedId: String): Boolean = leases.containsKey(feedId)

    fun lease(feedId: String): DecoderLease? = leases[feedId]

    fun activeLeases(): List<DecoderLease> = leases.values.toList()

    fun availableSlots(): Int = maxVideoDecoders - leases.size

    fun capacity(): Int = maxVideoDecoders

    fun reset() {
        leases.clear()
        requests.clear()
    }

    private fun priorityFor(kind: StreamKind, isReference: Boolean): Int {
        if (isReference) return 100
        return when (kind) {
            StreamKind.WORLD -> 90
            StreamKind.DRIVER -> 80
            StreamKind.INTERNATIONAL -> 70
            StreamKind.TIMING, StreamKind.TRACK, StreamKind.TRACK_MAP, StreamKind.F1_DASH -> 0
            else -> 60
        }
    }
}
