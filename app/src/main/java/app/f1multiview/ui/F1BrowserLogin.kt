package app.f1multiview.ui

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import app.f1multiview.viewmodel.MultiViewViewModel
import java.net.URLDecoder
import org.json.JSONObject

private val Red = Color(0xFFE10600)
private val Bg = Color(0xFF0B0B10)
private val Surface1 = Color(0xFF14151B)
private val White = Color(0xFFF5F5F7)
private val Muted = Color(0xFF9698A2)

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun F1BrowserLogin(
    vm: MultiViewViewModel,
    errorMessage: String? = null
) {
    val handler = remember { Handler(Looper.getMainLooper()) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var loading by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var reloadKey by remember { mutableStateOf(0) }

    DisposableEffect(reloadKey) {
        onDispose {
            handler.removeCallbacksAndMessages(null)
            webViewRef?.apply {
                stopLoading()
                webViewClient = WebViewClient()
                destroy()
            }
            webViewRef = null
        }
    }

    Box(Modifier.fillMaxSize().background(Bg)) {
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxWidth().height(6.dp).background(Red))

            Surface(Modifier.fillMaxWidth(), color = Surface1) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("F1", color = Red, fontSize = 30.sp, fontWeight = FontWeight.Black)
                    Spacer(Modifier.width(7.dp))
                    Text("TV", color = White, fontSize = 25.sp, fontWeight = FontWeight.ExtraBold)
                    Spacer(Modifier.width(16.dp))
                    Text("SIGN IN", color = White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.weight(1f))
                    if (loading) {
                        CircularProgressIndicator(
                            Modifier.size(18.dp),
                            color = Red,
                            strokeWidth = 2.dp
                        )
                        Spacer(Modifier.width(10.dp))
                    }
                    TextButton(onClick = { reloadKey++ }) {
                        Text("RELOAD", color = White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }

            if (!errorMessage.isNullOrBlank()) {
                Surface(
                    Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
                    color = Color(0xFF2A1114)
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text("F1 TV sign-in failed", color = Color(0xFFFF9B9B), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        Text(errorMessage, color = Color(0xFFFFB8B8), fontSize = 10.sp, modifier = Modifier.padding(top = 4.dp))
                        TextButton(onClick = { reloadKey++ }) {
                            Text("TRY AGAIN", color = White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            loadError?.let { message ->
                Surface(
                    Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp),
                    color = Color(0xFF2A1114)
                ) {
                    Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(message, color = Color(0xFFFFB8B8), fontSize = 10.sp, modifier = Modifier.weight(1f))
                        TextButton(onClick = { reloadKey++ }) {
                            Text("RELOAD", color = White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            AndroidView(
                Modifier.fillMaxWidth().weight(1f),
                factory = { context ->
                    WebView(context).apply {
                        webViewRef = this
                        var captured = false

                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.useWideViewPort = true
                        settings.loadWithOverviewMode = true
                        settings.allowContentAccess = true
                        settings.allowFileAccess = false
                        settings.mediaPlaybackRequiresUserGesture = true

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
                                return
                            }
                            handler.postDelayed({ captureToken() }, 700L)
                        }

                        fun acceptCookieConsent() {
                            evaluateJavascript(
                                """
                                (function() {
                                  var labels = ['Accept all','Accept All','I agree','Allow all','Accept'];
                                  var els = Array.prototype.slice.call(document.querySelectorAll('button,a,[role="button"]'));
                                  for (var i=0;i<els.length;i++) {
                                    var t=(els[i].innerText||els[i].textContent||'').trim().toLowerCase();
                                    for (var j=0;j<labels.length;j++) {
                                      if (t === labels[j].toLowerCase()) {
                                        els[i].click();
                                        return 'clicked';
                                      }
                                    }
                                  }
                                  return 'none';
                                })();
                                """.trimIndent(),
                                null
                            )
                        }

                        webViewClient = object : WebViewClient() {
                            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                super.onPageStarted(view, url, favicon)
                                loading = true
                                loadError = null
                                acceptCookieConsent()
                                captureToken()
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                super.onPageFinished(view, url)
                                loading = false
                                acceptCookieConsent()
                                captureToken()
                            }

                            override fun onReceivedError(
                                view: WebView?,
                                request: android.webkit.WebResourceRequest?,
                                error: android.webkit.WebResourceError?
                            ) {
                                super.onReceivedError(view, request, error)
                                if (request?.isForMainFrame == true) {
                                    loadError = error?.description?.toString() ?: "Unable to load F1 TV sign-in."
                                    loading = false
                                }
                            }
                        }

                        loadUrl("https://account.formula1.com/#/en/login")
                        handler.postDelayed({ captureToken() }, 1000L)
                        handler.postDelayed({ acceptCookieConsent() }, 1800L)
                    }
                },
                update = { webViewRef = it }
            )
        }
    }
}

private fun readSubscriptionToken(cookieHeader: String?): String? {
    if (cookieHeader.isNullOrBlank()) return null

    val cookie = cookieHeader
        .split(';')
        .map { it.trim() }
        .firstOrNull { it.startsWith("login-session=", ignoreCase = true) }
        ?: return null

    return runCatching {
        val json = JSONObject(URLDecoder.decode(cookie.substringAfter('='), "UTF-8"))
        val data = json.optJSONObject("data")
        data?.optString("subscriptionToken")?.takeIf { it.length >= 50 }
            ?: json.optString("subscriptionToken").takeIf { it.length >= 50 }
    }.getOrNull()
}
