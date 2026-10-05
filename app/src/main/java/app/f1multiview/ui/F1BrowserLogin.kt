package app.f1multiview.ui

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.os.Handler
import android.os.Looper
import app.f1multiview.data.f1tv.F1TvApiClient
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.ui.unit.dp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import app.f1multiview.viewmodel.MultiViewViewModel
import java.net.URLDecoder
import org.json.JSONObject

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun F1BrowserLogin(onClose: () -> Unit, vm: MultiViewViewModel) {
    val handler = remember { Handler(Looper.getMainLooper()) }
    DisposableEffect(Unit) {
        onDispose {
            handler.removeCallbacksAndMessages(null)
        }
    }
    AlertDialog(onDismissRequest = onClose, title = { Text("Sign in to F1 TV") }, text = {
        AndroidView(modifier = Modifier.fillMaxWidth().height(520.dp), factory = { context ->
            WebView(context).apply {
                var captured = false
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.userAgentString = F1TvApiClient.BROWSER_UA
                CookieManager.getInstance().setAcceptCookie(true)
                CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

                fun captureToken() {
                    if (captured) return
                    val cm = CookieManager.getInstance()
                    val token = listOf(
                        "https://account.formula1.com/",
                        "https://formula1.com/",
                        "https://f1tv.formula1.com/"
                    ).asSequence()
                        .mapNotNull { readSubscriptionToken(cm.getCookie(it)) }
                        .firstOrNull()
                    if (token != null) {
                        captured = true
                        cm.flush()
                        vm.signInWithSessionToken(token)
                    } else {
                        handler.postDelayed({ captureToken() }, 700L)
                    }
                }

                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        captureToken()
                    }
                }
                loadUrl("https://account.formula1.com/")
                handler.postDelayed({ captureToken() }, 1000L)
            }
        })
    }, confirmButton = { TextButton(onClick = onClose) { Text("CLOSE") } })
}
private fun readSubscriptionToken(cookieHeader: String?): String? {
    if (cookieHeader.isNullOrBlank()) return null
    val cookie = cookieHeader.split(';').map { it.trim() }.firstOrNull { it.startsWith("login-session=") } ?: return null
    return runCatching {
        val json = JSONObject(URLDecoder.decode(cookie.substringAfter('='), "UTF-8"))
        val data = json.optJSONObject("data")
        data?.optString("subscriptionToken")?.takeIf { it.length >= 50 } ?: json.optString("subscriptionToken").takeIf { it.length >= 50 }
    }.getOrNull()
}
