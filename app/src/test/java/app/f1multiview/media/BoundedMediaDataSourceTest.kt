package app.f1multiview.media

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
class BoundedMediaDataSourceTest {
    @Test
    fun knownLengthIsBoundedAndDoesNotReadPastBoundary() {
        val upstream = FakeDataSource(byteArrayOf(1, 2, 3, 4, 5), declaredLength = 5L)
        val source = BoundedMediaDataSource(upstream, maxBytes = 8L)
        source.open(spec())
        val target = ByteArray(8)

        assertEquals(5, source.read(target, 0, target.size))
        assertEquals(C.RESULT_END_OF_INPUT, source.read(target, 0, target.size))
        assertEquals(5, upstream.bytesRead)
        source.close()
    }

    @Test
    fun rejectsKnownOversizeBeforeExposingAnyBytes() {
        val upstream = FakeDataSource(byteArrayOf(1, 2, 3), declaredLength = 3L)
        val source = BoundedMediaDataSource(upstream, maxBytes = 2L)

        assertThrows(IOException::class.java) { source.open(spec()) }
        assertEquals(0, upstream.bytesRead)
        assertTrue(upstream.closeCount > 0)
    }

    @Test
    fun detectsPrematureEofForKnownLength() {
        val upstream = FakeDataSource(byteArrayOf(1, 2), declaredLength = 3L)
        val source = BoundedMediaDataSource(upstream, maxBytes = 3L)
        source.open(spec())
        val target = ByteArray(8)

        assertEquals(2, source.read(target, 0, target.size))
        assertThrows(IOException::class.java) { source.read(target, 0, target.size) }
        source.close()
    }

    @Test
    fun preservesNaturalEofWhenLengthIsUnknown() {
        val upstream = FakeDataSource(byteArrayOf(1, 2), declaredLength = C.LENGTH_UNSET.toLong())
        val source = BoundedMediaDataSource(upstream, maxBytes = 3L)
        source.open(spec())
        val target = ByteArray(8)

        assertEquals(2, source.read(target, 0, target.size))
        assertEquals(C.RESULT_END_OF_INPUT, source.read(target, 0, target.size))
        source.close()
    }

    @Test
    fun failsClosedWhenUnknownLengthResourceReachesBudget() {
        val upstream = FakeDataSource(byteArrayOf(1, 2, 3, 4), declaredLength = C.LENGTH_UNSET.toLong())
        val source = BoundedMediaDataSource(upstream, maxBytes = 3L)
        source.open(spec())
        val target = ByteArray(8)

        assertEquals(3, source.read(target, 0, target.size))
        assertThrows(IOException::class.java) { source.read(target, 0, target.size) }
        assertEquals(3, upstream.bytesRead)
        source.close()
    }

    @Test
    fun returnsZeroForZeroLengthRead() {
        val upstream = FakeDataSource(byteArrayOf(1), declaredLength = 1L)
        val source = BoundedMediaDataSource(upstream, maxBytes = 1L)
        source.open(spec())

        assertEquals(0, source.read(ByteArray(1), 0, 0))
        assertEquals(0, upstream.bytesRead)
        source.close()
    }

    private fun spec() = DataSpec.Builder().setUri(Uri.parse("https://example.test/segment.m4s")).build()

    private class FakeDataSource(
        private val payload: ByteArray,
        private val declaredLength: Long
    ) : DataSource {
        var bytesRead = 0
            private set
        var closeCount = 0
            private set

        override fun addTransferListener(transferListener: TransferListener) = Unit

        override fun open(dataSpec: DataSpec): Long = declaredLength

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (bytesRead >= payload.size) return C.RESULT_END_OF_INPUT
            val count = minOf(length, payload.size - bytesRead)
            payload.copyInto(buffer, offset, bytesRead, bytesRead + count)
            bytesRead += count
            return count
        }

        override fun getUri(): Uri = Uri.parse("https://example.test/segment.m4s")

        override fun getResponseHeaders(): Map<String, List<String>> = emptyMap()

        override fun close() {
            closeCount++
        }
    }
}
