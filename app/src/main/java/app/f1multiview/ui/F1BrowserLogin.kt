package app.f1multiview.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.widget.FrameLayout
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
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

private const val F1_LOGIN_URL = "https://account.formula1.com/#/en/login"
private const val PREFS = "f1_browser_login"
private const val KEY_LOGIN = "login"
private const val KEY_PASSWORD = "password"
private const val AUTO_FILL_RETRY_DELAY_MS = 2_000L
private const val AUTO_FILL_RETRY_ATTEMPTS = 8

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

            key(reloadKey) {
                AndroidView(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    factory = { context: Context ->
                        FrameLayout(context).apply {
                            val webView = WebView(context).apply {
                        webViewRef = this
                        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                        var capturedToken = false

                        settings.domStorageEnabled = true
                        settings.useWideViewPort = true
                        settings.loadWithOverviewMode = true
                        settings.javaScriptEnabled = true
                        settings.allowContentAccess = true
                        settings.allowFileAccess = false
                        settings.mediaPlaybackRequiresUserGesture = true

                        CookieManager.getInstance().setAcceptCookie(true)
                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

                        addJavascriptInterface(object {
                            @JavascriptInterface
                            fun saveCredentials(username: String, password: String) {
                                if (username.isBlank() || password.isBlank()) return
                                prefs.edit()
                                    .putString(KEY_LOGIN, username)
                                    .putString(KEY_PASSWORD, password)
                                    .apply()
                            }
                        }, "Android")

                        fun captureToken() {
                            if (capturedToken) return
                            val cm = CookieManager.getInstance()
                            val token = listOf(
                                "https://account.formula1.com/",
                                "https://formula1.com/",
                                "https://f1tv.formula1.com/"
                            ).asSequence()
                                .mapNotNull { readSubscriptionToken(cm.getCookie(it)) }
                                .firstOrNull()

                            if (token != null) {
                                capturedToken = true
                                cm.flush()
                                vm.signInWithSessionToken(token)
                                return
                            }
                            handler.postDelayed({ captureToken() }, 700L)
                        }

                        fun installCookieConsentAutomation() {
                            evaluateJavascript(
                                """
                                (function() {
                                    function collectInteractiveElements(root, found) {
                                        if (!root) return;
                                        var selectors = ['button','[role="button"]','input[type="button"]','input[type="submit"]','a[role="button"]'];
                                        selectors.forEach(function(selector) {
                                            try {
                                                root.querySelectorAll(selector).forEach(function(element) {
                                                    if (found.indexOf(element) === -1) found.push(element);
                                                });
                                            } catch (e) {}
                                        });
                                        try {
                                            root.querySelectorAll('*').forEach(function(element) {
                                                if (element.shadowRoot) collectInteractiveElements(element.shadowRoot, found);
                                            });
                                        } catch (e) {}
                                        try {
                                            root.querySelectorAll('iframe').forEach(function(frame) {
                                                try {
                                                    if (frame.contentDocument) collectInteractiveElements(frame.contentDocument, found);
                                                } catch (e) {}
                                            });
                                        } catch (e) {}
                                    }

                                    function looksLikeAcceptAction(element) {
                                        if (!element) return false;
                                        var text = [
                                            element.innerText, element.textContent, element.value,
                                            element.getAttribute('aria-label'), element.getAttribute('data-testid'),
                                            element.getAttribute('title'), element.id, element.className
                                        ].filter(Boolean).join(' ').toLowerCase();

                                        var includeTerms = ['accept','agree','allow all','accept all','accept cookies','allow cookies','consent','got it','ok'];
                                        var excludeTerms = ['reject','decline','deny','manage','settings','preferences','learn more'];

                                        return includeTerms.some(function(term) { return text.indexOf(term) !== -1; }) &&
                                            !excludeTerms.some(function(term) { return text.indexOf(term) !== -1; });
                                    }

                                    function clickConsentButton() {
                                        var elements = [];
                                        collectInteractiveElements(document, elements);
                                        var candidate = elements.find(looksLikeAcceptAction);
                                        if (!candidate) return false;

                                        ['pointerdown','mousedown','mouseup','click'].forEach(function(name) {
                                            try {
                                                candidate.dispatchEvent(new MouseEvent(name, {
                                                    bubbles: true, cancelable: true, view: window
                                                }));
                                            } catch (e) {}
                                        });
                                        try { candidate.click(); } catch (e) {}
                                        return true;
                                    }

                                    if (!window.__f1CookieConsentAutomationInstalled) {
                                        window.__f1CookieConsentAutomationInstalled = true;
                                        try {
                                            var observer = new MutationObserver(function() { clickConsentButton(); });
                                            observer.observe(document.documentElement || document.body, {
                                                childList: true, subtree: true, attributes: true
                                            });
                                        } catch (e) {}

                                        var attempts = 0;
                                        var intervalId = setInterval(function() {
                                            attempts++;
                                            if (clickConsentButton() || attempts >= 20) clearInterval(intervalId);
                                        }, 1000);
                                    }

                                    return clickConsentButton() ? 'clicked' : 'installed';
                                })();
                                """.trimIndent(),
                                null
                            )
                        }

                        fun captureCredentials() {
                            evaluateJavascript(
                                """
                                (function() {
                                    var loginButton =
                                        document.querySelector('button.btn.btn-primary[type="submit"]') ||
                                        document.querySelector('button[type="submit"]');

                                    if (loginButton && !loginButton.hasAttribute('data-f1-capture-installed')) {
                                        loginButton.setAttribute('data-f1-capture-installed', 'true');
                                        loginButton.addEventListener('click', function() {
                                            var loginInput =
                                                document.querySelector('.txtLogin') ||
                                                document.querySelector('input[type="email"]') ||
                                                document.querySelector('input[name="email"]');
                                            var passwordInput =
                                                document.querySelector('.txtPassword') ||
                                                document.querySelector('input[type="password"]');

                                            var login = loginInput ? loginInput.value : '';
                                            var password = passwordInput ? passwordInput.value : '';
                                            if (login && password && window.Android) {
                                                window.Android.saveCredentials(login, password);
                                            }
                                        });
                                    }
                                    return loginButton ? 'capture-installed' : 'login-button-not-found';
                                })();
                                """.trimIndent(),
                                null
                            )
                        }

                        fun fillLoginForm(login: String, password: String, autoSubmit: Boolean) {
                            val loginJs = JSONObject.quote(login)
                            val passwordJs = JSONObject.quote(password)
                            val submitJs = autoSubmit.toString()

                            evaluateJavascript(
                                """
                                (function() {
                                    function setNativeValue(element, value) {
                                        if (!element) return false;
                                        var prototype = element.tagName === 'TEXTAREA'
                                            ? window.HTMLTextAreaElement.prototype
                                            : window.HTMLInputElement.prototype;
                                        var descriptor = Object.getOwnPropertyDescriptor(prototype, 'value');
                                        if (descriptor && descriptor.set) descriptor.set.call(element, value);
                                        else element.value = value;

                                        ['input','change','blur'].forEach(function(eventName) {
                                            element.dispatchEvent(new Event(eventName, { bubbles: true }));
                                        });
                                        return true;
                                    }

                                    function findLoginField() {
                                        return document.querySelector('.txtLogin') ||
                                            document.querySelector('input[type="email"]') ||
                                            document.querySelector('input[name="email"]');
                                    }

                                    function findPasswordField() {
                                        return document.querySelector('.txtPassword') ||
                                            document.querySelector('input[type="password"]');
                                    }

                                    function findSubmitButton() {
                                        return document.querySelector('button.btn.btn-primary[type="submit"]') ||
                                            document.querySelector('button[type="submit"]');
                                    }

                                    function tryFill() {
                                        var loginField = findLoginField();
                                        var passwordField = findPasswordField();
                                        var loginButton = findSubmitButton();

                                        var loginFilled = setNativeValue(loginField, ${loginJs});
                                        var passwordFilled = setNativeValue(passwordField, ${passwordJs});

                                        if (!loginFilled || !passwordFilled) return false;

                                        if (${submitJs} && loginButton) {
                                            setTimeout(function() { loginButton.click(); }, 500);
                                        }
                                        return true;
                                    }

                                    if (tryFill()) return 'filled-now';

                                    var attempts = 0;
                                    var intervalId = setInterval(function() {
                                        attempts++;
                                        if (tryFill() || attempts >= 15) clearInterval(intervalId);
                                    }, 1000);
                                    return 'scheduled-retries';
                                })();
                                """.trimIndent(),
                                null
                            )
                        }

                        fun autoFillCredentials() {
                            val login = prefs.getString(KEY_LOGIN, null)
                            val password = prefs.getString(KEY_PASSWORD, null)

                            if (login.isNullOrBlank() || password.isNullOrBlank()) {
                                captureCredentials()
                                return
                            }

                            captureCredentials()
                            fillLoginForm(login, password, autoSubmit = true)

                            handler.postDelayed(object : Runnable {
                                var attempt = 0
                                override fun run() {
                                    if (capturedToken || !url.orEmpty().startsWith(F1_LOGIN_URL)) return
                                    attempt++
                                    if (attempt > AUTO_FILL_RETRY_ATTEMPTS) return
                                    fillLoginForm(login, password, autoSubmit = true)
                                    handler.postDelayed(this, AUTO_FILL_RETRY_DELAY_MS)
                                }
                            }, AUTO_FILL_RETRY_DELAY_MS)
                        }

                        webViewClient = object : WebViewClient() {
                            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                super.onPageStarted(view, url, favicon)
                                loading = true
                                loadError = null
                                installCookieConsentAutomation()
                                captureToken()
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                super.onPageFinished(view, url)
                                loading = false
                                installCookieConsentAutomation()
                                captureToken()

                                if (url.orEmpty().startsWith(F1_LOGIN_URL)) {
                                    captureCredentials()
                                    autoFillCredentials()
                                }
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

                        loadUrl(F1_LOGIN_URL)
                        handler.postDelayed({ captureToken() }, 1000L)
                        handler.postDelayed({ installCookieConsentAutomation() }, 1800L)
                            }

                            addView(
                                webView,
                                FrameLayout.LayoutParams(
                                    FrameLayout.LayoutParams.MATCH_PARENT,
                                    FrameLayout.LayoutParams.MATCH_PARENT
                                )
                            )
                        }
                    },
                    update = { container ->
                        val webView = container.getChildAt(0) as? WebView ?: return@AndroidView
                        webViewRef = webView
                    },
                    onRelease = { container ->
                        handler.removeCallbacksAndMessages(null)
                        val webView = container.getChildAt(0) as? WebView
                        if (webViewRef === webView) webViewRef = null
                        webView?.let {
                            it.stopLoading()
                            it.webViewClient = WebViewClient()
                            it.destroy()
                        }
                        container.removeAllViews()
                    }
                )
            }
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
