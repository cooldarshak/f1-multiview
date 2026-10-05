package app.f1multiview.media.protectedhdr

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.GlObjectsProvider
import androidx.media3.common.GlTextureInfo
import androidx.media3.effect.DefaultGlObjectsProvider

class ProtectedHlgGlObjectsProvider : GlObjectsProvider {
    private val delegate = DefaultGlObjectsProvider()
    private val protectedContexts = mutableListOf<EGLContext>()

    override fun createEglContext(display:EGLDisplay, openGlVersion:Int, configAttributes:IntArray):EGLContext {
        val config = chooseConfig(display, configAttributes) ?: return delegate.createEglContext(display,openGlVersion,configAttributes)
        clearEglError()
        val ctx=EGL14.eglCreateContext(display,config,EGL14.EGL_NO_CONTEXT,intArrayOf(
            EGL14.EGL_CONTEXT_CLIENT_VERSION,openGlVersion,
            EGL_PROTECTED_CONTENT_EXT,EGL14.EGL_TRUE,EGL14.EGL_NONE),0)
        val err=EGL14.eglGetError()
        if(ctx!=EGL14.EGL_NO_CONTEXT && err==EGL14.EGL_SUCCESS){ protectedContexts += ctx; return ctx }
        Log.w(TAG,"Protected EGL context unavailable: 0x"+err.toString(16))
        return delegate.createEglContext(display,openGlVersion,configAttributes)
    }

    override fun createEglSurface(display:EGLDisplay,surface:Any,colorTransfer:Int,isEncoderInputSurface:Boolean):EGLSurface {
        if(isEncoderInputSurface) return delegate.createEglSurface(display,surface,colorTransfer,true)
        val config=choose10BitConfig(display) ?: return delegate.createEglSurface(display,surface,C.COLOR_TRANSFER_SDR,false)
        clearEglError()
        val out=EGL14.eglCreateWindowSurface(display,config,surface,intArrayOf(
            EGL_PROTECTED_CONTENT_EXT,EGL14.EGL_TRUE,
            EGL_GL_COLORSPACE_KHR,EGL_GL_COLORSPACE_BT2020_HLG_EXT,
            EGL14.EGL_NONE),0)
        val err=EGL14.eglGetError()
        if(out!=EGL14.EGL_NO_SURFACE && err==EGL14.EGL_SUCCESS) return out
        Log.w(TAG,"Protected HLG EGL surface unavailable: 0x"+err.toString(16))
        return delegate.createEglSurface(display,surface,C.COLOR_TRANSFER_SDR,false)
    }

    override fun createFocusedPlaceholderEglSurface(eglContext:EGLContext,eglDisplay:EGLDisplay):EGLSurface =
        delegate.createFocusedPlaceholderEglSurface(eglContext,eglDisplay)

    override fun createBuffersForTexture(texId:Int,width:Int,height:Int):GlTextureInfo =
        delegate.createBuffersForTexture(texId,width,height)

    override fun release(eglDisplay:EGLDisplay) {
        protectedContexts.forEach { EGL14.eglDestroyContext(eglDisplay,it) }
        protectedContexts.clear()
        delegate.release(eglDisplay)
    }

    private fun choose10BitConfig(display:EGLDisplay):EGLConfig? = chooseConfig(display,intArrayOf(
        EGL14.EGL_RED_SIZE,10,EGL14.EGL_GREEN_SIZE,10,EGL14.EGL_BLUE_SIZE,10,EGL14.EGL_ALPHA_SIZE,2,
        EGL14.EGL_DEPTH_SIZE,0,EGL14.EGL_RENDERABLE_TYPE,EGL14.EGL_OPENGL_ES2_BIT,
        EGL14.EGL_SURFACE_TYPE,EGL14.EGL_WINDOW_BIT,EGL14.EGL_NONE))
    private fun chooseConfig(display:EGLDisplay,attrs:IntArray):EGLConfig? {
        val configs=arrayOfNulls<EGLConfig>(1); val count=IntArray(1)
        if(!EGL14.eglChooseConfig(display,attrs,0,configs,0,1,count,0)||count[0]<=0) return null
        return configs[0]
    }
    private fun clearEglError(){ while(EGL14.eglGetError()!=EGL14.EGL_SUCCESS){} }
    private companion object {
        const val EGL_PROTECTED_CONTENT_EXT=0x32C0
        const val EGL_GL_COLORSPACE_KHR=0x309D
        const val EGL_GL_COLORSPACE_BT2020_HLG_EXT=0x3540
        const val TAG="ProtectedHlgGlObjectsProvider"
    }
}
