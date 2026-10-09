package app.f1multiview.media

import androidx.media3.common.C
import androidx.media3.common.DataReader
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class BoundedCmafSegmentReaderTest {
    @Test
    fun capsReadsAtDeclaredSegmentBoundaryAndThenReturnsEndOfInput() {
        val payload = byteArrayOf(10, 20, 30, 40, 50)
        val upstream = RecordingDataReader(payload)
        val reader = BoundedCmafSegmentReader(upstream, segmentLength = 3, maxSegmentBytes = 8)
        val target = ByteArray(8)

        assertEquals(3, reader.read(target, 0, target.size))
        assertArrayEquals(byteArrayOf(10, 20, 30), target.copyOfRange(0, 3))
        assertEquals(3L, reader.bytesRead)
        assertEquals(3, upstream.totalBytesRead)
        assertEquals(C.RESULT_END_OF_INPUT, reader.read(target, 0, target.size))
        assertEquals("Reader must not consume bytes beyond the declared segment", 3, upstream.totalBytesRead)
    }

    @Test
    fun returnsZeroForZeroLengthReadWithoutTouchingUpstream() {
        val upstream = RecordingDataReader(byteArrayOf(1, 2, 3))
        val reader = BoundedCmafSegmentReader(upstream, segmentLength = 3, maxSegmentBytes = 3)

        assertEquals(0, reader.read(ByteArray(3), 0, 0))
        assertEquals(0, upstream.totalBytesRead)
    }

    @Test
    fun reportsPrematureUpstreamEofInsteadOfTreatingTruncationAsSuccess() {
        val reader = BoundedCmafSegmentReader(
            RecordingDataReader(byteArrayOf(1, 2)),
            segmentLength = 3,
            maxSegmentBytes = 3
        )
        val target = ByteArray(8)

        // DataReader is allowed to return fewer bytes than requested. Truncation is only
        // established when the next read reports EOF before the declared segment boundary.
        assertEquals(2, reader.read(target, 0, target.size))
        assertEquals(2L, reader.bytesRead)
        assertThrows(IOException::class.java) { reader.read(target, 0, target.size) }
        assertEquals(2L, reader.bytesRead)
    }

    @Test
    fun rejectsDeclaredLengthAboveConfiguredBudgetBeforeReading() {
        val upstream = RecordingDataReader(byteArrayOf(1, 2, 3))

        assertThrows(IllegalArgumentException::class.java) {
            BoundedCmafSegmentReader(upstream, segmentLength = 3, maxSegmentBytes = 2)
        }
        assertEquals(0, upstream.totalBytesRead)
    }

    @Test
    fun handlesAnEmptyDeclaredSegmentWithoutReadingUpstream() {
        val upstream = RecordingDataReader(byteArrayOf(1))
        val reader = BoundedCmafSegmentReader(upstream, segmentLength = 0, maxSegmentBytes = 1)

        assertEquals(C.RESULT_END_OF_INPUT, reader.read(ByteArray(1), 0, 1))
        assertEquals(0, upstream.totalBytesRead)
        assertTrue(reader.bytesRead == 0L)
    }

    @Test
    fun rejectsInvalidTargetBounds() {
        val reader = BoundedCmafSegmentReader(
            RecordingDataReader(byteArrayOf(1)),
            segmentLength = 1,
            maxSegmentBytes = 1
        )

        assertThrows(IndexOutOfBoundsException::class.java) {
            reader.read(ByteArray(2), 1, 2)
        }
    }

    private class RecordingDataReader(private val payload: ByteArray) : DataReader {
        var totalBytesRead: Int = 0
            private set

        override fun read(target: ByteArray, offset: Int, length: Int): Int {
            if (totalBytesRead >= payload.size) return C.RESULT_END_OF_INPUT
            val count = minOf(length, payload.size - totalBytesRead)
            payload.copyInto(target, offset, totalBytesRead, totalBytesRead + count)
            totalBytesRead += count
            return count
        }
    }
}
