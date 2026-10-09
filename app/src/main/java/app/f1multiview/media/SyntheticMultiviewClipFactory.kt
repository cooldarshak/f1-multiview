package app.f1multiview.media

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.SystemClock
import java.io.File
import java.nio.ByteBuffer

/**
 * Creates short, clear H.264 test clips locally for the isolated multiview prototype.
 *
 * This is deliberately independent of F1 authentication, network access, and DRM. It writes
 * ordinary MP4 fixtures into app cache so the prototype can exercise real MediaExtractor and
 * MediaCodec decoder instances rather than animating placeholder textures.
 */
internal object SyntheticMultiviewClipFactory {
    data class ClipPair(val left: File, val right: File)

    private const val WIDTH = 320
    private const val HEIGHT = 180
    private const val FRAME_RATE = 15
    private const val FRAME_COUNT = 90
    private const val BIT_RATE = 450_000
    private const val MIME = MediaFormat.MIMETYPE_VIDEO_AVC

    fun createPair(context: Context): ClipPair {
        val directory = File(context.cacheDir, "synthetic-multiview").apply {
            if (!exists() && !mkdirs()) error("Unable to create synthetic clip directory")
        }
        val left = File(directory, "synthetic-left.mp4")
        val right = File(directory, "synthetic-right.mp4")
        if (!isUsable(left)) encodeClip(left, 0)
        if (!isUsable(right)) encodeClip(right, 1)
        check(isUsable(left) && isUsable(right)) { "Synthetic clip generation produced an invalid file" }
        return ClipPair(left, right)
    }

    private fun isUsable(file: File): Boolean = file.isFile && file.length() > 1_024L

    private fun encodeClip(output: File, variant: Int) {
        output.parentFile?.mkdirs()
        if (output.exists() && !output.delete()) error("Unable to replace synthetic clip: ${output.name}")

        val format = MediaFormat.createVideoFormat(MIME, WIDTH, HEIGHT).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
            setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
            }
        }

        val encoder = MediaCodec.createEncoderByType(MIME)
        var muxer: MediaMuxer? = null
        var muxerStarted = false
        var trackIndex = -1
        var outputEos = false
        var inputEosQueued = false
        var frame = 0
        val info = MediaCodec.BufferInfo()
        val deadlineMs = SystemClock.elapsedRealtime() + 30_000L

        try {
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder.start()
            muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            // Keep feeding frames while draining output. Draining after every input prevents
            // encoder output buffers from filling and stalling the synthetic source.
            while (!outputEos) {
                check(SystemClock.elapsedRealtime() < deadlineMs) {
                    "Encoder timed out while generating ${output.name}"
                }
                if (frame < FRAME_COUNT && !inputEosQueued) {
                    val inputIndex = encoder.dequeueInputBuffer(10_000)
                    if (inputIndex >= 0) {
                        val image = encoder.getInputImage(inputIndex)
                            ?: error("Encoder does not expose flexible YUV input images on this device")
                        try {
                            fillImage(image, frame, variant)
                        } finally {
                            image.close()
                        }
                        encoder.queueInputBuffer(
                            inputIndex,
                            0,
                            imageDataSize(),
                            frameTimeUs(frame),
                            0
                        )
                        frame++
                    }
                } else if (!inputEosQueued) {
                    val inputIndex = encoder.dequeueInputBuffer(10_000)
                    if (inputIndex >= 0) {
                        encoder.queueInputBuffer(
                            inputIndex, 0, 0, frameTimeUs(FRAME_COUNT),
                            MediaCodec.BUFFER_FLAG_END_OF_STREAM
                        )
                        inputEosQueued = true
                    }
                }

                var drainedThisPass = false
                while (true) {
                    val outputIndex = encoder.dequeueOutputBuffer(info, 0)
                    when {
                        outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> break
                        outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            check(!muxerStarted) { "Encoder output format changed more than once" }
                            trackIndex = muxer.addTrack(encoder.outputFormat)
                            muxer.start()
                            muxerStarted = true
                        }
                        outputIndex >= 0 -> {
                            val buffer = encoder.getOutputBuffer(outputIndex)
                            if (info.size > 0) {
                                check(muxerStarted && buffer != null) {
                                    "Encoder produced samples before its output format was ready"
                                }
                                buffer.position(info.offset)
                                buffer.limit(info.offset + info.size)
                                muxer.writeSampleData(trackIndex, buffer, info)
                            }
                            outputEos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                            encoder.releaseOutputBuffer(outputIndex, false)
                            drainedThisPass = true
                        }
                    }
                    if (outputEos) break
                }

                // A broken codec must not spin forever if it stops accepting input and output.
                if (!drainedThisPass && inputEosQueued && !outputEos) {
                    SystemClock.sleep(2L)
                }
            }
            check(muxerStarted) { "Encoder never produced a muxable output format" }
        } catch (failure: Throwable) {
            output.delete()
            throw IllegalStateException("Unable to generate synthetic H.264 clip ${output.name}", failure)
        } finally {
            runCatching { encoder.stop() }
            encoder.release()
            if (muxerStarted) runCatching { muxer?.stop() }
            muxer?.release()
        }
    }

    private fun fillImage(image: android.media.Image, frame: Int, variant: Int): Int {
        val planes = image.planes
        require(planes.size == 3) { "Expected a three-plane YUV420 encoder input" }
        val yBase = if (variant == 0) 64 else 160
        val movingBandStart = (frame * 3) % (HEIGHT - 24)
        val markerStart = (frame * 5 + variant * 37) % (WIDTH - 48)
        val yPlane = planes[0]
        val yBuffer = yPlane.buffer
        for (row in 0 until HEIGHT) {
            for (column in 0 until WIDTH) {
                val band = row >= movingBandStart && row < movingBandStart + 24
                val marker = column >= markerStart && column < markerStart + 48
                val value = when {
                    band && marker -> 235
                    band -> (yBase + 48).coerceAtMost(220)
                    else -> yBase
                }
                yBuffer.put(row * yPlane.rowStride + column * yPlane.pixelStride, value.toByte())
            }
        }
        fillChromaPlane(planes[1], WIDTH / 2, HEIGHT / 2, 128)
        fillChromaPlane(planes[2], WIDTH / 2, HEIGHT / 2, 128)
        return imageDataSize()
    }

    private fun fillChromaPlane(
        plane: android.media.Image.Plane,
        width: Int,
        height: Int,
        value: Int
    ) {
        val buffer = plane.buffer
        for (row in 0 until height) {
            for (column in 0 until width) {
                buffer.put(row * plane.rowStride + column * plane.pixelStride, value.toByte())
            }
        }
    }

    private fun imageDataSize(): Int = WIDTH * HEIGHT * 3 / 2

    private fun frameTimeUs(frame: Int): Long = frame * 1_000_000L / FRAME_RATE
}
