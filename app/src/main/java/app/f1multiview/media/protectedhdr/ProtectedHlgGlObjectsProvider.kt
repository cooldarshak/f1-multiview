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

/**
 * Protected EGL provider for F1 UHD/HLG.
 *
 * Media3 can request an ordinary 8-bit EGL config. Protected HLG output needs
 * a 10-bit-capable window config, so choose that config before creating the
 * protected context instead of blindly reusing Media3's incoming attributes.
 *
 * Every protected operation has a normal Media3 fallback.
 */
class ProtectedHlgGlObjectsProvider : GlObjectsProvider {
    private val delegate = DefaultGlObjectsProvider()
    private val protectedContexts = mutableListOf<EGLContext>()

    override fun createEglContext(
        display: EGLDisplay,
        openGlVersion: Int,
        configAttributes: IntArray
    ): EGLContext {
        val config = chooseProtectedHlgConfig(display, true)
            ?: chooseConfig(display, configAttributes)
            ?: chooseProtectedHlgConfig(display, false)

        if (config == null) {
            Log.w(TAG, "No EGL config available for protected HLG; using Media3 default context")
            return delegate.createEglContext(display, openGlVersion, configAttributes)
        }

        clearEglError()
        val context = EGL14.eglCreateContext(
            display,
            config,
            EGL14.EGL_NO_CONTEXT,
            intArrayOf(
                EGL14.EGL_CONTEXT_CLIENT_VERSION, openGlVersion,
                EGL_PROTECTED_CONTENT_EXT, EGL14.EGL_TRUE,
                EGL14.EGL_NONE
            ),
            0
        )
        val error = EGL14.eglGetError()

        if (context != EGL14.EGL_NO_CONTEXT && error == EGL14.EGL_SUCCESS) {
            protectedContexts += context
            Log.i(TAG, "Created protected EGL context using a selected window config")
            return context
        }

        Log.w(TAG, "Protected EGL context failed: 0x" + error.toString(16) + "; falling back to Media3")
        return delegate.createEglContext(display, openGlVersion, configAttributes)
    }

    override fun createEglSurface(
        display: EGLDisplay,
        surface: Any,
        colorTransfer: Int,
        isEncoderInputSurface: Boolean
    ): EGLSurface {
        if (isEncoderInputSurface) {
            return delegate.createEglSurface(display, surface, colorTransfer, true)
        }

        // This provider is installed on the video graph, but the graph is also
        // used for SDR/FHD playback. Only request the protected BT.2020 HLG
        // surface when Media3 explicitly tells us the transfer is HLG.
        if (colorTransfer != C.COLOR_TRANSFER_HLG) {
            return delegate.createEglSurface(display, surface, colorTransfer, false)
        }

        val config = chooseProtectedHlgConfig(display, true)
            ?: chooseProtectedHlgConfig(display, false)

        if (config == null) {
            Log.w(TAG, "No protected HLG EGL window config; falling back to Media3 HLG surface")
            return delegate.createEglSurface(display, surface, colorTransfer, false)
        }

        clearEglError()
        val eglSurface = EGL14.eglCreateWindowSurface(
            display,
            config,
            surface,
            intArrayOf(
                EGL_PROTECTED_CONTENT_EXT, EGL14.EGL_TRUE,
                EGL_GL_COLORSPACE_KHR, EGL_GL_COLORSPACE_BT2020_HLG_EXT,
                EGL14.EGL_NONE
            ),
            0
        )
        val error = EGL14.eglGetError()

        if (eglSurface != EGL14.EGL_NO_SURFACE && error == EGL14.EGL_SUCCESS) {
            Log.i(TAG, "Created protected BT.2020 HLG EGL window surface")
            return eglSurface
        }

        Log.w(TAG, "Protected HLG EGL surface failed: 0x" + error.toString(16) + "; falling back to Media3")
        return delegate.createEglSurface(display, surface, colorTransfer, false)
    }

    override fun createFocusedPlaceholderEglSurface(
        eglContext: EGLContext,
        eglDisplay: EGLDisplay
    ): EGLSurface = delegate.createFocusedPlaceholderEglSurface(eglContext, eglDisplay)

    override fun createBuffersForTexture(
        texId: Int,
        width: Int,
        height: Int
    ): GlTextureInfo = delegate.createBuffersForTexture(texId, width, height)

    override fun release(eglDisplay: EGLDisplay) {
        protectedContexts.forEach { context ->
            runCatching { EGL14.eglDestroyContext(eglDisplay, context) }
        }
        protectedContexts.clear()
        delegate.release(eglDisplay)
    }

    private fun chooseProtectedHlgConfig(display: EGLDisplay, require10Bit: Boolean): EGLConfig? {
        val attrs = intArrayOf(
            EGL14.EGL_RED_SIZE, if (require10Bit) 10 else 8,
            EGL14.EGL_GREEN_SIZE, if (require10Bit) 10 else 8,
            EGL14.EGL_BLUE_SIZE, if (require10Bit) 10 else 8,
            EGL14.EGL_ALPHA_SIZE, if (require10Bit) 2 else 8,
            EGL14.EGL_DEPTH_SIZE, 0,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
            EGL14.EGL_NONE
        )
        val config = chooseConfig(display, attrs)
        if (config != null && require10Bit) {
            Log.i(TAG, "Selected 10-bit EGL window config for protected HLG")
        }
        return config
    }

    private fun chooseConfig(display: EGLDisplay, attrs: IntArray): EGLConfig? {
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        if (!EGL14.eglChooseConfig(display, attrs, 0, configs, 0, 1, count, 0)) return null
        if (count[0] <= 0) return null
        return configs[0]
    }

    private fun clearEglError() {
        while (EGL14.eglGetError() != EGL14.EGL_SUCCESS) { }
    }

    private companion object {
        const val EGL_PROTECTED_CONTENT_EXT = 0x32C0
        const val EGL_GL_COLORSPACE_KHR = 0x309D
        const val EGL_GL_COLORSPACE_BT2020_HLG_EXT = 0x3540
        const val TAG = "ProtectedHlgGlObjectsProvider"
    }
}
