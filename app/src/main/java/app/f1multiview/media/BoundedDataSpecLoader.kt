package app.f1multiview.media

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import java.io.IOException

/** Bounded one-shot loading for authorized manifests and CMAF byte ranges. */
internal object BoundedDataSpecLoader {
    data class Resource(
        val resolvedUri: Uri,
        val bytes: ByteArray,
        val responseHeaders: Map<String, List<String>>
    )

    fun load(factory: DataSource.Factory, dataSpec: DataSpec, maxBytes: Int): Resource {
        require(maxBytes > 0) { "Maximum resource size must be positive" }
        val destination = ByteArray(maxBytes)
        val source = factory.createDataSource()
        var needsClose = false
        try {
            needsClose = true
            val declaredLength = source.open(dataSpec)
            if (declaredLength != C.LENGTH_UNSET.toLong() && declaredLength > maxBytes) {
                throw IOException("Authorized resource length $declaredLength exceeds $maxBytes byte limit")
            }
            val resolvedUri = source.uri ?: dataSpec.uri
            val headers = source.responseHeaders
            val size = readOpenSource(source, destination, 0, maxBytes, declaredLength)
            return Resource(resolvedUri, destination.copyOf(size), headers)
        } finally {
            if (needsClose) runCatching { source.close() }
        }
    }

    fun readInto(
        factory: DataSource.Factory,
        dataSpec: DataSpec,
        destination: ByteArray,
        destinationOffset: Int,
        maxBytes: Int
    ): Int {
        require(maxBytes > 0) { "Maximum resource size must be positive" }
        require(destinationOffset >= 0 && destinationOffset <= destination.size) {
            "Destination offset is outside the destination buffer"
        }
        require(maxBytes <= destination.size - destinationOffset) {
            "Destination buffer cannot hold the configured resource budget"
        }
        val source = factory.createDataSource()
        var needsClose = false
        try {
            needsClose = true
            val declaredLength = source.open(dataSpec)
            if (declaredLength != C.LENGTH_UNSET.toLong() && declaredLength > maxBytes) {
                throw IOException("Authorized resource length $declaredLength exceeds $maxBytes byte limit")
            }
            return readOpenSource(source, destination, destinationOffset, maxBytes, declaredLength)
        } finally {
            if (needsClose) runCatching { source.close() }
        }
    }

    private fun readOpenSource(
        source: DataSource,
        destination: ByteArray,
        destinationOffset: Int,
        maxBytes: Int,
        declaredLength: Long
    ): Int {
        var total = 0
        while (total < maxBytes) {
            val requested = minOf(16 * 1024, maxBytes - total)
            val count = source.read(destination, destinationOffset + total, requested)
            if (count == C.RESULT_END_OF_INPUT) break
            if (count <= 0 || count > requested) {
                throw IOException("Invalid authorized-resource read count $count for request $requested")
            }
            total += count
        }
        if (total == maxBytes) {
            val probe = ByteArray(1)
            val count = source.read(probe, 0, 1)
            if (count != C.RESULT_END_OF_INPUT) {
                throw IOException("Authorized resource reached its $maxBytes byte limit before EOF")
            }
        }
        if (declaredLength != C.LENGTH_UNSET.toLong() && total.toLong() != declaredLength) {
            throw IOException("Premature EOF in authorized resource: read $total of $declaredLength bytes")
        }
        return total
    }
}
