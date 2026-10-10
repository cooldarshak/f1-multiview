package app.f1multiview.media

/**
 * Production gate for the non-negotiable one-coded-stream/one-decoder multiview architecture.
 * Separate feed URLs are not a substitute for a single HEVC tiled bitstream.
 */
internal object SingleStreamTiledPlaybackPolicy {
    data class Decision(val allowed: Boolean, val reason: String)

    fun evaluate(
        visibleVideoFeedCount: Int,
        descriptor: AppOwnedTiledStreamDescriptor?
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
        return Decision(true, "authorized single-stream tiled input validated")
    }
}
