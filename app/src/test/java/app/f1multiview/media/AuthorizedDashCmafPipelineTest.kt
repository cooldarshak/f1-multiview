package app.f1multiview.media

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import app.f1multiview.model.StreamKind
import app.f1multiview.model.StreamSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AuthorizedDashCmafPipelineTest {
    private val dollar = '$'
    private val manifestUrl = "https://origin.example.test/race/manifest.mpd"
    private val manifest = """
        <?xml version="1.0"?>
        <MPD xmlns="urn:mpeg:dash:schema:mpd:2011"
             type="static" mediaPresentationDuration="PT8S" minBufferTime="PT1S">
          <BaseURL>https://cdn.example.test/race/</BaseURL>
          <Period id="p0" start="PT0S" duration="PT8S">
            <AdaptationSet id="1" contentType="video" mimeType="video/mp4" segmentAlignment="true">
              <SegmentTemplate timescale="1" duration="2"
                  initialization="init-${dollar}RepresentationID${dollar}.mp4"
                  media="segment-${dollar}Number${dollar}.m4s" startNumber="5"/>
              <Representation id="low" bandwidth="300000" width="640" height="360" codecs="avc1.4d401e"/>
            </AdaptationSet>
          </Period>
        </MPD>
    """.trimIndent()

    @Test
    fun loadsManifestResolvesAndExtractsAuthorizedClearDashSegment() {
        val complete = javaClass.classLoader!!
            .getResourceAsStream("media/sample_fragmented.mp4")!!
            .use { it.readBytes() }
        val (initBytes, mediaBytes) = splitAtMoof(complete)
        val factory = ByteArrayDataSourceFactory(
            mapOf(
                "manifest.mpd" to manifest.toByteArray(),
                "init-low.mp4" to initBytes,
                "segment-5.m4s" to mediaBytes
            )
        )
        val stream = stream(protected = false)
        val output = CmafSampleQueueOutput()
        try {
            val result = AuthorizedDashCmafPipeline(
                stream = stream,
                dataSourceFactory = factory,
                maxWidth = 640,
                maxHeight = 360
            ).prepareFirstSegment(output)

            assertEquals(manifestUrl, result.resolvedManifestUri.toString())
            assertEquals("https://cdn.example.test/race/segment-5.m4s", result.videoPlan.firstMediaSegment.dataSpec.uri.toString())
            assertTrue(result.extraction.trackCount > 0)
            assertTrue(output.sampleQueues.values.any { it.upstreamFormat != null })
            assertFalse(result.extractedDrmInitDataPresent)
        } finally {
            output.close()
        }
    }

    @Test(expected = IOException::class)
    fun protectedStreamWithoutLicenseEndpointFailsBeforeAnyNetworkRequest() {
        val output = CmafSampleQueueOutput()
        try {
            AuthorizedDashCmafPipeline(
                stream = stream(protected = true, licenseUrl = null),
                dataSourceFactory = UnusedDataSourceFactory()
            ).prepareFirstSegment(output)
        } finally {
            output.close()
        }
    }

    @Test(expected = IOException::class)
    fun protectedStreamWithoutDrmManagedOutputFailsBeforeAnyNetworkRequest() {
        val output = CmafSampleQueueOutput()
        try {
            AuthorizedDashCmafPipeline(
                stream = stream(protected = true, licenseUrl = "https://license.example.test/widevine"),
                dataSourceFactory = UnusedDataSourceFactory()
            ).prepareFirstSegment(output)
        } finally {
            output.close()
        }
    }

    private fun stream(protected: Boolean, licenseUrl: String? = null) = StreamSource(
        id = "feed",
        title = "Authorized feed",
        kind = StreamKind.WORLD,
        url = manifestUrl,
        drmLicenseUrl = licenseUrl,
        requestHeaders = mapOf("Authorization" to "authorized-test-header"),
        drmProtected = protected
    )

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
            if (type == "moof") return bytes.copyOfRange(0, position) to bytes.copyOfRange(position, bytes.size)
            position += boxSize.toInt()
        }
        error("Fragmented MP4 fixture has no top-level moof box")
    }

    private class UnusedDataSourceFactory : DataSource.Factory {
        override fun createDataSource(): DataSource =
            throw AssertionError("Fail-closed DRM precondition should run before network access")
    }

    private class ByteArrayDataSourceFactory(
        private val resources: Map<String, ByteArray>
    ) : DataSource.Factory {
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
