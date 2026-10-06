package app.f1multiview.media

import android.content.Context
import android.media.MediaFormat
import android.os.Build
import android.os.Handler
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.Effect
import androidx.media3.common.Format
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.mediacodec.MediaCodecAdapter
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.mediacodec.SynchronousMediaCodecAdapter
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer
import androidx.media3.exoplayer.video.PlaybackVideoGraphWrapper
import androidx.media3.exoplayer.video.VideoFrameReleaseControl
import androidx.media3.exoplayer.video.VideoRendererEventListener
import androidx.media3.effect.DefaultVideoFrameProcessor
import androidx.media3.effect.SingleInputVideoGraph
import app.f1multiview.media.protectedhdr.ProtectedHlgGlObjectsProvider
import app.f1multiview.media.protectedhdr.ProtectedHdrCapabilitiesProbe

class F1TvRenderersFactory(context: Context) : DefaultRenderersFactory(context) {
    private val appContext=context.applicationContext
    init {
        setEnableDecoderFallback(true)
        // Diagnostics only; do not gate playback on extension strings because some
        // TV firmware implements protected HDR without advertising every extension.
        ProtectedHdrCapabilitiesProbe.probe()
    }

    private fun needsSynchronousCodecQueueing(): Boolean {
        val identity=listOf(Build.BRAND,Build.MANUFACTURER,Build.MODEL,Build.DEVICE,Build.PRODUCT).joinToString(" ").lowercase()
        return "google tv streamer" in identity || "kirkwood" in identity
    }

    override fun buildVideoRenderers(
        context: Context, extensionRendererMode: Int, mediaCodecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean, eventHandler: Handler, eventListener: VideoRendererEventListener,
        allowedVideoJoiningTimeMs: Long, out: ArrayList<Renderer>
    ) {
        super.buildVideoRenderers(context,extensionRendererMode,mediaCodecSelector,enableDecoderFallback,eventHandler,eventListener,allowedVideoJoiningTimeMs,out)
        for(index in out.indices) {
            if(out[index] !is MediaCodecVideoRenderer) continue
            val sync=if(needsSynchronousCodecQueueing()) SynchronousMediaCodecAdapter.Factory() else MediaCodecAdapter.Factory.DEFAULT
            out[index]=F1HdrMediaCodecVideoRenderer(
                appContext,sync,mediaCodecSelector,allowedVideoJoiningTimeMs,enableDecoderFallback,eventHandler,eventListener
            )
        }
    }
}

@androidx.media3.common.util.UnstableApi
private class F1HdrMediaCodecVideoRenderer(
    context:Context,
    codecAdapterFactory:MediaCodecAdapter.Factory,
    mediaCodecSelector:MediaCodecSelector,
    allowedVideoJoiningTimeMs:Long,
    enableDecoderFallback:Boolean,
    eventHandler:Handler,
    eventListener:VideoRendererEventListener
):MediaCodecVideoRenderer(
    Builder(context)
        .setCodecAdapterFactory(codecAdapterFactory)
        .setMediaCodecSelector(mediaCodecSelector)
        .setAllowedJoiningTimeMs(allowedVideoJoiningTimeMs)
        .setEnableDecoderFallback(enableDecoderFallback)
        .setEventHandler(eventHandler)
        .setEventListener(eventListener)
        .setMaxDroppedFramesToNotify(DefaultRenderersFactory.MAX_DROPPED_VIDEO_FRAME_COUNT_TO_NOTIFY)
) {
    override fun getMediaFormat(
        format:Format,codecMimeType:String,codecMaxValues:MediaCodecVideoRenderer.CodecMaxValues,
        codecOperatingRate:Float,deviceNeedsNoPostProcessWorkaround:Boolean,tunnelingAudioSessionId:Int
    ):MediaFormat {
        val mf=super.getMediaFormat(format,codecMimeType,codecMaxValues,codecOperatingRate,deviceNeedsNoPostProcessWorkaround,tunnelingAudioSessionId)
        if(isF1UhdHlg(format,codecMimeType)) {
            // Mirror the reference Android TV path: preserve source frame rate and
            // avoid an injected operating-rate hint for the 4K HLG path.
            mf.setInteger("priority",0)
            if(format.frameRate>0f) mf.setFloat("frame-rate",format.frameRate)
            mf.setInteger("rotation-degrees",0)
            Log.i("F1TvRenderersFactory",
                "Protected UHD/HLG MediaCodec format: " + format.width + "x" + format.height +
                    " fps=" + format.frameRate + " mime=" + format.sampleMimeType)
        }
        return mf
    }

    override fun getCodecOperatingRateV23(operatingRate:Float,format:Format,streamFormats:Array<Format>):Float {
        // Keep the protected UHD/HLG path free of a Media3 operating-rate override.
        return if(isF1UhdHlg(format,format.sampleMimeType.orEmpty())) {
            Log.i("F1TvRenderersFactory",
                "Suppressing codec operating-rate for protected UHD/HLG " + format.width + "x" + format.height)
            -1f
        } else super.getCodecOperatingRateV23(operatingRate,format,streamFormats)
    }

    override fun createPlaybackVideoGraphWrapper(context:Context,videoFrameReleaseControl:VideoFrameReleaseControl):PlaybackVideoGraphWrapper {
        // Force the VideoSink/GL path to stay alive so the protected EGL output
        // surface is actually used instead of being optimized away.
        setVideoEffects(emptyList<Effect>())
        val processor=DefaultVideoFrameProcessor.Factory.Builder()
            .setGlObjectsProvider(ProtectedHlgGlObjectsProvider(context))
            .build()
        return PlaybackVideoGraphWrapper.Builder(context,videoFrameReleaseControl)
            .setEnablePlaylistMode(true)
            .setVideoGraphFactory(SingleInputVideoGraph.Factory(processor))
            .build()
    }

    private fun isF1UhdHlg(format:Format,codecMime:String):Boolean =
        F1HdrFormatClassifier.isUhdHlg(format, codecMime)
}
