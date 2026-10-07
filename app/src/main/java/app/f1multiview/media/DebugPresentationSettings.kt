package app.f1multiview.media

import android.content.Context

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

    fun isScreenshotModeEnabled(context: Context): Boolean {
        val value = context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_SCREENSHOT_MODE, false)
        cached = value
        return value
    }

    fun setScreenshotMode(context: Context, enabled: Boolean) {
        cached = enabled
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_SCREENSHOT_MODE, enabled)
            .apply()
    }

    fun isScreenshotModeEnabled(): Boolean = cached
}