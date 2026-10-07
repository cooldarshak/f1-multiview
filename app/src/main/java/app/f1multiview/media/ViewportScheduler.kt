package app.f1multiview.media

import app.f1multiview.model.StreamKind
import app.f1multiview.model.StreamSource

/**
 * Chooses which logical feeds are allowed to consume physical decoder slots.
 *
 * Logical feeds may outnumber the decoder budget. Selection is deterministic:
 * reference feed first, then visible feeds in viewport order, then already-active
 * feeds to avoid unnecessary churn. The scheduler never changes the decoder budget.
 */
class ViewportScheduler(private val maxDecoders: Int) {
    data class Candidate(
        val stream: StreamSource,
        val visible: Boolean,
        val reference: Boolean,
        val viewportIndex: Int
    )

    fun schedule(
        streams: List<StreamSource>,
        visibleIds: Set<String>,
        referenceId: String?,
        activeIds: Set<String>
    ): List<StreamSource> {
        val candidates = streams.asSequence()
            .filter { it.url != null }
            .filter { it.kind !in setOf(StreamKind.TRACK_MAP, StreamKind.F1_DASH_DATA) }
            .mapIndexed { index, stream ->
                Candidate(
                    stream = stream,
                    visible = stream.id in visibleIds,
                    reference = stream.id == referenceId,
                    viewportIndex = if (stream.id in visibleIds) index else Int.MAX_VALUE
                )
            }
            .sortedWith(
                compareByDescending<Candidate> { it.reference }
                    .thenByDescending { it.visible }
                    .thenByDescending { it.stream.id in activeIds }
                    .thenBy { it.viewportIndex }
            )
            .take(maxDecoders)
            .map { it.stream }
            .toList()

        return candidates(streams, visibleIds, referenceId, activeIds).distinctBy { it.id }
    }

    private val scheduled: List<StreamSource>
        get() = emptyList()
}
