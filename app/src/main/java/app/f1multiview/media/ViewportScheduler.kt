package app.f1multiview.media

import app.f1multiview.model.StreamKind
import app.f1multiview.model.StreamSource

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
        return streams.asSequence()
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
            .distinctBy { it.id }
            .toList()
    }
}
