package app.f1multiview.media

import android.content.Context
import android.os.Build
import android.os.Handler
import android.util.Log
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.mediacodec.SynchronousMediaCodecAdapter
import androidx.media3.exoplayer.video.VideoRendererEventListener

class F1TvRenderersFactory(context: Context) : DefaultRenderersFactory(context) {
    init { setEnableDecoderFallback(true) }

    private fun needsSynchronousCodecQueueing(): Boolean {
        val identity = listOf(Build.BRAND, Build.MANUFACTURER, Build.MODEL, Build.DEVICE, Build.PRODUCT)
            .joinToString(" ").lowercase()
        return "google tv streamer" in identity || "kirkwood" in identity
    }

    override fun buildVideoRenderers(
        context: Context,
        extensionRendererMode: Int,
        mediaCodecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean,
        eventHandler: Handler,
        eventListener: VideoRendererEventListener,
        allowedVideoJoiningTimeMs: Long,
        out: ArrayList<Renderer>
    ) {
        super.buildVideoRenderers(context, extensionRendererMode, mediaCodecSelector, enableDecoderFallback, eventHandler, eventListener, allowedVideoJoiningTimeMs, out)
        if (!needsSynchronousCodecQueueing()) return
        for (index in out.indices) {
            if (out[index] is MediaCodecVideoRenderer) {
                Log.i("F1TvRenderersFactory", "Using synchronous MediaCodec adapter for ${Build.MODEL}")
                out[index] = MediaCodecVideoRenderer(
                    MediaCodecVideoRenderer.Builder(context)
                        .setCodecAdapterFactory(SynchronousMediaCodecAdapter.Factory())
                        .setMediaCodecSelector(mediaCodecSelector)
                        .setAllowedJoiningTimeMs(allowedVideoJoiningTimeMs)
                        .setEnableDecoderFallback(enableDecoderFallback)
                        .setEventHandler(eventHandler)
                        .setEventListener(eventListener)
                        .setMaxDroppedFramesToNotify(DefaultRenderersFactory.MAX_DROPPED_VIDEO_FRAME_COUNT_TO_NOTIFY)
                )
            }
        }
    }
}
