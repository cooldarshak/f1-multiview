package app.f1multiview.media

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import java.io.IOException

/**
 * Applies a byte budget to one DataSource request that the caller has already classified as
 * a media-segment request. This class does not classify HLS/DASH resources and must not be used
 * indiscriminately for manifests, DRM licenses, keys, or initialization resources.
 *
 * A known upstream length is enforced exactly and premature EOF is an error. For an unresolved
 * length, natural EOF remains valid, but reaching the configured byte budget without having
 * observed EOF fails closed on the next read.
 */
internal class BoundedMediaDataSource(
    private val upstream: DataSource,
    private val maxBytes: Long
) : DataSource {
    init {
        require(maxBytes > 0L) { "Maximum resource size must be positive" }
    }

    private var opened = false
    private var expectedLength = C.LENGTH_UNSET.toLong()
    private var bytesRead = 0L

    override fun addTransferListener(transferListener: TransferListener) {
        upstream.addTransferListener(transferListener)
    }

    @Throws(IOException::class)
    override fun open(dataSpec: DataSpec): Long {
        close()
        // Media3 callers close a DataSource even when open() throws. Mark the upstream as
        // needing close before invoking it so partially opened transports are cleaned up.
        opened = true
        val length = upstream.open(dataSpec)
        expectedLength = length
        bytesRead = 0L

        if (length != C.LENGTH_UNSET.toLong() && length > maxBytes) {
            close()
            throw IOException("Media segment request length $length exceeds configured limit $maxBytes")
        }
        return length
    }

    @Throws(IOException::class)
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (!opened) throw IOException("Media data source is not open")
        if (offset < 0 || length < 0 || offset > buffer.size - length) {
            throw IndexOutOfBoundsException("Invalid target range: offset=$offset length=$length size=${buffer.size}")
        }
        if (length == 0) return 0
        if (expectedLength != C.LENGTH_UNSET.toLong() && bytesRead == expectedLength) {
            return C.RESULT_END_OF_INPUT
        }
        if (bytesRead == maxBytes) {
            throw IOException("Media segment reached configured byte limit $maxBytes before EOF was established")
        }

        val remainingBudget = maxBytes - bytesRead
        val remainingDeclared = if (expectedLength == C.LENGTH_UNSET.toLong()) {
            Long.MAX_VALUE
        } else {
            expectedLength - bytesRead
        }
        val allowed = minOf(length.toLong(), remainingBudget, remainingDeclared).toInt()
        val count = upstream.read(buffer, offset, allowed)

        if (count == C.RESULT_END_OF_INPUT) {
            if (expectedLength != C.LENGTH_UNSET.toLong() && bytesRead < expectedLength) {
                throw IOException("Premature EOF in media segment: read $bytesRead of $expectedLength bytes")
            }
            return C.RESULT_END_OF_INPUT
        }
        if (count <= 0 || count > allowed) {
            throw IOException("Invalid upstream read count $count for requested bounded length $allowed")
        }
        bytesRead += count
        return count
    }

    override fun getUri(): Uri? = upstream.uri

    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders

    @Throws(IOException::class)
    override fun close() {
        if (opened) {
            opened = false
            upstream.close()
        }
        expectedLength = C.LENGTH_UNSET.toLong()
        bytesRead = 0L
    }
}
