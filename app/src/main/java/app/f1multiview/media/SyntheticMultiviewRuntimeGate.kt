package app.f1multiview.media

/** Conservative acceptance gate for the debug-only, clear synthetic three-feed proof. */
internal object SyntheticMultiviewRuntimeGate {
    val expectedFeedIds = listOf("LEFT", "CENTER", "RIGHT")
    enum class State { WARMING, PASS, FAIL }

    data class TextureMetrics(
        val feedId: String,
        val textureUpdates: Long,
        val firstFrameLatencyMs: Long?,
        val lastUpdateAgeMs: Long?,
        val maxUpdateGapMs: Long
    )

    data class FeedMetrics(
        val feedId: String,
        val codecName: String,
        val decoderOutputFrames: Long,
        val textureUpdates: Long,
        val firstFrameLatencyMs: Long?,
        val lastUpdateAgeMs: Long?,
        val maxUpdateGapMs: Long,
        val error: String? = null
    )

    data class DrawMetrics(
        val drawCalls: Long,
        val drawRateFps: Double?,
        val maxDrawGapMs: Long
    )

    data class Verdict(val state: State, val summary: String, val failures: List<String> = emptyList())

    fun evaluate(elapsedMs: Long, feeds: List<FeedMetrics>, compositor: DrawMetrics): Verdict {
        require(elapsedMs >= 0L) { "Elapsed runtime cannot be negative" }
        val errors = feeds.filter { !it.error.isNullOrBlank() }
        if (errors.isNotEmpty()) {
            return Verdict(
                State.FAIL,
                "One or more decoder pipelines reported an error",
                errors.map { "${it.feedId}: ${it.error}" }
            )
        }
        if (elapsedMs < 15_000L) {
            return Verdict(State.WARMING, "Collecting at least 15 seconds of three-feed runtime measurements")
        }

        val failures = mutableListOf<String>()
        val ids = feeds.map { it.feedId }
        if (ids.size != expectedFeedIds.size || ids.toSet() != expectedFeedIds.toSet()) {
            failures += "Expected exactly LEFT, CENTER, and RIGHT decoder measurements; got ${ids.joinToString()}"
        }
        expectedFeedIds.forEach { id ->
            val feed = feeds.firstOrNull { it.feedId == id } ?: return@forEach
            if (feed.codecName == "pending" || feed.codecName.isBlank()) failures += "$id decoder did not configure"
            if (feed.decoderOutputFrames < 30L) failures += "$id decoder output below 30 frames (${feed.decoderOutputFrames})"
            if (feed.textureUpdates < 30L) failures += "$id SurfaceTexture updates below 30 (${feed.textureUpdates})"
            if (feed.firstFrameLatencyMs == null) failures += "$id has no observed first texture frame"
            if (feed.lastUpdateAgeMs == null || feed.lastUpdateAgeMs > 1_500L) failures += "$id texture is stale (age=${feed.lastUpdateAgeMs}ms)"
            if (feed.maxUpdateGapMs > 2_000L) failures += "$id texture update gap exceeded 2000ms (${feed.maxUpdateGapMs}ms)"
        }
        if (compositor.drawCalls < 60L) failures += "GLES draw count below 60 (${compositor.drawCalls})"
        if (compositor.drawRateFps == null || compositor.drawRateFps < 8.0) {
            failures += "GLES draw cadence below 8 FPS (${compositor.drawRateFps ?: "unavailable"})"
        }
        if (compositor.maxDrawGapMs > 2_000L) failures += "GLES draw gap exceeded 2000ms (${compositor.maxDrawGapMs}ms)"
        return if (failures.isEmpty()) {
            Verdict(State.PASS, "Three decoders and all three compositor inputs sustained measurable output")
        } else {
            Verdict(State.FAIL, "Synthetic three-feed runtime acceptance criteria were not met", failures)
        }
    }
}
