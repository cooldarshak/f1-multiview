package app.f1multiview.media

/**
 * OpenTME pipeline contracts.
 *
 * This layer is deliberately independent of Media3. It models the work that must
 * happen before a single Android decoder is fed:
 *
 * TME-1: epoch/CMAF segment alignment
 * TME-2: dynamic logical tile selection without decoder recreation
 * TME-3: bounded-latency/performance accounting
 * TME-4: F1 TME metadata -> tile source mapping
 * TME-5: output layout/compositor mapping
 * TME-6: physical decoder ownership/telemetry
 *
 * It does NOT claim that independent F1 URLs are already mergeable by Media3.
 * A native merger implementation must satisfy TmeBitstreamMerger before the
 * backend can become playable.
 */

data class TmeTileSource(
    val feedId: String,
    val url: String,
    val tileIndex: Int,
    val row: Int?,
    val column: Int,
    val tileWidth: Int,
    val tileHeight: Int,
    val decoderConfig: ByteArray? = null,
    val requestHeaders: Map<String, String> = emptyMap()
)

data class TmeCmafSegmentKey(
    val sequence: Long,
    val epochStartUs: Long,
    val durationUs: Long
)

data class TmeTileSegment(
    val feedId: String,
    val key: TmeCmafSegmentKey,
    val payload: ByteArray,
    val keyFrame: Boolean = false
)

data class TmeAlignedSegment(
    val key: TmeCmafSegmentKey,
    val tiles: List<TmeTileSegment>
) {
    val complete: Boolean get() = tiles.isNotEmpty() &&
        tiles.map { it.feedId }.distinct().size == tiles.size
}

class TmeSegmentSynchronizer(
    private val segmentTimeoutUs: Long = 1_500_000L,
    private val clockUs: () -> Long = {
        System.nanoTime() / 1_000L
    }
) {
    private data class Bucket(
        val firstSeenElapsedUs: Long,
        val tiles: MutableMap<String, TmeTileSegment>
    )

    private val pending = linkedMapOf<TmeCmafSegmentKey, Bucket>()

    /**
     * Media epoch timestamps are not wall-clock time. Expiration therefore uses a
     * monotonic arrival clock, while the segment key remains the media-time identity.
     */
    fun offer(
        segment: TmeTileSegment,
        expectedFeedIds: Set<String>? = null,
        nowElapsedUs: Long = clockUs()
    ): TmeAlignedSegment? {
        val bucket = pending.getOrPut(segment.key) {
            Bucket(nowElapsedUs, linkedMapOf())
        }
        bucket.tiles[segment.feedId] = segment

        if (expectedFeedIds != null && expectedFeedIds.isNotEmpty() &&
            expectedFeedIds.all(bucket.tiles::containsKey)
        ) {
            pending.remove(segment.key)
            return TmeAlignedSegment(segment.key, bucket.tiles.values.toList())
        }
        return null
    }

    fun pollComplete(expectedFeedIds: Set<String>): TmeAlignedSegment? {
        val entry = pending.entries.firstOrNull { (_, bucket) ->
            expectedFeedIds.all(bucket.tiles::containsKey)
        } ?: return null
        pending.remove(entry.key)
        return TmeAlignedSegment(entry.key, entry.value.tiles.values.toList())
    }

    fun dropExpired(nowElapsedUs: Long = clockUs()): List<TmeCmafSegmentKey> {
        val expired = pending.entries
            .filter { (_, bucket) -> nowElapsedUs - bucket.firstSeenElapsedUs > segmentTimeoutUs }
            .map { it.key }
        expired.forEach(pending::remove)
        return expired
    }

    fun pendingCount(): Int = pending.size

    fun clear() {
        pending.clear()
    }
}

data class TmeSelectionState(
    val selectedFeedIds: List<String>,
    val generation: Long
)

class TmeDynamicSelection(
    private val maxFeeds: Int = 24
) {
    private var generation = 0L
    private var selected = emptyList<String>()

    fun state(): TmeSelectionState = TmeSelectionState(selected, generation)

    /**
     * Selection is a logical operation only. It must never recreate the physical
     * decoder or reset the shared playback clock.
     */
    fun setSelected(feedIds: List<String>): TmeSelectionState {
        val next = feedIds.distinct().take(maxFeeds)
        if (next != selected) {
            selected = next
            generation++
        }
        return state()
    }

    fun toggle(feedId: String): TmeSelectionState {
        return if (feedId in selected) {
            if (selected.size <= 1) state()
            else setSelected(selected.filterNot { it == feedId })
        } else {
            setSelected(selected + feedId)
        }
    }
}

data class TmeOutputSlot(
    val feedId: String,
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
    val zIndex: Int = 0
)

class TmeLayoutController {
    private var slots: List<TmeOutputSlot> = emptyList()

    fun setSlots(next: List<TmeOutputSlot>): List<TmeOutputSlot> {
        slots = next.distinctBy { it.feedId }
        return slots
    }

    fun slots(): List<TmeOutputSlot> = slots
}

/**
 * The only component allowed to turn multiple compressed tile samples into a
 * decoder-ready stream.
 *
 * Implementations must preserve timestamps and produce one elementary stream
 * for one decoder. A Media3 collection of players is explicitly not an
 * implementation of this interface.
 */
interface TmeBitstreamMerger {
    val implementationName: String
    val available: Boolean

    fun merge(segment: TmeAlignedSegment): List<TmeMergedAccessUnit>

    fun release()
}

/**
 * Runtime accounting. These values are deliberately physical rather than logical.
 * "players=1" is not sufficient proof of the TME architecture.
 */
data class TmeRuntimeTelemetry(
    val backend: String,
    val logicalFeedCount: Int,
    val selectedFeedCount: Int,
    val inputTileStreams: Int,
    val mergedVideoStreams: Int,
    val mediaCodecInstances: Int,
    val outputSurfaces: Int,
    val droppedSegments: Long,
    val mergeLatencyMs: Long,
    val decoderRecreationCount: Long
) {
    val singleDecoderInvariant: Boolean
        get() = mergedVideoStreams == 1 &&
            mediaCodecInstances == 1 &&
            outputSurfaces == 1
}

class TmePerformanceTracker {
    private var dropped = 0L
    private var mergeLatency = 0L
    private var decoderRecreations = 0L

    fun recordDroppedSegment() { dropped++ }
    fun recordMergeLatency(ms: Long) { mergeLatency = ms.coerceAtLeast(0L) }
    fun recordDecoderRecreation() { decoderRecreations++ }

    fun telemetry(
        logicalFeeds: Int,
        selectedFeeds: Int,
        inputTiles: Int,
        mergedStreams: Int,
        codecs: Int,
        surfaces: Int
    ): TmeRuntimeTelemetry = TmeRuntimeTelemetry(
        backend = "OPEN_TME_NATIVE_PIPELINE",
        logicalFeedCount = logicalFeeds,
        selectedFeedCount = selectedFeeds,
        inputTileStreams = inputTiles,
        mergedVideoStreams = mergedStreams,
        mediaCodecInstances = codecs,
        outputSurfaces = surfaces,
        droppedSegments = dropped,
        mergeLatencyMs = mergeLatency,
        decoderRecreationCount = decoderRecreations
    )
}

/**
 * Converts the existing F1 TME metadata model into OpenTME tile sources.
 * This adapter does not download or decrypt protected content.
 */
object F1TmeTileAdapter {
    fun map(session: app.f1multiview.core.playback.TiledMultiviewSession): List<TmeTileSource> {
        require(session.hasTileGeometry) { "F1 TME tile geometry is missing" }
        require(session.feeds.isNotEmpty()) { "F1 TME feed list is empty" }

        val width = session.tileWidth!!
        val height = session.tileHeight!!

        return session.feeds.mapIndexed { index, feed ->
            val tileIndex = feed.tileIndex
                ?: error("F1 TME feed $index has no authoritative tileIndex")
            val row = feed.tileRow
                ?: error("F1 TME feed $index has no authoritative tileRow")
            val column = feed.tileColumn
                ?: error("F1 TME feed $index has no authoritative tileColumn")
            require(tileIndex >= 0 && row >= 0 && column >= 0) {
                "F1 TME feed $index has invalid tile placement"
            }
            val url = feed.url?.takeIf(String::isNotBlank)
                ?: error("F1 TME feed $index has no media URL")
            TmeTileSource(
                feedId = session.feedIds[index],
                url = url,
                tileIndex = tileIndex,
                row = row,
                column = column,
                tileWidth = width,
                tileHeight = height
            )
        }
    }
}

/**
 * Phase gate used by integration code and tests.
 *
 * The gate intentionally fails closed: no backend may advertise native TME
 * playback until the compressed-domain merger and the physical decoder/surface
 * invariant are both proven.
 */
object TmeAcceptanceGate {
    fun requireNativeProof(telemetry: TmeRuntimeTelemetry) {
        check(telemetry.inputTileStreams >= 2) {
            "TME proof requires at least two tile inputs"
        }
        check(telemetry.singleDecoderInvariant) {
            "TME invariant failed: expected N tile inputs -> 1 merged stream -> 1 MediaCodec -> 1 surface"
        }
        check(telemetry.decoderRecreationCount == 0L) {
            "TME dynamic selection recreated the physical decoder"
        }
    }
}


/**
 * Contract for the compressed-domain side of OpenTME.
 *
 * The merger produces complete HEVC access units. The decoder consumes those
 * access units on exactly one MediaCodec instance and never knows how many
 * logical tiles contributed to them.
 */
data class TmeMergedAccessUnit(
    val ptsUs: Long,
    val dtsUs: Long,
    val durationUs: Long,
    val keyFrame: Boolean,
    val codecConfig: ByteArray? = null,
    val payload: ByteArray
)

data class TmeDecoderTelemetry(
    val codecName: String?,
    val configured: Boolean,
    val started: Boolean,
    val queuedAccessUnits: Long,
    val renderedAccessUnits: Long,
    val decoderRecreationCount: Long,
    val outputSurfaceAttached: Boolean
) {
    val singleDecoderInvariant: Boolean
        get() = configured && started && decoderRecreationCount == 0L &&
            outputSurfaceAttached
}
