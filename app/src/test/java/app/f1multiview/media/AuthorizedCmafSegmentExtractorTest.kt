package app.f1multiview.media

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.decoder.DecoderInputBuffer
import androidx.media3.exoplayer.FormatHolder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

@OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AuthorizedCmafSegmentExtractorTest {
    @Test
    fun extractsAnEncryptedCmafFixtureFetchedAsSeparateInitAndMediaResources() {
        val complete = javaClass.classLoader!!
            .getResourceAsStream("media/sample_fragmented_widevine.mp4")!!
            .use { it.readBytes() }
        val (initBytes, mediaBytes) = splitAtMoof(complete)
        val initUri = Uri.parse("https://cdn.example.test/init.mp4")
        val mediaUri = Uri.parse("https://cdn.example.test/segment-5.m4s")
        val factory = ByteArrayDataSourceFactory(
            mapOf("init.mp4" to initBytes, "segment-5.m4s" to mediaBytes)
        )
        val plan = AuthorizedDashManifestResolver.VideoPlan(
            format = Format.Builder().setSampleMimeType("video/avc").setWidth(640).setHeight(360).build(),
            initialization = DataSpec.Builder().setUri(initUri).build(),
            firstMediaSegment = AuthorizedDashManifestResolver.SegmentRequest(
                DataSpec.Builder().setUri(mediaUri)
                    .setCustomData(MediaSegmentRoleDataSource.MEDIA_SEGMENT_ROLE).build(),
                presentationTimeUs = 0L,
                durationUs = 2_000_000L
            ),
            periodIndex = 0,
            dynamicManifest = false,
            manifestDeclaredDrmInitData = true
        )
        val output = CmafSampleQueueOutput()
        try {
            val result = AuthorizedCmafSegmentExtractor(factory).extractFirstSegment(plan, output)
            assertEquals(complete.size, result.bytesFetched)
            assertTrue(result.trackCount > 0)
            assertTrue(result.seekMapAvailable)
            assertTrue(output.sampleQueues.values.any { it.upstreamFormat?.drmInitData != null })

            var samples = 0
            var encrypted = 0
            output.sampleQueues.values.forEach { queue ->
                val holder = FormatHolder()
                val buffer = DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_NORMAL)
                var readResult = queue.read(holder, buffer, 0, true)
                while (readResult == C.RESULT_FORMAT_READ) {
                    buffer.clear()
                    readResult = queue.read(holder, buffer, 0, true)
                }
                while (readResult == C.RESULT_BUFFER_READ && !buffer.isEndOfStream) {
                    samples++
                    if (buffer.isEncrypted) encrypted++
                    buffer.clear()
                    readResult = queue.read(holder, buffer, 0, true)
                }
            }
            assertTrue("The extracted resources should produce samples", samples > 0)
            assertTrue("Encrypted metadata must survive separate-resource extraction", encrypted > 0)
        } finally {
            output.close()
        }
    }

    private fun splitAtMoof(bytes: ByteArray): Pair<ByteArray, ByteArray> {
        var position = 0
        while (position + 8 <= bytes.size) {
            val size32 = ByteBuffer.wrap(bytes, position, 4).int.toLong() and 0xffff_ffffL
            val type = String(bytes, position + 4, 4, StandardCharsets.US_ASCII)
            val headerSize = if (size32 == 1L) 16 else 8
            val boxSize = when (size32) {
                0L -> (bytes.size - position).toLong()
                1L -> ByteBuffer.wrap(bytes, position + 8, 8).long
                else -> size32
            }
            require(boxSize >= headerSize && boxSize <= bytes.size - position) {
                "Invalid ISO BMFF box size at offset $position"
            }
            if (type == "moof") {
                return bytes.copyOfRange(0, position) to bytes.copyOfRange(position, bytes.size)
            }
            position += boxSize.toInt()
        }
        error("Fragmented MP4 fixture has no top-level moof box")
    }

    private class ByteArrayDataSourceFactory(private val resources: Map<String, ByteArray>) : DataSource.Factory {
        override fun createDataSource(): DataSource = ByteArrayDataSource(resources)
    }

    private class ByteArrayDataSource(private val resources: Map<String, ByteArray>) : DataSource {
        private var activeUri: Uri? = null
        private var activeBytes: ByteArray? = null
        private var position = 0
        private var limit = 0

        override fun addTransferListener(transferListener: TransferListener) = Unit

        override fun open(dataSpec: DataSpec): Long {
            close()
            val uri = dataSpec.uri
            val bytes = resources[uri.lastPathSegment] ?: throw IOException("No fixture resource for $uri")
            val start = dataSpec.position.coerceAtMost(bytes.size.toLong()).toInt()
            val end = if (dataSpec.length == C.LENGTH_UNSET.toLong()) {
                bytes.size
            } else {
                minOf(bytes.size.toLong(), dataSpec.position + dataSpec.length).toInt()
            }
            if (start > end) throw IOException("Invalid fixture range for $uri")
            activeUri = uri
            activeBytes = bytes
            position = start
            limit = end
            return (end - start).toLong()
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (length == 0) return 0
            val bytes = activeBytes ?: throw IOException("Fixture source is not open")
            if (position >= limit) return C.RESULT_END_OF_INPUT
            val count = minOf(length, limit - position)
            bytes.copyInto(buffer, offset, position, position + count)
            position += count
            return count
        }

        override fun getUri(): Uri? = activeUri
        override fun getResponseHeaders(): Map<String, List<String>> = emptyMap()

        override fun close() {
            activeUri = null
            activeBytes = null
            position = 0
            limit = 0
        }
    }
}
