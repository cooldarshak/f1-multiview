package app.f1multiview.media

import androidx.media3.common.C
import androidx.media3.common.DataReader
import java.io.IOException

/**
 * Bounds one already-resolved CMAF segment before bytes reach an extractor.
 *
 * The caller must obtain segmentLength from the authorized data source/HTTP response and choose
 * an explicit maxSegmentBytes budget. This wrapper never reads past the declared segment length.
 * Premature upstream EOF is an error, not a successful end-of-segment.
 *
 * This is deliberately not an HTTP client, manifest parser, DRM handler, or decryption layer.
 */
internal class BoundedCmafSegmentReader(
    private val upstream: DataReader,
    val segmentLength: Long,
    val maxSegmentBytes: Long
) : DataReader {
    var bytesRead: Long = 0L
        private set

    init {
        require(segmentLength >= 0L) { "Segment length cannot be negative" }
        require(maxSegmentBytes > 0L) { "Maximum segment size must be positive" }
        require(segmentLength <= maxSegmentBytes) {
            "Declared segment length $segmentLength exceeds configured limit $maxSegmentBytes"
        }
    }

    @Throws(IOException::class)
    override fun read(target: ByteArray, offset: Int, length: Int): Int {
        if (offset < 0 || length < 0 || offset > target.size - length) {
            throw IndexOutOfBoundsException("Invalid target range: offset=$offset length=$length size=${target.size}")
        }
        if (length == 0) return 0
        if (bytesRead == segmentLength) return C.RESULT_END_OF_INPUT

        val remaining = segmentLength - bytesRead
        val allowed = minOf(length.toLong(), remaining).toInt()
        val count = upstream.read(target, offset, allowed)
        if (count == C.RESULT_END_OF_INPUT) {
            throw IOException("Premature EOF in CMAF segment: read $bytesRead of $segmentLength bytes")
        }
        if (count <= 0 || count > allowed) {
            throw IOException("Invalid upstream read count $count for requested bounded length $allowed")
        }

        bytesRead += count
        check(bytesRead <= segmentLength && bytesRead <= maxSegmentBytes) {
            "CMAF segment reader exceeded its configured byte boundary"
        }
        return count
    }
}
