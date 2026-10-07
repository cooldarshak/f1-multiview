package app.f1multiview

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import app.f1multiview.ui.App
import app.f1multiview.viewmodel.MultiViewViewModel
import app.f1multiview.media.AppLogger
import app.f1multiview.media.HdrPresentationDiagnostics

class MainActivity : ComponentActivity() {
    private val vm: MultiViewViewModel by viewModels()

    private val storagePermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            AppLogger.retryPersistentStorage(this)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Screenshot capture is allowed at the window level. Protected F1 video
        // surfaces remain individually secure, so this does not weaken Widevine
        // content protection. UI Capture Mode removes those secure surfaces before
        // the user takes a screenshot.
        window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)

        AppLogger.initialize(this)
        requestLegacyStoragePermissionIfNeeded()
        HdrPresentationDiagnostics.log(this, "MainActivity")

        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        setContent {
            App(vm)
        }
    }

    private fun requestLegacyStoragePermissionIfNeeded() {
        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) return
        if (AppLogger.hasPersistentStorage()) return

        val permissions = buildList {
            if (ContextCompat.checkSelfPermission(
                    this@MainActivity,
                    Manifest.permission.READ_EXTERNAL_STORAGE
                ) != PackageManager.PERMISSION_GRANTED
            ) add(Manifest.permission.READ_EXTERNAL_STORAGE)
            if (ContextCompat.checkSelfPermission(
                    this@MainActivity,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
                ) != PackageManager.PERMISSION_GRANTED
            ) add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }

        if (permissions.isNotEmpty()) {
            storagePermissionLauncher.launch(permissions.toTypedArray())
        } else {
            AppLogger.retryPersistentStorage(this)
        }
    }
}
