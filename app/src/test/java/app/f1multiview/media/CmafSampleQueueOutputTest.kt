package app.f1multiview.media

import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.decoder.DecoderInputBuffer
import androidx.media3.exoplayer.FormatHolder
import androidx.media3.exoplayer.source.SampleQueue
import androidx.media3.extractor.DefaultExtractorInput
import androidx.media3.common.DataReader
import androidx.media3.extractor.Extractor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CmafSampleQueueOutputTest {
    @Test
    fun encryptedCmafSamplesRetainDecoderCryptoMetadata() {
        val bytes = javaClass.classLoader!!
            .getResourceAsStream("media/sample_fragmented_widevine.mp4")!!
            .use { it.readBytes() }
        val extractor = CmafExtractorPrototype.createExtractor()
        val output = CmafSampleQueueOutput()
        try {
            assertTrue("Encrypted fixture must sniff as fragmented MP4", extractor.sniff(newInput(bytes, 0)))
            extractor.init(output)
            var input = newInput(bytes, 0)
            val seek = androidx.media3.extractor.PositionHolder()
            var result: Int
            var reads = 0
            do {
                check(++reads <= 20_000) { "Extractor exceeded bounded read loop" }
                result = extractor.read(input, seek)
                if (result == Extractor.RESULT_SEEK) {
                    check(seek.position in 0..bytes.size.toLong()) {
                        "Extractor requested out-of-range seek ${seek.position}"
                    }
                    extractor.seek(seek.position, 0)
                    input = newInput(bytes, seek.position.toInt())
                }
            } while (result == Extractor.RESULT_CONTINUE || result == Extractor.RESULT_SEEK)

            assertEquals(Extractor.RESULT_END_OF_INPUT, result)
            assertNotNull("Extractor should publish a seek map", output.seekMap)
            assertTrue("Extractor should create at least one sample queue", output.sampleQueues.isNotEmpty())
            assertTrue(
                "Encrypted track format should retain DRM initialization data",
                output.sampleQueues.values.any { it.getUpstreamFormat()?.drmInitData != null }
            )

            val holder = FormatHolder()
            val buffer = DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_NORMAL)
            var sampleCount = 0
            var encryptedSampleCount = 0
            for (queue: SampleQueue in output.sampleQueues.values) {
                var trackReads = 0
                while (true) {
                    check(++trackReads <= 10_000) { "Sample queue read loop exceeded bound" }
                    buffer.clear()
                    val readResult = queue.read(holder, buffer, /* readFlags= */ 0, /* loadingFinished= */ true)
                    when (readResult) {
                        C.RESULT_FORMAT_READ -> continue
                        C.RESULT_BUFFER_READ -> {
                            if (buffer.isEndOfStream) break
                            sampleCount++
                            if (buffer.isEncrypted) {
                                encryptedSampleCount++
                                assertTrue("Encrypted samples must have positive sample size", buffer.data!!.remaining() > 0)
                                assertTrue("Encrypted samples must expose subsample crypto metadata", buffer.cryptoInfo.numSubSamples > 0)
                                assertNotNull("Encrypted samples must expose an IV", buffer.cryptoInfo.iv)
                                assertTrue("Encrypted sample IV must not be empty", buffer.cryptoInfo.iv.isNotEmpty())
                                assertNotNull("Encrypted samples must expose a key ID", buffer.cryptoInfo.key)
                                assertTrue("Encrypted sample key ID must not be empty", buffer.cryptoInfo.key.isNotEmpty())
                                assertTrue(
                                    "Encrypted samples must use a recognized AES mode",
                                    buffer.cryptoInfo.mode == C.CRYPTO_MODE_AES_CTR ||
                                        buffer.cryptoInfo.mode == C.CRYPTO_MODE_AES_CBC
                                )
                            }
                        }
                        C.RESULT_NOTHING_READ -> break
                        else -> error("Unexpected SampleQueue result: $readResult")
                    }
                }
            }
            assertTrue("At least one media sample must be read through SampleQueue", sampleCount > 0)
            assertTrue("At least one encrypted sample must reach DecoderInputBuffer", encryptedSampleCount > 0)
        } finally {
            output.close()
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
}
