package app.f1multiview.media

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import android.util.Log
import android.view.Display

object HdrPresentationDiagnostics {
    fun log(context: Context, source: String) {
        runCatching {
            val dm = context.getSystemService(DisplayManager::class.java)
            val display = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) context.display else @Suppress("DEPRECATION") dm?.getDisplay(Display.DEFAULT_DISPLAY)
            if (display == null) return
            val hdrTypes = display.hdrCapabilities.supportedHdrTypes.joinToString(",") { type ->
                when (type) {
                    Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION -> "DOLBY_VISION"
                    Display.HdrCapabilities.HDR_TYPE_HDR10 -> "HDR10"
                    Display.HdrCapabilities.HDR_TYPE_HLG -> "HLG"
                    Display.HdrCapabilities.HDR_TYPE_HDR10_PLUS -> "HDR10_PLUS"
                    else -> type.toString()
                }
            }
            Log.i("F1HDR", "source=" + source + " mode=" + display.mode.physicalWidth + "x" + display.mode.physicalHeight + "@" + display.mode.refreshRate + " hdrTypes=[" + hdrTypes + "] wideColor=" + display.isWideColorGamut)
        }.onFailure { Log.w("F1HDR", "HDR display diagnostics failed", it) }
    }
}