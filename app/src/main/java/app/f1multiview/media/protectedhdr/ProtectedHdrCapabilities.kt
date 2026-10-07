package app.f1multiview.media.protectedhdr

import android.opengl.EGL14
import android.util.Log

data class ProtectedHdrCapabilities(
    val extensions: Set<String>,
    val hasProtectedContentExtension: Boolean,
    val hasBt2020HlgColorSpaceExtension: Boolean
) {
    val extensionAdvertisesProtectedHlg: Boolean
        get() = hasProtectedContentExtension && hasBt2020HlgColorSpaceExtension
}

/**
 * Diagnostics only. We intentionally do not gate playback on extension strings:
 * some TV firmware exposes protected/HDR EGL functionality without advertising
 * every extension reliably. The real provider still performs the protected
 * context/surface operation and falls back safely if the driver rejects it.
 */
object ProtectedHdrCapabilitiesProbe {
    private const val TAG = "ProtectedHdrCapabilities"

    @Volatile
    private var cached: ProtectedHdrCapabilities? = null

    fun probe(forceRefresh: Boolean = false): ProtectedHdrCapabilities {
        if (!forceRefresh) cached?.let { return it }

        return synchronized(this) {
            if (!forceRefresh) cached?.let { return it }

            val result = runCatching {
                val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
                if (display == EGL14.EGL_NO_DISPLAY) {
                    ProtectedHdrCapabilities(emptySet(), false, false)
                } else {
                    val version = IntArray(2)
                    if (!EGL14.eglInitialize(display, version, 0, version, 1)) {
                        ProtectedHdrCapabilities(emptySet(), false, false)
                    } else {
                        val extensions = EGL14.eglQueryString(display, EGL14.EGL_EXTENSIONS)
                            ?.split(' ')
                            ?.filter(String::isNotBlank)
                            ?.toSet()
                            .orEmpty()
                        EGL14.eglTerminate(display)
                        ProtectedHdrCapabilities(
                            extensions = extensions,
                            hasProtectedContentExtension = "EGL_EXT_protected_content" in extensions,
                            hasBt2020HlgColorSpaceExtension = "EGL_EXT_gl_colorspace_bt2020_hlg" in extensions
                        )
                    }
                }
            }.getOrElse {
                Log.w(TAG, "EGL capability probe failed", it)
                ProtectedHdrCapabilities(emptySet(), false, false)
            }.also { cached = it }

            Log.i(
                TAG,
                "EGL HDR capability advertisement: " +
                    "protectedContent=" + result.hasProtectedContentExtension +
                    " bt2020Hlg=" + result.hasBt2020HlgColorSpaceExtension +
                    " advertisedProtectedHlg=" + result.extensionAdvertisesProtectedHlg
            )
            result
        }
    }
}
