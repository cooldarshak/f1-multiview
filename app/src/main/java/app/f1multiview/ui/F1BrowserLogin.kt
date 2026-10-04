package app.f1multiview.ui

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.ui.unit.dp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import app.f1multiview.viewmodel.MultiViewViewModel
import java.net.URLDecoder
import org.json.JSONObject

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun F1BrowserLogin(onClose: () -> Unit, vm: MultiViewViewModel) {
    AlertDialog(onDismissRequest = onClose, title = { Text("Sign in to F1 TV") }, text = {
        AndroidView(modifier = Modifier.fillMaxWidth().height(520.dp), factory = { context ->
            WebView(context).apply {
                var captured = false
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                CookieManager.getInstance().setAcceptCookie(true)
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        if (captured) return
                        val token = readSubscriptionToken(CookieManager.getInstance().getCookie("https://account.formula1.com"))
                        if (token != null) { captured = true; vm.signInWithSessionToken(token) }
                    }
                }
                loadUrl("https://account.formula1.com/")
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
