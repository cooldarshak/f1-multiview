package app.f1multiview.media

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.view.Surface
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.LockSupport

/**
 * Minimal clear-MP4 decoder used only by SyntheticMultiviewPrototypeActivity.
 * Each instance owns exactly one MediaCodec decoder and writes to one compositor input Surface.
 */
internal class SyntheticFeedDecoder(
    private val feedLabel: String,
    private val file: File,
    private val outputSurface: Surface,
    private val sharedPlaybackAnchorNs: Long,
    private val onStatus: (String) -> Unit
) {
    val label: String get() = feedLabel

    private val stopRequested = AtomicBoolean(false)
    private val outputFramesQueued = AtomicLong(0L)
    @Volatile private var worker: Thread? = null

    fun start() {
        check(worker == null) { "Decoder already started: $feedLabel" }
        worker = Thread(::decodeLoop, "synthetic-decoder-$feedLabel").apply {
            isDaemon = true
            start()
        }
    }

    fun stopAndJoin(timeoutMs: Long = 1_000L): Boolean {
        stopRequested.set(true)
        val thread = worker ?: return true
        if (thread !== Thread.currentThread()) {
            runCatching { thread.join(timeoutMs) }
        }
        return !thread.isAlive
    }

    private fun decodeLoop() {
        var extractor: MediaExtractor? = null
        var decoder: MediaCodec? = null
        try {
            require(file.isFile && file.length() > 1_024L) { "Synthetic input file is missing or empty" }
            extractor = MediaExtractor().apply { setDataSource(file.absolutePath) }
            var selectedTrack = -1
            var format: MediaFormat? = null
            for (index in 0 until extractor.trackCount) {
                val candidate = extractor.getTrackFormat(index)
                val mime = candidate.getString(MediaFormat.KEY_MIME).orEmpty()
                if (mime.startsWith("video/")) {
                    selectedTrack = index
                    format = candidate
                    break
                }
            }
            check(selectedTrack >= 0 && format != null) { "No video track in synthetic input" }
            extractor.selectTrack(selectedTrack)
            val mime = requireNotNull(format.getString(MediaFormat.KEY_MIME))
            val clipDurationUs = format.getLong(MediaFormat.KEY_DURATION)
            check(clipDurationUs > 0L) { "Synthetic clip has no positive duration: $clipDurationUs" }
            decoder = MediaCodec.createDecoderByType(mime)
            decoder.configure(format, outputSurface, null, 0)
            decoder.start()
            val codecName = decoder.name
            onStatus("$feedLabel decoder=$codecName configured durationUs=$clipDurationUs")
            decodeLoop(extractor, decoder, clipDurationUs)
        } catch (failure: Throwable) {
            if (!stopRequested.get()) {
                onStatus("$feedLabel ERROR ${failure.javaClass.simpleName}: ${failure.message ?: "unknown"}")
            }
        } finally {
            runCatching { decoder?.stop() }
            runCatching { decoder?.release() }
            runCatching { extractor?.release() }
            worker = null
            if (stopRequested.get()) onStatus("$feedLabel stopped frames=${outputFramesQueued.get()}")
        }
    }

    private fun decodeLoop(extractor: MediaExtractor, decoder: MediaCodec, clipDurationUs: Long) {
        val info = MediaCodec.BufferInfo()
        var inputEosQueued = false
        var loopIndex = 0L

        while (!stopRequested.get()) {
            if (!inputEosQueued) {
                val inputIndex = decoder.dequeueInputBuffer(10_000)
                if (inputIndex >= 0) {
                    val inputBuffer = decoder.getInputBuffer(inputIndex)
                    checkNotNull(inputBuffer) { "Decoder returned no input buffer" }
                    inputBuffer.clear()
                    val sampleSize = extractor.readSampleData(inputBuffer, 0)
                    if (sampleSize < 0) {
                        decoder.queueInputBuffer(
                            inputIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM
                        )
                        inputEosQueued = true
                    } else {
                        val sampleTimeUs = extractor.sampleTime.coerceAtLeast(0L)
                        val sampleFlags = if (
                            (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0
                        ) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                        decoder.queueInputBuffer(inputIndex, 0, sampleSize, sampleTimeUs, sampleFlags)
                        extractor.advance()
                    }
                }
            }

            var drainedOutput = false
            while (!stopRequested.get()) {
                val outputIndex = decoder.dequeueOutputBuffer(info, 0)
                when {
                    outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> break
                    outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        onStatus("$feedLabel output=${decoder.outputFormat}")
                    }
                    outputIndex >= 0 -> {
                        drainedOutput = true
                        val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        if (eos) {
                            decoder.releaseOutputBuffer(outputIndex, false)
                            // Loop the short synthetic clip on the same decoder so the test can
                            // observe steady-state rendering rather than only startup behavior.
                            extractor.seekTo(0L, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                            decoder.flush()
                            inputEosQueued = false
                            loopIndex++
                            onStatus("$feedLabel loop=$loopIndex timelineUs=${loopIndex * clipDurationUs}")
                            continue
                        }

                        if (info.size > 0) {
                            // Every feed uses the same monotonic epoch. Advancing loopIndex rather
                            // than resetting a per-decoder anchor preserves the shared timeline.
                            val timelineUs = loopIndex * clipDurationUs + info.presentationTimeUs.coerceAtLeast(0L)
                            val renderAtNs = sharedPlaybackAnchorNs + timelineUs * 1_000L
                            if (awaitPresentationTime(renderAtNs)) {
                                decoder.releaseOutputBuffer(outputIndex, renderAtNs)
                                val count = outputFramesQueued.incrementAndGet()
                                if (count == 1L || count % 30L == 0L) {
                                    onStatus("$feedLabel outputFramesQueued=$count")
                                }
                            } else {
                                decoder.releaseOutputBuffer(outputIndex, false)
                            }
                        } else {
                            decoder.releaseOutputBuffer(outputIndex, false)
                        }
                    }
                }
                if (outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER) break
            }

            if (!drainedOutput && inputEosQueued) {
                // The codec is waiting for its EOS output; avoid a hot spin while still
                // allowing stop requests to be observed promptly.
                Thread.sleep(2L)
            }
        }
    }

    /**
     * SurfaceTexture consumes queued buffers; assigning a timestamp alone is not a reliable
     * pacing mechanism for this path. Pace each decoder against the same monotonic epoch before
     * releasing the output buffer, so one feed cannot run ahead simply because it decodes faster.
     */
    private fun awaitPresentationTime(targetNs: Long): Boolean {
        while (!stopRequested.get()) {
            val remainingNs = targetNs - System.nanoTime()
            if (remainingNs <= 0L) return true
            LockSupport.parkNanos(minOf(remainingNs, 5_000_000L))
        }
        return false
    }
}
