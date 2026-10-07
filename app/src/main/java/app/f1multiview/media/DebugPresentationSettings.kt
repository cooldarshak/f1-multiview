package app.f1multiview.media

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Debug-only playback presentation switch.
 *
 * When enabled, the protected EGL presentation path is bypassed so normal Android
 * screenshots can capture the video surface during testing. DRM/authentication and
 * decoder selection are otherwise unchanged.
 *
 * This is intentionally persisted so a tester does not need to re-enable it after
 * restarting the app. Release builds should expose no UI for this switch.
 */
object DebugPresentationSettings {
    private const val PREFS = "f1_multiview_debug_settings"
    private const val KEY_SCREENSHOT_MODE = "screenshot_mode"

    @Volatile
    private var cached = false

    private val _screenshotMode = MutableStateFlow(false)
    val screenshotMode: StateFlow<Boolean> = _screenshotMode.asStateFlow()

    fun isScreenshotModeEnabled(context: Context): Boolean {
        val value = context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_SCREENSHOT_MODE, false)
        cached = value
        _screenshotMode.value = value
        return value
    }

    fun setScreenshotMode(context: Context, enabled: Boolean) {
        cached = enabled
        _screenshotMode.value = enabled
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_SCREENSHOT_MODE, enabled)
            .apply()
    }

    /**
     * Enters a transient UI-capture state. It intentionally does not persist.
     * Protected video surfaces are removed from the view hierarchy while playback
     * continues, so Android's normal screenshot path can capture the Compose UI.
     */
    fun enterUiCaptureMode() {
        cached = true
        _screenshotMode.value = true
    }

    fun exitUiCaptureMode(context: Context) {
        setScreenshotMode(context, false)
    }

    fun isScreenshotModeEnabled(): Boolean = cached
}