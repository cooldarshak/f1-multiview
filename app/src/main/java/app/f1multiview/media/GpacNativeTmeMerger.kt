package app.f1multiview.media

/**
 * Narrow boundary between Android/Kotlin scheduling and the native compressed-domain merger.
 *
 * Native implementations are expected to wrap GPAC/OpenTME. This interface intentionally
 * carries timestamps and codec configuration so no Media3 player is needed between the
 * merger and TmeNativeDecoder.
 */
interface NativeTmeMerger : TmeBitstreamMerger {
    override val available: Boolean

    fun configure(tileSources: List<TmeTileSource>, outputWidth: Int, outputHeight: Int)

    fun push(segment: TmeAlignedSegment): List<TmeMergedAccessUnit>

    fun updateSelection(selectedFeedIds: Set<String>)

    override fun merge(segment: TmeAlignedSegment): List<TmeMergedAccessUnit> =
        push(segment)
}

/**
 * JNI-backed implementation is deliberately unavailable until the native library is
 * present. It must fail closed rather than silently falling back to N Media3 players.
 */
class GpacNativeTmeMerger : NativeTmeMerger {
    override val implementationName: String = "GPAC_NATIVE_TME"
    override val available: Boolean
        get() = nativeLoaded && nativeIsAvailable()

    override fun configure(tileSources: List<TmeTileSource>, outputWidth: Int, outputHeight: Int) {
        check(available) { "GPAC native TME library is not loaded" }
        require(tileSources.size >= 2)
        nativeConfigure(tileSources.map { it.feedId }.toTypedArray(), outputWidth, outputHeight)
    }

    override fun push(segment: TmeAlignedSegment): List<TmeMergedAccessUnit> {
        check(available) { "GPAC native TME library is not loaded" }
        return nativePush(
            segment.key.sequence,
            segment.key.epochStartUs,
            segment.key.durationUs,
            segment.tiles.map { it.feedId }.toTypedArray(),
            segment.tiles.map { it.payload }.toTypedArray()
        ).toList()
    }

    override fun updateSelection(selectedFeedIds: Set<String>) {
        check(available) { "GPAC native TME library is not loaded" }
        nativeUpdateSelection(selectedFeedIds.toTypedArray())
    }

    override fun release() {
        if (available) nativeRelease()
    }

    private external fun nativeIsAvailable(): Boolean
    private external fun nativeConfigure(feedIds: Array<String>, width: Int, height: Int)
    private external fun nativePush(
        sequence: Long,
        epochStartUs: Long,
        durationUs: Long,
        feedIds: Array<String>,
        payloads: Array<ByteArray>
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
