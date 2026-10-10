package app.f1multiview.media

import app.f1multiview.model.StreamKind
import app.f1multiview.model.StreamSource

/**
 * Must match MultiviewFeedTile: only the track-map and F1 dash-data panels are UI-only feeds.
 * TIMING and TRACK are real video stream kinds and must enter the protected playback path.
 */
internal object ProtectedMultiviewFeedPolicy {
    private val uiOnlyKinds = setOf(StreamKind.TRACK_MAP, StreamKind.F1_DASH_DATA)

    fun isVideo(kind: StreamKind): Boolean = kind !in uiOnlyKinds

    fun visibleVideoCount(streams: List<StreamSource>, visibleIds: Set<String>): Int {
        val byId = streams.associateBy(StreamSource::id)
        return visibleIds.count { id -> byId[id]?.let { isVideo(it.kind) } == true }
    }

    fun selectedVideoStreams(streams: List<StreamSource>, visibleIds: Set<String>): List<StreamSource> =
        streams.filter { it.id in visibleIds && isVideo(it.kind) }
}
