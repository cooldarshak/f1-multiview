package app.f1multiview.media

interface NativeTmeMerger : TmeBitstreamMerger {
    override val available: Boolean
    fun configure(tileSources: List<TmeTileSource>, outputWidth: Int, outputHeight: Int)
    fun push(segment: TmeAlignedSegment): List<TmeMergedAccessUnit>
    fun updateSelection(selectedFeedIds: Set<String>)
    override fun merge(segment: TmeAlignedSegment): List<TmeMergedAccessUnit> = push(segment)
}

/**
 * Availability is stricter than native library presence: the native layer must report
 * that the complete compressed-domain merger graph is ready.
 */
class GpacNativeTmeMerger : NativeTmeMerger {
    private var pushCount = 0L
    override val implementationName: String = "GPAC_NATIVE_TME"
    override val available: Boolean
        get() = nativeLoaded && runCatching { nativeIsAvailable() }.getOrDefault(false)

    override fun configure(tileSources: List<TmeTileSource>, outputWidth: Int, outputHeight: Int) {
        check(available) { "GPAC native TME merger graph is not ready" }
        require(tileSources.size >= 2)
        require(outputWidth > 0 && outputHeight > 0)
        AppLogger.i(
            "TME",
            "GPAC_CONFIG inputs=${tileSources.size} output=${outputWidth}x${outputHeight} " +
                tileSources.joinToString(" ") { it.feedId + "@r" + (it.row ?: 0) + "c" + it.column + "=" + it.tileWidth + "x" + it.tileHeight }
        )
        nativeConfigure(
            tileSources.map { it.feedId }.toTypedArray(),
            outputWidth,
            outputHeight,
            tileSources.map { it.decoderConfig ?: ByteArray(0) }.toTypedArray(),
            tileSources.map { (it.column * it.tileWidth) }.toIntArray(),
            tileSources.map { ((it.row ?: 0) * it.tileHeight) }.toIntArray(),
            tileSources.map { it.tileWidth }.toIntArray(),
            tileSources.map { it.tileHeight }.toIntArray()
        )
        AppLogger.i("TME", "GPAC_CONFIG_COMPLETE inputs=${tileSources.size} output=${outputWidth}x${outputHeight} mergedStreams=1")
    }

    override fun push(segment: TmeAlignedSegment): List<TmeMergedAccessUnit> {
        check(available) { "GPAC native TME merger graph is not ready" }
        pushCount++
        val merged = nativePush(
            segment.key.sequence,
            segment.key.epochStartUs,
            segment.key.durationUs,
            segment.tiles.map { it.feedId }.toTypedArray(),
            segment.tiles.map { it.payload }.toTypedArray(),
            segment.tiles.map { it.keyFrame }.toBooleanArray()
        ).toList()
        if (pushCount == 1L || pushCount % 30L == 0L) {
            AppLogger.i("TME", "GPAC_PUSH count=$pushCount sequence=${segment.key.sequence} tiles=${segment.tiles.size} mergedAccessUnits=${merged.size}")
        }
        return merged
    }

    override fun updateSelection(selectedFeedIds: Set<String>) {
        check(available) { "GPAC native TME merger graph is not ready" }
        nativeUpdateSelection(selectedFeedIds.toTypedArray())
    }

    override fun release() {
        if (nativeLoaded) runCatching { nativeRelease() }
        pushCount = 0L
    }

    private external fun nativeIsAvailable(): Boolean
    private external fun nativeConfigure(
        feedIds: Array<String>,
        width: Int,
        height: Int,
        decoderConfigs: Array<ByteArray>,
        tileX: IntArray,
        tileY: IntArray,
        tileWidths: IntArray,
        tileHeights: IntArray
    )
    private external fun nativePush(
        sequence: Long,
        epochStartUs: Long,
        durationUs: Long,
        feedIds: Array<String>,
        payloads: Array<ByteArray>,
        keyFrames: BooleanArray
    ): Array<TmeMergedAccessUnit>
    private external fun nativeUpdateSelection(feedIds: Array<String>)
    private external fun nativeRelease()

    private companion object {
        val nativeLoaded: Boolean = runCatching {
            System.loadLibrary("f1tme")
            true
        }.getOrDefault(false)
    }
}
