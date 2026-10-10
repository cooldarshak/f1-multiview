package app.f1multiview.media

import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.extractor.DefaultExtractorInput
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.PositionHolder
import java.io.IOException

/**
 * Fetches one authorized DASH CMAF initialization/media pair into bounded storage and extracts it
 * into app-owned SampleQueues. This is a first-segment integration step, not a live playback loop or
 * secure decoder; extraction alone must never open the production multi-feed gate.
 */
@OptIn(UnstableApi::class)
internal class AuthorizedCmafSegmentExtractor(
    private val dataSourceFactory: DataSource.Factory,
    private val maxInitializationBytes: Int = DEFAULT_MAX_INITIALIZATION_BYTES,
    private val maxMediaSegmentBytes: Int = DEFAULT_MAX_MEDIA_SEGMENT_BYTES
) {
    data class Result(val bytesFetched: Int, val trackCount: Int, val seekMapAvailable: Boolean)

    init {
        require(maxInitializationBytes > 0) { "Initialization segment budget must be positive" }
        require(maxMediaSegmentBytes > 0) { "Media segment budget must be positive" }
    }

    @Throws(IOException::class)
    fun extractFirstSegment(
        plan: AuthorizedDashManifestResolver.VideoPlan,
        output: CmafSampleQueueOutput
    ): Result {
        val payload = ByteArray(maxInitializationBytes + maxMediaSegmentBytes)
        val initializationBytes = BoundedDataSpecLoader.readInto(
            dataSourceFactory, plan.initialization, payload, 0, maxInitializationBytes
        )
        check(initializationBytes > 0) { "DASH initialization segment is empty" }
        val mediaBytes = BoundedDataSpecLoader.readInto(
            dataSourceFactory,
            plan.firstMediaSegment.dataSpec,
            payload,
            initializationBytes,
            maxMediaSegmentBytes
        )
        check(mediaBytes > 0) { "DASH media segment is empty" }
        val totalBytes = initializationBytes + mediaBytes

        val extractor = CmafExtractorPrototype.createExtractor()
        try {
            check(extractor.sniff(newInput(payload, 0, totalBytes))) {
                "Authorized DASH initialization/media pair is not recognized as fragmented MP4"
            }
            extractor.init(output)
            val seekPosition = PositionHolder()
            var input = newInput(payload, 0, totalBytes)
            var result: Int
            var reads = 0
            do {
                if (++reads > MAX_EXTRACTOR_READS) {
                    throw IOException("CMAF extractor exceeded its bounded read loop")
                }
                result = extractor.read(input, seekPosition)
                if (result == Extractor.RESULT_SEEK) {
                    val requested = seekPosition.position
                    if (requested < 0L || requested > totalBytes.toLong()) {
                        throw IOException("CMAF extractor requested out-of-range seek $requested/$totalBytes")
                    }
                    extractor.seek(
                        requested,
                        if (requested >= initializationBytes) plan.firstMediaSegment.presentationTimeUs else 0L
                    )
                    input = newInput(payload, requested.toInt(), totalBytes)
                }
            } while (result == Extractor.RESULT_CONTINUE || result == Extractor.RESULT_SEEK)

            if (result != Extractor.RESULT_END_OF_INPUT) {
                throw IOException("CMAF extractor ended with unexpected result $result")
            }
            if (output.sampleQueues.isEmpty()) {
                throw IOException("CMAF extraction completed without creating sample queues")
            }
            return Result(totalBytes, output.sampleQueues.size, output.seekMap != null)
        } finally {
            extractor.release()
        }
    }

    private fun newInput(bytes: ByteArray, start: Int, limit: Int): DefaultExtractorInput =
        DefaultExtractorInput(ByteArrayDataReader(bytes, start, limit), start.toLong(), limit.toLong())

    private class ByteArrayDataReader(
        private val bytes: ByteArray,
        start: Int,
        private val limit: Int
    ) : DataReader {
        private var position = start

        override fun read(target: ByteArray, offset: Int, length: Int): Int {
            if (length == 0) return 0
            if (position >= limit) return C.RESULT_END_OF_INPUT
            val count = minOf(length, limit - position)
            bytes.copyInto(target, offset, position, position + count)
            position += count
            return count
        }
    }

    private companion object {
        const val DEFAULT_MAX_INITIALIZATION_BYTES = 2 * 1024 * 1024
        const val DEFAULT_MAX_MEDIA_SEGMENT_BYTES = 16 * 1024 * 1024
        const val MAX_EXTRACTOR_READS = 20_000
    }
}
