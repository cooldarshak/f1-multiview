package app.f1multiview.media

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.DefaultExtractorInput
import androidx.media3.extractor.DataReader
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.util.Arrays

@OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CmafExtractorPrototypeTest {
    @Test
    fun admitsMp4ContainerTypesWithoutAssumingDrmSupport() {
        assertTrue(CmafExtractorPrototype.supportsContainerMimeType("video/mp4"))
        assertTrue(CmafExtractorPrototype.supportsContainerMimeType("APPLICATION/MP4"))
        assertFalse(CmafExtractorPrototype.supportsContainerMimeType("application/vnd.apple.mpegurl"))
        assertFalse(CmafExtractorPrototype.supportsContainerMimeType(null))
    }

    @Test
    fun parsesRealFragmentedMp4AndEmitsTrackFormatAndSamples() {
        val bytes = javaClass.classLoader!!
            .getResourceAsStream("media/sample_fragmented.mp4")!!
            .use { it.readBytes() }
        assertTrue("Fixture should contain a real fragmented MP4 payload", bytes.size > 10_000)

        val extractor = CmafExtractorPrototype.createExtractor()
        try {
            val sniffInput = newInput(bytes, 0)
            assertTrue("Media3 must recognize the fixture as fragmented MP4", extractor.sniff(sniffInput))

            val output = CapturingExtractorOutput()
            extractor.init(output)
            var input = newInput(bytes, 0)
            val seekPosition = PositionHolder()
            var result: Int
            var reads = 0
            do {
                check(++reads <= 20_000) { "Extractor did not finish within the bounded read loop" }
                result = extractor.read(input, seekPosition)
                if (result == Extractor.RESULT_SEEK) {
                    val requested = seekPosition.position
                    check(requested in 0..bytes.size.toLong()) {
                        "Extractor requested an out-of-range seek: $requested"
                    }
                    extractor.seek(requested, 0)
                    input = newInput(bytes, requested.toInt())
                }
            } while (result == Extractor.RESULT_CONTINUE || result == Extractor.RESULT_SEEK)

            assertEquals("Extraction should reach end of the fixture", Extractor.RESULT_END_OF_INPUT, result)
            assertTrue("Extractor should discover at least one track", output.tracks.isNotEmpty())
            assertTrue("Extractor should emit track format metadata", output.tracks.values.any { it.format != null })
            assertTrue("Extractor should emit actual sample metadata", output.tracks.values.sumOf { it.samples.size } > 0)
            assertTrue("Extractor should emit actual sample bytes", output.tracks.values.sumOf { it.sampleBytes } > 0)
            assertTrue("Sample timestamps should be populated", output.tracks.values.flatMap { it.samples }.all { it.timeUs >= 0 })
            assertNotNull("Extractor should publish a seek map", output.seekMap)
        } finally {
            extractor.release()
        }
    }

    @Test
    fun rejectsNonFragmentedBytesDuringSniff() {
        val extractor = CmafExtractorPrototype.createExtractor()
        try {
            val ordinaryText = "this is not an ISO BMFF fragmented media stream".toByteArray()
            assertFalse(extractor.sniff(newInput(ordinaryText, 0)))
        } finally {
            extractor.release()
        }
    }

    private fun newInput(bytes: ByteArray, start: Int): DefaultExtractorInput =
        DefaultExtractorInput(ByteArrayDataReader(bytes, start), start.toLong(), bytes.size.toLong())

    private class ByteArrayDataReader(
        private val bytes: ByteArray,
        startPosition: Int
    ) : DataReader {
        private var position = startPosition

        override fun read(target: ByteArray, offset: Int, length: Int): Int {
            if (position >= bytes.size) return C.RESULT_END_OF_INPUT
            val count = minOf(length, bytes.size - position)
            System.arraycopy(bytes, position, target, offset, count)
            position += count
            return count
        }
    }

    private class CapturingExtractorOutput : ExtractorOutput {
        val tracks = linkedMapOf<Int, CapturingTrackOutput>()
        var seekMap: SeekMap? = null

        override fun track(id: Int, type: Int): TrackOutput =
            tracks.getOrPut(id) { CapturingTrackOutput(type) }

        override fun endTracks() = Unit
        override fun seekMap(seekMap: SeekMap) { this.seekMap = seekMap }
    }

    private class CapturingTrackOutput(val trackType: Int) : TrackOutput {
        var format: Format? = null
        var sampleBytes: Long = 0
        val samples = mutableListOf<SampleMetadata>()

        override fun format(format: Format) { this.format = format }

        @Throws(IOException::class)
        override fun sampleData(input: androidx.media3.common.DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int): Int {
            val buffer = ByteArray(length)
            var total = 0
            while (total < length) {
                val count = input.read(buffer, total, length - total)
                if (count == C.RESULT_END_OF_INPUT) {
                    if (total == 0 && allowEndOfInput) return C.RESULT_END_OF_INPUT
                    throw IOException("Unexpected end of sample data after $total of $length bytes")
                }
                total += count
            }
            sampleBytes += total
            return total
        }

        override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) {
            data.skipBytes(length)
            sampleBytes += length
        }

        override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) {
            samples += SampleMetadata(timeUs, flags, size, offset, cryptoData != null)
        }
    }

    private data class SampleMetadata(
        val timeUs: Long,
        val flags: Int,
        val size: Int,
        val offset: Int,
        val encrypted: Boolean
    )
}
