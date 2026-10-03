package com.termux.browser

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.view.MotionEvent
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.webkit.WebViewCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * M1 visible browser shell: manual browsing only. No Termux server, no AI
 * automation, no JavaScript bridge. Every navigation is user-visible.
 */
class BrowserActivity : Activity(), PageHost {

    internal lateinit var controller: BrowserController
    internal lateinit var statusView: TextView
    internal lateinit var webView: WebView
    private lateinit var urlInput: EditText
    private lateinit var logView: TextView
    private val policy = ControlPolicy()
    private val log = ActivityLog()
    private val activityScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val uiRunner = object : UiRunner {
        override suspend fun <T> run(block: () -> T): T =
            withContext(Dispatchers.Main) { block() }
    }
    private val results = ResultStore()
    private lateinit var arbiter: CommandArbiter
    private var server: LocalApiServer? = null
    private var apiToken: ByteArray = ByteArray(0)
    private var pageLoading = false
    private var webViewVersion = "unknown"

    @SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        statusView = TextView(this).apply {
            contentDescription = "Browser status"
        }
        urlInput = EditText(this).apply {
            hint = "URL or search"
            contentDescription = "Address bar"
        }
        val go = Button(this).apply { text = "Go" }
        val nav = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val back = Button(this).apply { text = "Back" }
        val forward = Button(this).apply { text = "Fwd" }
        val reload = Button(this).apply { text = "Reload" }
        val stop = Button(this).apply { text = "Stop" }
        val pair = Button(this).apply {
            text = "Pair Termux"
            contentDescription = "Show Termux pairing details"
        }
        webView = WebView(this).apply {
            contentDescription = "Browser page"
        }
        logView = TextView(this).apply {
            contentDescription = "Activity log"
        }
        val logScroll = ScrollView(this).apply { addView(logView) }

        root.addView(statusView)
        root.addView(urlInput)
        root.addView(go)
        nav.addView(back)
        nav.addView(forward)
        nav.addView(reload)
        nav.addView(stop)
        nav.addView(pair)
        root.addView(nav)
        root.addView(
            webView,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )
        root.addView(
            logScroll,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )
        setContentView(root)

        controller = BrowserController(this, policy, log) { message ->
            runOnUiThread {
                statusView.text = message
                refreshLog()
            }
        }

        configureWebView()
        // Debug builds only: ADB WebView inspection for development.
        // Release builds must never enable this (credential vault).
        if (BuildConfig.DEBUG) {
            WebView.setWebContentsDebuggingEnabled(true)
        }
        go.setOnClickListener { controller.open(urlInput.text.toString(), "user") }
        back.setOnClickListener { controller.back("user") }
        forward.setOnClickListener { controller.forward("user") }
        reload.setOnClickListener { controller.reload("user") }
        stop.setOnClickListener { controller.stop("user") }
        pair.setOnClickListener { showPairing() }
        webView.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_DOWN) {
                policy.onUserInput()
                announce("User browsing.")
            }
            false
        }

        webViewVersion = runCatching {
            WebViewCompat.getCurrentWebViewPackage(this)?.versionName ?: "unknown"
        }.getOrDefault("unknown")
        arbiter = CommandArbiter(activityScope, uiRunner, this, controller, policy, results)
        apiToken = TokenStore(filesDir).getOrCreate()
        val api = LocalApiServer(apiToken, uiRunner, arbiter, policy, log, results) {
            currentStatus()
        }
        server = api
        activityScope.launch { runCatching { api.start() } }
        if (savedInstanceState != null) {
            policy.restore(
                savedInstanceState.getInt(KEY_GENERATION),
                ControlState.valueOf(
                    savedInstanceState.getString(KEY_STATE) ?: ControlState.IDLE.name
                )
            )
            savedInstanceState.getString(KEY_URL)?.let {
                controller.open(it, "system-restore")
            }
        } else {
            announce("Termux Browser ready. WebView $webViewVersion. Type a URL or search.")
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_URL, webView.url)
        outState.putInt(KEY_GENERATION, policy.generation)
        outState.putString(KEY_STATE, policy.state.name)
    }

    // PageHost: the ONLY place that touches the WebView.

    override fun loadUrl(url: String) {
        webView.loadUrl(url)
    }

    override fun stopLoading() {
        webView.stopLoading()
    }

    override fun goBack() {
        if (webView.canGoBack()) webView.goBack()
    }

    override fun goForward() {
        if (webView.canGoForward()) webView.goForward()
    }

    override fun reload() {
        webView.reload()
    }

    override fun onDestroy() {
        activityScope.cancel()
        runCatching { server?.stop() }
        server = null
        arbiter.close()
        (webView.parent as? android.view.ViewGroup)?.removeView(webView)
        webView.destroy()
        super.onDestroy()
    }

    private fun currentStatus(): StatusBody = StatusBody(
        state = policy.state.name,
        url = webView.url,
        title = webView.title,
        loading = pageLoading,
        generationId = policy.generation,
        webViewVersion = webViewVersion
    )

    /**
     * One-time local bootstrap: shows the endpoint and token on screen for
     * manual copy into Termux. The CLI must store it privately and never
     * print it to stdout.
     */
    private fun showPairing() {
        val port = server?.port ?: -1
        val details = TextView(this).apply {
            text = "Host: 127.0.0.1:$port\n\nToken (copy once into Termux):\n" +
                TokenStore(filesDir).hex(apiToken) +
                "\n\nIn Termux: browserctl connect"
            setTextIsSelectable(true)
            setPadding(32, 24, 32, 24)
        }
        AlertDialog.Builder(this)
            .setTitle("Pair Termux")
            .setView(details)
            .setPositiveButton("Close", null)
            .show()
    }

    override fun currentUrl(): String? = webView.url

    override suspend fun evalJs(script: String): String? =
        suspendCancellableCoroutine { cont ->
            runOnUiThread {
                try {
                    webView.evaluateJavascript(script) { value ->
                        if (cont.isActive) cont.resume(value)
                    }
                } catch (e: Exception) {
                    if (cont.isActive) cont.resume(null)
                }
            }
        }

    private fun announce(message: String) {
        statusView.text = message
        refreshLog()
    }

    private fun refreshLog() {
        val lines = log.since(0).takeLast(8).joinToString("\n") {
            "[${it.source}] ${it.action}: ${it.detail}"
        }
        logView.text = lines
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView() {
        val settings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.allowFileAccess = false
        settings.allowFileAccessFromFileURLs = false
        settings.allowUniversalAccessFromFileURLs = false
        settings.allowContentAccess = false
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        settings.safeBrowsingEnabled = true
        settings.setGeolocationEnabled(false)
        settings.mediaPlaybackRequiresUserGesture = true
        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                pageLoading = true
            }            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest
            ): Boolean {
                val url = request.url.toString()
                if (!UrlPolicy.isAllowed(url)) {
                    Toast.makeText(
                        this@BrowserActivity,
                        "Blocked navigation: unsupported address.",
                        Toast.LENGTH_SHORT
                    ).show()
                    log.add("user", "navigation_rejected", url.take(200))
                    return true
                }
                if (controller.claimPending(url)) return false
                controller.userNavigated(url)
                return false
            }

            override fun onPageFinished(view: WebView, url: String) {
                pageLoading = false
                controller.onPageFinished(url)
            }

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: WebResourceError
            ) {
                if (request.isForMainFrame) {
                    controller.onPageError(request.url.toString(), error.description.toString())
                }
            }
        }
    }

    companion object {
        private const val KEY_URL = "browser_url"
        private const val KEY_GENERATION = "browser_generation"
        private const val KEY_STATE = "browser_state"
    }
}
