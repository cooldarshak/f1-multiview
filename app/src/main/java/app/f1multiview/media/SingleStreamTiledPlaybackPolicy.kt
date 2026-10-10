package app.f1multiview.media

/**
 * Production gate for the non-negotiable one-coded-stream/one-decoder multiview architecture.
 * Separate feed URLs are not a substitute for a single HEVC tiled bitstream.
 */
internal object SingleStreamTiledPlaybackPolicy {
    data class Decision(val allowed: Boolean, val reason: String)

    fun evaluate(
        visibleVideoFeedCount: Int,
        descriptor: AppOwnedTiledStreamDescriptor?,
        visibleVideoFeedIds: Set<String>? = null
    ): Decision {
        if (visibleVideoFeedCount <= 1) return Decision(true, "single-feed playback")
        if (visibleVideoFeedCount !in 2..4) {
            return Decision(false, "single tiled stream supports 2, 3, or 4 visible feeds")
        }
        if (descriptor == null) {
            return Decision(false, "authorized single-stream HEVC tiled representation is not resolved")
        }
        val validation = descriptor.validate()
        if (!validation.isValid) {
            return Decision(false, "tiled stream descriptor invalid: ${validation.errors.joinToString("; ")}")
        }
        if (descriptor.tiles.size != visibleVideoFeedCount) {
            return Decision(false, "visible feed count does not match tiled stream tile count")
        }
        if (visibleVideoFeedIds != null && descriptor.tiles.map { it.sourceId }.toSet() != visibleVideoFeedIds) {
            return Decision(false, "tiled stream source IDs do not match the selected visible feeds")
        }
        return Decision(true, "authorized single-stream tiled input validated")
    }
}
