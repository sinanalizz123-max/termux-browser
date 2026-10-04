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
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.Switch
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.GravityCompat
import androidx.webkit.WebViewCompat
import com.termux.browser.ai.AdapterRegistry
import com.termux.browser.ai.AiApiRunner
import com.termux.browser.ai.ApiCredentialStore
import com.termux.browser.ai.ChatGPTAdapter
import com.termux.browser.ai.DeepSeekAdapter
import com.termux.browser.ai.GenericAdapter
import com.termux.browser.ai.OpenAiProvider
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
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
    internal lateinit var drawerLayout: androidx.drawerlayout.widget.DrawerLayout
    internal lateinit var menuButton: Button
    internal lateinit var contentView: LinearLayout
    private lateinit var urlInput: EditText
    private lateinit var logView: TextView
    private lateinit var progressBar: ProgressBar
    private val policy = ControlPolicy()
    private val log = ActivityLog()
    private val bus = EventBus()
    private val debugLog = ActivityLog(maxEvents = 200, maxBytes = 128 * 1024)
    private val startTime = System.currentTimeMillis()
    private val lastCrash = java.util.concurrent.atomic.AtomicReference<String?>(null)
    private val fgs = ForegroundController()
    private lateinit var notifier: SessionNotifier
    private var fgsRunning = false
    private var notificationShown = false

    /**
     * Test seam: onPause wiring reads this first so Robolectric can simulate
     * background automation without real queued work.
     */
    internal var automationActiveOverride: Boolean? = null

    private val learnedStore by lazy {
        com.termux.browser.ai.LearnedStore(java.io.File(filesDir, "learned"))
    }
    internal var learnMode = false
    private val activityScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val uiRunner = object : UiRunner {
        override suspend fun <T> run(block: suspend () -> T): T =
            withContext(Dispatchers.Main) { block() }
    }
    private val results = ResultStore()
    private lateinit var arbiter: CommandArbiter
    private var server: LocalApiServer? = null
    private var apiToken: ByteArray = ByteArray(0)
    private var pageLoading = false
    private var webViewVersion = "unknown"

    /**
     * Test-only hook: Robolectric has no Android Keystore provider, and this
     * static survives activity recreation inside tests. Production always
     * uses the Android Keystore.
     */
    internal var tokenCryptoOverride: TokenCrypto? = null
        get() = field ?: testCrypto

    @SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        fun dp(value: Int): Int =
            (value * resources.displayMetrics.density).toInt()

        // DuckDuckGo-style pill background for the address bar.
        fun pillBackground(): android.graphics.drawable.GradientDrawable {
            return android.graphics.drawable.GradientDrawable().apply {
                setColor(0xFFF1F3F4.toInt())
                cornerRadius = dp(24).toFloat()
            }
        }

        drawerLayout = androidx.drawerlayout.widget.DrawerLayout(this)

        // ---- Main content: top bar, nav row, status, progress, page ----
        // Padded for the system status/navigation bars so they never
        // overlap the browser (edge-to-edge is enforced on target 35+).
        // The drawer itself stays full-bleed, which is standard.
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        contentView = content
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(content) { view, insets ->
            val bars = insets.getInsets(
                androidx.core.view.WindowInsetsCompat.Type.systemBars()
            )
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(8), dp(8), dp(8), dp(4))
        }
        menuButton = Button(this).apply {
            text = "☰"
            contentDescription = "Menu"
            textSize = 20f
            setPadding(dp(4), dp(4), dp(4), dp(4))
            setOnClickListener { toggleMenu() }
        }
        urlInput = EditText(this).apply {
            hint = "Search or enter address"
            contentDescription = "Address bar"
            background = pillBackground()
            setPadding(dp(16), dp(10), dp(16), dp(10))
            textSize = 15f
            isSingleLine = true
        }
        val go = Button(this).apply { text = "Go" }
        topBar.addView(
            menuButton,
            LinearLayout.LayoutParams(dp(48), LinearLayout.LayoutParams.WRAP_CONTENT)
        )
        topBar.addView(
            urlInput,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(dp(8), 0, dp(8), 0)
            }
        )
        topBar.addView(go)
        content.addView(topBar)

        val nav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(8), 0, dp(8), dp(4))
        }
        val back = Button(this).apply { text = "‹" }
        val forward = Button(this).apply { text = "›" }
        val reload = Button(this).apply { text = "↻" }
        val stop = Button(this).apply { text = "✕" }
        nav.addView(back)
        nav.addView(forward)
        nav.addView(reload)
        nav.addView(stop)
        content.addView(nav)

        statusView = TextView(this).apply {
            contentDescription = "Browser status"
            setPadding(dp(12), dp(2), dp(12), dp(2))
            textSize = 12f
        }
        content.addView(statusView)

        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            visibility = android.view.View.GONE
        }
        content.addView(
            progressBar,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(4)
            )
        )

        webView = WebView(this).apply {
            contentDescription = "Browser page"
        }
        content.addView(
            webView,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )
        drawerLayout.addView(
            content,
            androidx.drawerlayout.widget.DrawerLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
        )

        // ---- Left drawer: Termux menu with the activity log tab ----
        val pane = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFFFFFFFF.toInt())
            setPadding(dp(16), dp(24), dp(16), dp(16))
        }
        val paneTitle = TextView(this).apply {
            text = "Termux"
            textSize = 20f
        }
        pane.addView(paneTitle)
        val logHeader = TextView(this).apply {
            text = "Activity"
            textSize = 14f
            setPadding(0, dp(12), 0, dp(4))
        }
        pane.addView(logHeader)
        logView = TextView(this).apply {
            contentDescription = "Activity log"
            textSize = 12f
        }
        val logScroll = ScrollView(this).apply { addView(logView) }
        pane.addView(
            logScroll,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )
        val pair = Button(this).apply {
            text = "Pair Termux"
            contentDescription = "Show Termux pairing details"
        }
        val apiKeys = Button(this).apply {
            text = "API keys"
            contentDescription = "Manage provider API keys"
        }
        val learnHeader = TextView(this).apply {
            text = "Learn mode: tap a send button to teach it"
            textSize = 14f
            setPadding(0, dp(12), 0, dp(4))
        }
        val learnSwitch = Switch(this).apply {
            text = "Learn mode"
            contentDescription = "Toggle learn mode"
            setOnCheckedChangeListener { _, checked ->
                learnMode = checked
                announce(if (checked) "LEARN MODE: tap the send button." else "Learn mode off.")
            }
        }
        pane.addView(pair)
        pane.addView(apiKeys)
        pane.addView(learnHeader)
        pane.addView(learnSwitch)
        drawerLayout.addView(
            pane,
            androidx.drawerlayout.widget.DrawerLayout.LayoutParams(
                dp(300),
                LinearLayout.LayoutParams.MATCH_PARENT,
                GravityCompat.START
            )
        )
        setContentView(drawerLayout)

        controller = BrowserController(
            this,
            policy,
            log,
            announce = { message ->
                runOnUiThread {
                    statusView.text = message
                    refreshLog()
                }
            },
            bus = bus
        )

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
        apiKeys.setOnClickListener { showApiKeys() }
        webView.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_DOWN) {
                policy.onUserInput()
                announce("User browsing.")
            }
            if (event.action == MotionEvent.ACTION_UP && learnMode) {
                recordLearnTap(event.x, event.y)
            }
            false
        }

        webViewVersion = runCatching {
            WebViewCompat.getCurrentWebViewPackage(this)?.versionName ?: "unknown"
        }.getOrDefault("unknown")
        notifier = SessionNotifier(this)
        val apiCredentials = ApiCredentialStore(filesDir) { providerId ->
            KeystoreTokenCrypto(KeystoreTokenCrypto.providerAlias(providerId))
        }
        val apiRunner = AiApiRunner(
            apiCredentials,
            mapOf("openai" to OpenAiProvider(productionApiClient()))
        )
        arbiter = CommandArbiter(
            activityScope, uiRunner, this, controller, policy, results, bus,
            registry = AdapterRegistry(
                listOf(ChatGPTAdapter(), DeepSeekAdapter(), GenericAdapter())
            ),
            learnedStore = learnedStore,
            onWorkChanged = { active -> onAutomationWorkChanged(active) }
        )
        apiToken = TokenStore(filesDir, tokenCryptoOverride ?: KeystoreTokenCrypto())
            .getOrCreate()
        val previousCrashHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            val summary = "${error.javaClass.name}: ${(error.message ?: "").take(200)}"
            lastCrash.set(summary)
            debugLog.add("crash", error.javaClass.name, "${thread.name}: ${summary.take(200)}")
            previousCrashHandler?.uncaughtException(thread, error)
        }
        val api = LocalApiServer(
            apiToken, uiRunner, arbiter, policy, log, results,
            statusProvider = { currentStatus() },
            bus = bus,
            debugLog = debugLog,
            reportProvider = { currentReport() }
        )
        server = api
        activityScope.launch {
            // Tests override to an ephemeral port so suites never fight
            // over the fixed port in one JVM.
            val started = runCatching { api.start(testPortOverride ?: LocalApiServer.FIXED_PORT) }
            if (started.isFailure) {
                // Non-destructive: token untouched, no ready advertisement.
                // Control returns on next activity creation once free.
                server = null
                announce("Termux control unavailable: localhost port busy.")
            }
        }
        wireControlPlane()
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

    override fun onStart() {
        super.onStart()
        applyFgs(fgs.onVisibilityChanged(true))
    }

    override fun onResume() {
        super.onResume()
        applyFgs(fgs.onVisibilityChanged(true))
    }

    override fun onPause() {
        // Foreground-to-background transition: the compliant moment to start
        // the FGS if automation is active. Never start from fully background.
        if (automationActiveOverride == true && !fgs.automationActive) {
            fgs.onAutomationChanged(true)
        }
        applyFgs(fgs.onVisibilityChanged(false))
        super.onPause()
    }

    override fun onStop() {
        applyFgs(fgs.onVisibilityChanged(false))
        super.onStop()
    }

    internal fun toggleMenu() {
        if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
            drawerLayout.closeDrawer(GravityCompat.START, false)
        } else {
            drawerLayout.openDrawer(GravityCompat.START, false)
        }
    }

    @Deprecated("Use back-press dispatch on newer platforms")
    override fun onBackPressed() {
        if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
            drawerLayout.closeDrawer(GravityCompat.START)
        } else if (webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        activityScope.cancel()
        runCatching { server?.stop() }
        server = null
        arbiter.close()
        stopFgsService()
        notifier.cancel()
        ControlPlane.onPauseRequested = null
        ControlPlane.onResumeRequested = null
        ControlPlane.onStopRequested = null
        (webView.parent as? android.view.ViewGroup)?.removeView(webView)
        webView.destroy()
        super.onDestroy()
    }

    private fun productionApiClient(): HttpClient {
        return HttpClient(CIO) {
            install(ContentNegotiation) {
                json()
            }
        }
    }

    /**
     * M10 manual key management. Keys are typed in-app only, stored encrypted
     * per provider, and never cross the Termux API. The input field uses a
     * password transformation and is cleared on dismiss.
     */
    private fun showApiKeys() {
        val store = ApiCredentialStore(filesDir) { providerId ->
            KeystoreTokenCrypto(KeystoreTokenCrypto.providerAlias(providerId))
        }
        val providerId = "openai"
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 32, 48, 32)
        }
        val status = TextView(this)
        val input = EditText(this).apply {
            hint = "Paste API key"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            contentDescription = "API key input"
        }
        val enabled = CheckBox(this).apply { text = "Enabled" }
        fun refresh() {
            val has = store.hasKey(providerId)
            status.text = "openai: key " + (if (has) "set" else "missing") +
                ", " + (if (store.isEnabled(providerId)) "enabled" else "disabled")
            enabled.isChecked = store.isEnabled(providerId)
        }
        layout.addView(status)
        layout.addView(input)
        layout.addView(enabled)
        refresh()
        enabled.setOnCheckedChangeListener { _, checked ->
            store.setEnabled(providerId, checked)
            refresh()
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("Provider API keys")
            .setView(layout)
            .setPositiveButton("Save key") { _, _ ->
                val key = input.text.toString()
                if (key.isNotBlank()) {
                    store.setKey(providerId, key)
                    input.text.clear()
                }
                refresh()
            }
            .setNeutralButton("Delete key") { _, _ ->
                store.deleteKey(providerId)
                input.text.clear()
                refresh()
            }
            .setNegativeButton("Close", null)
            .create()
        dialog.setOnDismissListener { input.text.clear() }
        dialog.show()
    }

    /**
     * Learn mode recorder: maps the user's tap to the element beneath it
     * and stores the derived send selector for the current host. Position
     * and structure only — no page content is ever read or stored.
     */
    private fun recordLearnTap(xPx: Float, yPx: Float) {
        val host = currentHost() ?: return
        activityScope.launch(Dispatchers.IO) {
            val scale = webView.scale
            if (!scale.isFinite() || scale <= 0f) return@launch
            val raw = runCatching {
                evalJs(
                    com.termux.browser.ai.elementProbeScript(
                        (xPx / scale).toDouble(),
                        (yPx / scale).toDouble()
                    )
                )
            }.getOrNull()
            val info = com.termux.browser.ai.parseElementInfo(raw) ?: return@launch
            val selector = com.termux.browser.ai.LearnedSelectors.derive(info) ?: return@launch
            learnedStore.put(host, selector)
            withContext(Dispatchers.Main) {
                announce("Learned send for $host: $selector")
            }
        }
    }

    private fun currentHost(): String? {
        val url = webView.url ?: return null
        return url.substringAfter("://", url).substringBefore('/').lowercase()
            .takeIf { it.isNotBlank() }
    }

    private fun currentReport(): DebugReport {
        val runtime = Runtime.getRuntime()
        val usedMb = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)
        val maxMb = runtime.maxMemory() / (1024 * 1024)
        return DebugReport(
            uptimeMs = System.currentTimeMillis() - startTime,
            pendingCommands = arbiter.pendingCount(),
            controlState = policy.state.name,
            generationId = policy.generation,
            webViewVersion = webViewVersion,
            heapUsedMb = usedMb,
            heapMaxMb = maxMb,
            lastCrash = lastCrash.get()
        )
    }

    private fun currentStatus(): StatusBody = StatusBody(
        state = policy.state.name,
        // Display form only: OAuth/session tokens in query strings must
        // never cross the API. Internal logic keeps using the full URL.
        url = UrlPolicy.forDisplay(webView.url).ifEmpty { null },
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
        if ((server?.port ?: -1) < 0) {
            AlertDialog.Builder(this)
                .setTitle("Pair Termux")
                .setMessage("Control server is not running (localhost port busy). Close the conflicting app and reopen this one.")
                .setPositiveButton("Close", null)
                .show()
            return
        }
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(
                arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 41
            )
        }
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
        if (notificationShown) {
            notifier.show(message)
        }
    }

    private fun onAutomationWorkChanged(active: Boolean) {
        if (active) {
            notifier.show("Termux automation active")
            notificationShown = true
        } else {
            notifier.cancel()
            notificationShown = false
        }
        applyFgs(fgs.onAutomationChanged(active))
    }

    private fun applyFgs(action: ForegroundController.Action) {
        when (action) {
            is ForegroundController.Action.StartFgs -> startFgsService()
            is ForegroundController.Action.StopFgs -> stopFgsService()
            is ForegroundController.Action.None -> Unit
        }
    }

    private fun startFgsService() {
        if (fgsRunning) return
        val intent = android.content.Intent(this, AutomationService::class.java).apply {
            putExtra(AutomationService.EXTRA_STATE, statusView.text.toString())
        }
        runCatching { startForegroundService(intent) }
        fgsRunning = true
    }

    private fun stopFgsService() {
        if (!fgsRunning) return
        runCatching {
            stopService(android.content.Intent(this, AutomationService::class.java))
        }
        fgsRunning = false
    }

    private fun wireControlPlane() {
        ControlPlane.onPauseRequested = {
            policy.onPause()
            announce("Termux control paused.")
        }
        ControlPlane.onResumeRequested = {
            policy.onResume()
            announce("Termux control resumed.")
        }
        ControlPlane.onStopRequested = {
            activityScope.launch { arbiter.stopNow("notification") }
        }
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
                controller.onPageStarted(url)
            }

            override fun shouldOverrideUrlLoading(
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
                if (!urlInput.hasFocus()) {
                    urlInput.setText(url)
                }
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
        webView.webChromeClient = object : android.webkit.WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                progressBar.progress = newProgress
                progressBar.visibility =
                    if (newProgress >= 100) android.view.View.GONE
                    else android.view.View.VISIBLE
            }

            override fun onConsoleMessage(message: android.webkit.ConsoleMessage): Boolean {
                debugLog.add(
                    "console",
                    message.messageLevel().name,
                    "${message.sourceId()}:${message.lineNumber()}: ${message.message().take(500)}"
                )
                return super.onConsoleMessage(message)
            }
        }
    }

    companion object {
        private const val KEY_URL = "browser_url"
        private const val KEY_GENERATION = "browser_generation"
        private const val KEY_STATE = "browser_state"

        /**
         * Test-only hook: Robolectric has no Android Keystore provider, and
         * this static survives activity recreation inside tests. Production
         * always uses the Android Keystore.
         */
        internal var testCrypto: TokenCrypto? = null

        /** Test-only hook: bind an ephemeral port instead of the fixed one. */
        internal var testPortOverride: Int? = null
    }
}
