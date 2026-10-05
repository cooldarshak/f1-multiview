package app.f1multiview

import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import app.f1multiview.ui.App
import app.f1multiview.viewmodel.MultiViewViewModel

class MainActivity : ComponentActivity() {
    private val vm: MultiViewViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        setContent {
            App(vm)
        }

        if (isAndroidTv()) {
            window.decorView.post {
                // Put the window into keyboard/D-pad navigation mode. Compose clickable
                // controls are focus targets and the framework then performs spatial
                // D-pad traversal between them.
                window.decorView.isFocusableInTouchMode = true
                window.decorView.clearFocus()
                window.decorView.focusSearch(View.FOCUS_DOWN)?.requestFocus()
            }
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // Let the normal Android/Compose focus system consume D-pad navigation and
        // select/back events. This is intentionally not intercepted here so player
        // and dialog controls retain their normal key handling.
        return super.dispatchKeyEvent(event)
    }

    private fun isAndroidTv(): Boolean {
        val uiMode = resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_TYPE_MASK
        return uiMode == android.content.res.Configuration.UI_MODE_TYPE_TELEVISION ||
            packageManager.hasSystemFeature("android.software.leanback")
    }
}
