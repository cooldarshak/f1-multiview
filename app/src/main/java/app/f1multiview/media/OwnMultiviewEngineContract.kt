package app.f1multiview.media

/**
 * Provider-neutral contract for our own multiview pipeline.
 *
 * These types deliberately contain no TME/Tiledmedia metadata. A FeedDescriptor describes
 * one authorized input; it does not imply that the feed is already a spatial tile or that
 * it can be decoded by the same codec instance as another feed.
 */
data class FeedDescriptor(
    val id: String,
    val mediaUri: String,
    val mimeType: String? = null,
    val codecs: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    val isLive: Boolean = false,
    val timelineGroup: String? = null
) {
    init {
        require(id.isNotBlank()) { "Feed id must not be blank" }
        require(mediaUri.isNotBlank()) { "Feed media URI must not be blank" }
        require(width == null || width > 0) { "Feed width must be positive when supplied" }
        require(height == null || height > 0) { "Feed height must be positive when supplied" }
    }
}

/** Normalized output rectangle. Coordinates and dimensions are in the 0..1 range. */
data class FeedViewport(
    val feedId: String,
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
    val zIndex: Int = 0
) {
    init {
        require(feedId.isNotBlank()) { "Viewport feed id must not be blank" }
        require(x.isFinite() && y.isFinite() && width.isFinite() && height.isFinite()) {
            "Viewport coordinates must be finite"
        }
        require(x >= 0f && y >= 0f && width > 0f && height > 0f) {
            "Viewport origin must be non-negative and dimensions positive"
        }
        require(x + width <= 1.0001f && y + height <= 1.0001f) {
            "Viewport must fit within normalized output bounds"
        }
    }
}

data class ViewportLayout(
    val id: String,
    val viewports: List<FeedViewport>
) {
    init {
        require(id.isNotBlank()) { "Layout id must not be blank" }
        require(viewports.map { it.feedId }.distinct().size == viewports.size) {
            "A layout can contain at most one viewport per feed"
        }
    }
}

enum class MultiviewEnginePhase {
    IDLE,
    RESOLVING,
    PREPARING,
    READY,
    PLAYING,
    BUFFERING,
    DEGRADED,
    FAILED,
    RELEASED
}

data class FeedEngineStatus(
    val feedId: String,
    val phase: MultiviewEnginePhase,
    val firstFrameRendered: Boolean = false,
    val bufferedDurationMs: Long = 0L,
    val droppedFrames: Long = 0L,
    val errorCode: String? = null
) {
    init {
        require(feedId.isNotBlank()) { "Feed status id must not be blank" }
        require(bufferedDurationMs >= 0L) { "Buffered duration cannot be negative" }
        require(droppedFrames >= 0L) { "Dropped frame count cannot be negative" }
    }
}

/**
 * Shared session clock. All feed positions are expressed in the same millisecond timebase.
 * For live streams, liveEdgePositionMs is optional because not every manifest exposes a
 * trustworthy live-edge timestamp.
 */
data class SessionTimeline(
    val masterFeedId: String,
    val positionMs: Long,
    val isLive: Boolean,
    val isPlaying: Boolean,
    val masterBuffering: Boolean = false,
    val liveEdgePositionMs: Long? = null
) {
    init {
        require(masterFeedId.isNotBlank()) { "Master feed id must not be blank" }
        require(positionMs >= 0L) { "Timeline position cannot be negative" }
        require(liveEdgePositionMs == null || liveEdgePositionMs >= 0L) {
            "Live edge position cannot be negative"
        }
    }
}
