package app.f1multiview.media

import android.media.MediaCodec
import android.media.MediaFormat
import android.view.Surface
import java.nio.ByteBuffer

/**
 * Owns the physical decoder for OpenTME.
 *
 * This class is deliberately independent of the logical-feed model: adding/removing
 * tiles changes merger selection only. It must never create another MediaCodec.
 */
private object HevcCodecConfig {
    fun toAnnexB(data: ByteArray): ByteArray {
        if (data.size < 23 || data[0].toInt() != 1) return data
        val arrayCount = data[22].toInt() and 0x7f
        var offset = 23
        val out = java.io.ByteArrayOutputStream()
        repeat(arrayCount) {
            if (offset + 3 > data.size) return data
            offset += 1 // array completeness + NAL unit type
            if (offset + 2 > data.size) return data
            val count = ((data[offset].toInt() and 0xff) shl 8) or (data[offset + 1].toInt() and 0xff)
            offset += 2
            repeat(count) {
                if (offset + 2 > data.size) return data
                val size = ((data[offset].toInt() and 0xff) shl 8) or (data[offset + 1].toInt() and 0xff)
                offset += 2
                if (offset + size > data.size) return data
                out.write(byteArrayOf(0, 0, 0, 1))
                out.write(data, offset, size)
                offset += size
            }
        }
        return out.toByteArray().takeIf { it.isNotEmpty() } ?: data
    }
}

class TmeNativeDecoder(
    private val mimeType: String = MediaFormat.MIMETYPE_VIDEO_HEVC
) : AutoCloseable {
    private var codec: MediaCodec? = null
    private var outputSurface: Surface? = null
    private var started = false
    private var configured = false
    private var queued = 0L
    private var rendered = 0L
    private var recreations = 0L
    private var everConfigured = false

    fun configure(surface: Surface, width: Int, height: Int, codecConfig: ByteArray? = null) {
        if (everConfigured) recreations++
        check(codec == null) { "TME decoder is already configured; selection changes must not recreate it" }
        require(width > 0 && height > 0)
        val format = MediaFormat.createVideoFormat(mimeType, width, height)
        format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 4 * 1024 * 1024)
        codecConfig?.let {
            format.setByteBuffer("csd-0", ByteBuffer.wrap(HevcCodecConfig.toAnnexB(it)))
        }
        val created = MediaCodec.createDecoderByType(mimeType)
        created.configure(format, surface, null, 0)
        codec = created
        outputSurface = surface
        everConfigured = true
        configured = true
        AppLogger.i("TME", "MEDIACODEC_CONFIGURED codec=${created.codecInfo.name} mime=$mimeType size=${width}x${height} surfaceValid=${surface.isValid}")
    }

    fun start() {
        check(configured) { "configure() must be called before start()" }
        check(!started) { "TME decoder already started" }
        codec!!.start()
        started = true
        AppLogger.i("TME", "MEDIACODEC_STARTED codec=${codec!!.codecInfo.name}")
    }

    fun queue(accessUnit: TmeMergedAccessUnit, timeoutUs: Long = 10_000L): Boolean {
        val c = codec ?: return false
        check(started) { "TME decoder is not started" }
        val index = c.dequeueInputBuffer(timeoutUs)
        if (index < 0) return false
        val input = c.getInputBuffer(index) ?: return false
        input.clear()
        input.put(accessUnit.payload)
        val flags = if (accessUnit.keyFrame) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
        c.queueInputBuffer(index, 0, accessUnit.payload.size, accessUnit.ptsUs, flags)
        queued++
        if (queued == 1L || queued % 30L == 0L) {
            AppLogger.i("TME", "MEDIACODEC_QUEUE count=$queued ptsUs=${accessUnit.ptsUs} keyFrame=${accessUnit.keyFrame}")
        }
        return true
    }

    fun drain(timeoutUs: Long = 0L): Int {
        val c = codec ?: return 0
        var count = 0
        while (true) {
            val info = MediaCodec.BufferInfo()
            when (val index = c.dequeueOutputBuffer(info, timeoutUs)) {
                MediaCodec.INFO_TRY_AGAIN_LATER -> return count
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> Unit
                MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> Unit
                else -> {
                    if (index >= 0) {
                        c.releaseOutputBuffer(index, true)
                        rendered++
                        if (rendered == 1L || rendered % 30L == 0L) {
                            AppLogger.i("TME", "MEDIACODEC_RENDERED count=$rendered ptsUs=${info.presentationTimeUs}")
                        }
                        count++
                    }
                }
            }
        }
    }

    fun telemetry(): TmeDecoderTelemetry = TmeDecoderTelemetry(
        codecName = codec?.codecInfo?.name,
        configured = configured,
        started = started,
        queuedAccessUnits = queued,
        renderedAccessUnits = rendered,
        decoderRecreationCount = recreations,
        outputSurfaceAttached = outputSurface?.isValid == true
    )

    override fun close() {
        codec?.let {
            runCatching { if (started) it.stop() }
            runCatching { it.release() }
        }
        codec = null
        outputSurface = null
        configured = false
        started = false
    }
}
