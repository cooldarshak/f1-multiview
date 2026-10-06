package app.f1multiview.media

import android.hardware.DataSpace
import android.os.Build
import android.util.Log
import android.view.SurfaceView
import android.view.SurfaceControl

object HdrSurfaceHints {
    fun apply(surfaceView:SurfaceView?,source:String) {
        if(surfaceView==null||Build.VERSION.SDK_INT<34)return
        runCatching { surfaceView.setSurfaceLifecycle(SurfaceView.SURFACE_LIFECYCLE_FOLLOWS_ATTACHMENT) }
            .onFailure { Log.w("HdrSurfaceHints","Surface lifecycle failed: $source",it) }
    }
    fun applyHlg(surfaceView:SurfaceView?,source:String) {
        if(surfaceView==null||Build.VERSION.SDK_INT<34)return
        runCatching {
            val control=surfaceView.surfaceControl
            if(control!=null&&control.isValid) SurfaceControl.Transaction().setDataSpace(control,DataSpace.DATASPACE_BT2020_HLG).apply()
        }.onFailure { Log.w("HdrSurfaceHints","HLG dataspace failed: $source",it) }
    }
}
