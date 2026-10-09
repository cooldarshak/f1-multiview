package app.f1multiview.media

import android.net.Uri
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSource.Factory
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import java.io.IOException

/**
 * Enforces the segment byte budget only when patched Media3 marks the request at the
 * HLS/DASH media-chunk construction site. Initialization/index requests and all other
 * traffic are passed through unchanged. No URL, ordering, or request-header heuristics.
 */
internal class MediaSegmentRoleDataSource(
    private val upstream: DataSource,
    private val maxBytes: Long
) : DataSource {
    class Factory(private val upstreamFactory: Factory) : Factory {
        override fun createDataSource(): DataSource =
            MediaSegmentRoleDataSource(upstreamFactory.createDataSource(), DEFAULT_MAX_BYTES)
    }

    companion object {
        const val MEDIA_SEGMENT_ROLE = "f1-multiview:media-segment:v1"
        const val DEFAULT_MAX_BYTES = 32L * 1024L * 1024L
    }

    private var active: DataSource = upstream

    override fun addTransferListener(transferListener: TransferListener) {
        upstream.addTransferListener(transferListener)
    }

    @Throws(IOException::class)
    override fun open(dataSpec: DataSpec): Long {
        close()
        active = if (dataSpec.customData == MEDIA_SEGMENT_ROLE) {
            BoundedMediaDataSource(upstream, maxBytes)
        } else {
            upstream
        }
        return active.open(dataSpec)
    }

    @Throws(IOException::class)
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        active.read(buffer, offset, length)

    override fun getUri(): Uri? = active.uri

    override fun getResponseHeaders(): Map<String, List<String>> = active.responseHeaders

    @Throws(IOException::class)
    override fun close() {
        active.close()
        active = upstream
    }
}
