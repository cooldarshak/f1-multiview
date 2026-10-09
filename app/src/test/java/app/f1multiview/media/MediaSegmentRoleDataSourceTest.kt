package app.f1multiview.media

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
class MediaSegmentRoleDataSourceTest {
    @Test
    fun unmarkedInitializationManifestOrKeyTrafficIsNotBounded() {
        val upstream = FakeDataSource(payloadSize = 5, declaredLength = 5L)
        val source = MediaSegmentRoleDataSource(upstream, maxBytes = 2L)

        source.open(spec(customData = null))
        val target = ByteArray(8)
        assertEquals(5, source.read(target, 0, target.size))
        assertEquals(C.RESULT_END_OF_INPUT, source.read(target, 0, target.size))
        assertEquals(5, upstream.bytesRead)
        source.close()
    }


    @Test
    fun manifestInitKeyAndLicenseUrisRemainUnboundedWithoutExplicitRole() {
        val resources = listOf(
            "https://example.test/master.m3u8",
            "https://example.test/init.mp4",
            "https://example.test/key",
            "https://license.test/widevine"
        )
        for (uri in resources) {
            val upstream = FakeDataSource(payloadSize = 5, declaredLength = 5L)
            val source = MediaSegmentRoleDataSource(upstream, maxBytes = 2L)
            source.open(spec(customData = null, uri = uri))
            val target = ByteArray(8)
            assertEquals("resource=$uri", 5, source.read(target, 0, target.size))
            assertEquals("resource=$uri", C.RESULT_END_OF_INPUT, source.read(target, 0, target.size))
            assertEquals("resource=$uri", 5, upstream.bytesRead)
            source.close()
        }
    }

    @Test
    fun markedSegmentKeepsOriginalUriAndAuthorizationHeaders() {
        val uri = "https://authorized.example.test/segment.m4s"
        val headers = mapOf("Authorization" to "Bearer fixture-token", "Cookie" to "session=fixture")
        val upstream = FakeDataSource(payloadSize = 2, declaredLength = 2L)
        val source = MediaSegmentRoleDataSource(upstream, maxBytes = 8L)
        source.open(spec(MediaSegmentRoleDataSource.MEDIA_SEGMENT_ROLE, uri, headers))

        assertEquals(uri, upstream.lastSpec?.uri.toString())
        assertEquals(headers, upstream.lastSpec?.httpRequestHeaders)
        assertEquals(2, source.read(ByteArray(8), 0, 8))
        assertEquals(C.RESULT_END_OF_INPUT, source.read(ByteArray(8), 0, 8))
        source.close()
    }

    @Test
    fun unmarkedOversizeLicenseDoesNotAccidentallyEnterSegmentBudget() {
        val upstream = FakeDataSource(payloadSize = 9, declaredLength = 9L)
        val source = MediaSegmentRoleDataSource(upstream, maxBytes = 2L)
        source.open(spec(null, "https://license.test/challenge"))
        assertEquals(9, source.read(ByteArray(16), 0, 16))
        source.close()
    }

    @Test
    fun explicitlyMarkedMediaSegmentIsBounded() {
        val upstream = FakeDataSource(payloadSize = 5, declaredLength = 5L)
        val source = MediaSegmentRoleDataSource(upstream, maxBytes = 2L)

        assertThrows(IOException::class.java) {
            source.open(spec(MediaSegmentRoleDataSource.MEDIA_SEGMENT_ROLE))
        }
        assertEquals(0, upstream.bytesRead)
        source.close()
    }

    private fun spec(
        customData: Any?,
        uri: String = "https://example.test/resource.bin",
        headers: Map<String, String> = emptyMap()
    ) =
        DataSpec.Builder()
            .setUri(Uri.parse(uri))
            .setHttpRequestHeaders(headers)
            .setCustomData(customData)
            .build()

    private class FakeDataSource(
        payloadSize: Int,
        private val declaredLength: Long
    ) : DataSource {
        private val payload = ByteArray(payloadSize) { it.toByte() }
        var bytesRead = 0
            private set
        var lastSpec: DataSpec? = null
            private set

        override fun addTransferListener(transferListener: TransferListener) = Unit

        override fun open(dataSpec: DataSpec): Long {
            lastSpec = dataSpec
            return declaredLength
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (bytesRead >= payload.size) return C.RESULT_END_OF_INPUT
            val count = minOf(length, payload.size - bytesRead)
            payload.copyInto(buffer, offset, bytesRead, bytesRead + count)
            bytesRead += count
            return count
        }

        override fun getUri(): Uri = Uri.parse("https://example.test/resource.bin")

        override fun getResponseHeaders(): Map<String, List<String>> = emptyMap()

        override fun close() = Unit
    }
}
