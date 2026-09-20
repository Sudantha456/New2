package com.app.youtube.lite.auth

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.app.youtube.lite.core.net.UserAgents
import com.app.youtube.lite.core.util.L

/** Result of an in-app login attempt. */
sealed interface LoginOutcome {
    data class Success(val cookies: SessionCookies) : LoginOutcome
    data class Failure(val message: String) : LoginOutcome
}

/**
 * Minimal, memory-honest Google/YouTube login.
 *
 * Design notes:
 *  • **Desktop UA.** `accounts.google.com` refuses the default WebView UA with "this browser
 *    or app may not be secure". Presenting a desktop Chrome UA keeps the standard web login
 *    usable inside the app.
 *  • **Third-party cookies on.** The Google session is written to `.google.com` while the
 *    YouTube session is written to `.youtube.com`; without third-party cookies the redirect
 *    back from accounts.google.com never lands on the YouTube side.
 *  • **Cookie harvesting, not scraping.** As soon as any `*.youtube.com` page finishes
 *    loading we read [CookieManager] directly — no JavaScript injection, no DOM parsing,
 *    nothing that breaks when Google ships a new sign-in UI.
 *  • **Immediate teardown.** On success the WebView is stopped, navigated to `about:blank`,
 *    detached and destroyed. A live WebView owns a renderer process plus several MB of GPU
 *    textures; that memory is reclaimed before playback ever starts.
 *
 * @param finishSignal increment from the host screen to force a manual harvest (the escape
 *        hatch for accounts that land on a page we do not auto-detect as "done").
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun LoginWebView(
    onOutcome: (LoginOutcome) -> Unit,
    modifier: Modifier = Modifier,
    finishSignal: Int = 0,
) {
    val context = LocalContext.current
    val currentOutcome by rememberUpdatedState(onOutcome)

    var isLoading by remember { mutableStateOf(true) }
    var hint by remember { mutableStateOf<String?>(null) }

    val webView = remember {
        WebView(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            configureForLogin()
        }
    }

    val harvester = remember(webView) {
        CookieHarvester { cookies ->
            // null means the manual harvest ran and at least one of the six cookies was missing.
            // A partial session is worse than none — YouTube would answer as a signed-out client
            // and the failure would look like a parser bug — so it is reported as a failure.
            currentOutcome(
                if (cookies == null) {
                    LoginOutcome.Failure("Sign-in did not complete")
                } else {
                    LoginOutcome.Success(cookies)
                },
            )
            // Reclaim the renderer now; the host unmounts this screen a frame later and the
            // DisposableEffect below is then a no-op.
            destroyWebView(webView)
        }
    }

    DisposableEffect(webView) {
        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                isLoading = true
                hint = url?.let { hintFor(it) }
            }

            override fun onPageFinished(view: WebView, url: String?) {
                isLoading = false
                url?.let { harvester.harvest(it) }
            }

            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest,
            ): Boolean {
                val url = request.url.toString()
                hint = hintFor(url)
                // Never hand off to an external browser: the cookies we need are written into
                // this WebView's own jar, and the external browser's cookies are not readable.
                return false
            }
        }
        onDispose {
            harvester.cancel()
            destroyWebView(webView)
        }
    }

    LaunchedEffect(finishSignal) {
        if (finishSignal > 0) harvester.harvestNow(manual = true)
    }

    Box(modifier = modifier) {
        AndroidView(factory = { webView }, modifier = Modifier.fillMaxSize())

        AnimatedVisibility(
            visible = isLoading,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }

        val currentHint = hint
        AnimatedVisibility(
            visible = currentHint != null,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            if (currentHint != null) {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text(
                            text = currentHint,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                    }
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  Implementation details
// ─────────────────────────────────────────────────────────────────────────────

private fun WebView.configureForLogin() {
    settings.apply {
        javaScriptEnabled = true       // Google's sign-in UI requires JS
        domStorageEnabled = true       // …and DOM storage
        databaseEnabled = true
        setSupportZoom(false)
        builtInZoomControls = false
        displayZoomControls = false
        useWideViewPort = true
        loadWithOverviewMode = false
        mediaPlaybackRequiresUserGesture = false
        cacheMode = WebSettings.LOAD_DEFAULT
        userAgentString = UserAgents.DESKTOP
    }
    runCatching { settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW) }
    runCatching { CookieManager.getInstance().setAcceptThirdPartyCookies(this, true) }
    isVerticalScrollBarEnabled = true
    isHorizontalScrollBarEnabled = false
    overScrollMode = View.OVER_SCROLL_NEVER
    setBackgroundColor(Color.BLACK)
    loadUrl(LOGIN_URL)
    L.i("Login") { "WebView created (desktop UA, third-party cookies enabled)" }
}

/**
 * Reads the WebView cookie store and reports a complete session exactly once.
 *
 * Cookies are gathered from several hosts and merged, because Google and YouTube split the
 * session across `.google.com` and `.youtube.com`; the YouTube-side copy is the one that also
 * satisfies the SAPISIDHASH scheme.
 */
private class CookieHarvester(private val onHarvested: (SessionCookies?) -> Unit) {

    private var reported = false

    /** Called on every finished page load; harvests as soon as the user is on YouTube. */
    fun harvest(url: String) {
        if (reported) return
        val host = runCatching { Uri.parse(url).host }.getOrNull() ?: return
        if (!host.endsWith("youtube.com")) return
        harvestNow(manual = false)
    }

    /**
     * @param manual when true, an incomplete cookie set is reported as a failure instead of
     *        being ignored — that is the "I'm signed in — continue" button's contract.
     */
    fun harvestNow(manual: Boolean) {
        if (reported) return
        val manager = CookieManager.getInstance()
        val merged = StringBuilder(512)
        for (host in COOKIE_HOSTS) {
            val value = runCatching { manager.getCookie("https://$host") }.getOrNull() ?: continue
            if (value.isNotEmpty()) {
                if (merged.isNotEmpty()) merged.append("; ")
                merged.append(value)
            }
        }
        val cookies = SessionCookies.fromHeader(merged.toString())
        if (cookies != null && cookies.isComplete) {
            reported = true
            runCatching { manager.flush() }  // persist to disk before the WebView dies
            L.i("Login") { "harvested session (all six cookies present)" }
            onHarvested(cookies)
        } else if (manual) {
            reported = true
            L.w("Login") { "manual harvest found an incomplete session" }
            onHarvested(null)
        } else {
            L.d("Login") { "not signed in yet (${merged.length} cookie bytes seen)" }
        }
    }

    fun cancel() {
        reported = true
    }

    private companion object {
        val COOKIE_HOSTS = listOf(
            "www.youtube.com",
            "youtube.com",
            "m.youtube.com",
            "accounts.google.com",
        )
    }
}

/**
 * Destroys a WebView and everything it holds.
 *
 * `loadUrl("about:blank")` comes first on purpose: it tears down the renderer's retained page
 * state *before* `destroy()`, which measurably lowers the RSS that surviving view references
 * keep pinned until the next GC.
 */
internal fun destroyWebView(webView: WebView?) {
    if (webView == null) return
    runCatching {
        webView.stopLoading()
        webView.loadUrl("about:blank")
        webView.clearHistory()
        webView.clearFormData()
        webView.webViewClient = WebViewClient()
        webView.removeAllViews()
        (webView.parent as? ViewGroup)?.removeView(webView)
        webView.destroy()
    }.onFailure { L.w("Login") { "WebView teardown threw: $it" } }
    L.i("Login") { "WebView destroyed" }
}

/** Human-readable hints for the failure modes Google actually produces in embedded browsers. */
private fun hintFor(url: String): String? = when {
    url.contains("/sorry/index") || url.contains("deniedsigninrejected") ->
        "Google flagged this sign-in attempt. Wait a few minutes, or sign in from a browser " +
            "first — the session is reusable here afterwards."
    url.contains("signin/rejected") ->
        "Google rejected the embedded browser. Close and reopen the login screen, or retry later."
    else -> null
}

private const val LOGIN_URL = "https://accounts.google.com/ServiceLogin?service=youtube"
