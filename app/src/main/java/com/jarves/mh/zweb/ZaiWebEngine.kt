package com.jarves.mh.zweb

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.resume

/**
 * Drives https://chat.z.ai through a hidden (never attached) WebView.
 *
 * Fully isolated from the CLI agent stack: no AgentRegistry / AgentDriver / runtime
 * involvement. All DOM access is selector-config driven (see [ZaiWebConfig]); nothing
 * about the chat.z.ai markup is hardcoded here.
 *
 * Completion detection follows the multi-signal rule: stop-indicator absence + a new
 * assistant message + text stable for N seconds, with an overall timeout. Login pages
 * and captchas stop the run cleanly instead of hanging.
 *
 * Cookies live in the default CookieManager (app-persistent), so a sign-in performed
 * in the visible sheet is shared with this hidden WebView and vice versa. Cookies and
 * tokens are NEVER logged or persisted outside the system cookie store.
 */
class ZaiWebEngine(
    private val context: Context,
    private val scope: CoroutineScope,
    private val configLoader: ZaiConfigLoader,
    private val onState: (ZaiWebUiState) -> Unit,
) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val ledger = ZaiSessionLedger(context)

    private var webView: WebView? = null
    private var runJob: Job? = null
    private var runNumber = 0

    private var previousRunLoaded = false

    @Volatile
    private var canceled = false

    @Volatile
    private var state = ZaiWebUiState()

    // ── State emission (always funneled to the main thread) ──

    private fun update(transform: (ZaiWebUiState) -> ZaiWebUiState) {
        mainHandler.post {
            state = transform(state)
            onState(state)
        }
    }

    fun currentState(): ZaiWebUiState = state

    // ── WebView lifecycle ──

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun ensureWebView(): WebView? = withContext(Dispatchers.Main) {
        webView?.let { return@withContext it }
        val config = configLoader.load()
        if (config == null) {
            update { it.copy(runPhase = ZaiRunPhase.ERROR, runMessage = "Selector config unavailable — Z.ai Web disabled.") }
            return@withContext null
        }
        update { it.copy(configVersion = config.version) }
        try {
            runCatching {
                WebView.setWebContentsDebuggingEnabled(com.jarves.mh.BuildConfig.DEBUG)
            }
            val cookieManager = CookieManager.getInstance()
            cookieManager.setAcceptCookie(true)
            val view = WebView(context)
            view.settings.javaScriptEnabled = true
            view.settings.domStorageEnabled = true
            view.settings.loadWithOverviewMode = true
            view.settings.useWideViewPort = true
            view.settings.cacheMode = WebSettings.LOAD_DEFAULT
            view.settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            // Same UA as the visible sign-in sheet (WebView markers stripped):
            // the site does not bind sessions to the UA (live-tested 2026-10-18
            // — one token accepted under two different UAs), but sheet and
            // engine presenting ONE coherent Chrome profile removes a whole
            // class of drift and keeps the OAuth path safe in both WebViews.
            runCatching { view.settings.userAgentString = ZaiSheetUserAgent.chromeLike(view.settings.userAgentString) }
            cookieManager.setAcceptThirdPartyCookies(view, true)
            cookieManager.flush()
            update { it.copy(engineUserAgent = view.settings.userAgentString) }
            // The WebView is intentionally never attached to a view hierarchy: it is
            // a headless automation surface, laid out manually so the page has a
            // real viewport.
            view.layout(0, 0, LAYOUT_WIDTH_PX, LAYOUT_HEIGHT_PX)
            webView = view
            view
        } catch (e: Throwable) {
            Log.e(TAG, "WebView creation failed", e)
            update { it.copy(runPhase = ZaiRunPhase.ERROR, runMessage = "Could not create the hidden browser: ${e.message}") }
            null
        }
    }

    /**
     * Evaluates JS on the hidden WebView. Uses [Handler.post] rather than View.post —
     * the view is never attached, and an unattached View.post never executes.
     */
    private suspend fun WebView.evaluateJs(script: String): String? =
        suspendCancellableCoroutine { cont ->
            mainHandler.post {
                try {
                    evaluateJavascript(script) { result -> cont.resume(result) }
                } catch (e: Throwable) {
                    Log.w(TAG, "evaluateJavascript failed: ${e.message}")
                    cont.resume(null)
                }
            }
        }

    // ── Public API ──

    /**
     * Loads chat.z.ai in the hidden WebView and resolves the sign-in state via the
     * session auth probe. Never throws; the result is published into [ZaiWebUiState].
     */
    suspend fun checkConnection() {
        canceled = false
        loadPreviousRunOnce()
        update { it.copy(checkingConnection = true, runMessage = null) }
        val config = configLoader.load()
        if (config == null) {
            recordCheck(ZaiAuthProbeClassifier.ZaiCheckOutcome.CONFIG_MISSING, false) {
                it.copy(
                    checkingConnection = false,
                    signedIn = false,
                    runMessage = "Selector config unavailable.",
                )
            }
            return
        }
        refreshConfigInBackground()
        val view = ensureWebView()
        if (view == null) {
            update { it.copy(checkingConnection = false) }
            return
        }
        try {
            loadChatPage(view, config)
            val ready = waitForComposer(view, config)
            if (ready.composerReady && ready.blocked == PageBlock.NONE) {
                publishModelLabel(view, config)
            }
            if (ready.blocked == PageBlock.LOGIN) {
                recordCheck(ZaiAuthProbeClassifier.ZaiCheckOutcome.LOGIN_PAGE, false) {
                    it.copy(
                        checkingConnection = false,
                        signedIn = false,
                        accountEmail = null,
                    )
                }
                return
            }
            if (ready.blocked == PageBlock.CAPTCHA) {
                recordCheck(ZaiAuthProbeClassifier.ZaiCheckOutcome.CAPTCHA, null) {
                    it.copy(
                        checkingConnection = false,
                        signedIn = null,
                        runMessage = "chat.z.ai is showing a verification puzzle. Open Z.ai once, solve it, then check again.",
                    )
                }
                return
            }
            if (!ready.composerReady) {
                recordCheck(ZaiAuthProbeClassifier.ZaiCheckOutcome.PAGE_TIMEOUT, null) {
                    it.copy(
                        checkingConnection = false,
                        signedIn = null,
                        runMessage = "chat.z.ai did not load in time — open Diagnostics and check your connection.",
                    )
                }
                return
            }
            val verdict = probeAuthVerdict(view, config)
            recordCheck(verdict.outcome, verdict.signedIn) {
                it.copy(
                    checkingConnection = false,
                    signedIn = verdict.signedIn,
                    accountEmail = if (verdict.signedIn) verdict.accountEmail ?: it.accountEmail else null,
                    accountName = if (verdict.signedIn) verdict.accountName ?: it.accountName else null,
                    lastProbeSummary = ZaiAuthProbeClassifier.summary(verdict),
                    runMessage = if (!verdict.signedIn && verdict.outcome == ZaiAuthProbeClassifier.ZaiCheckOutcome.PROBE_ERROR) {
                        "Could not verify the Z.ai session: ${verdict.errorText}"
                    } else {
                        null
                    },
                )
            }
        } catch (e: CancellationException) {
            update { it.copy(checkingConnection = false) }
            throw e
        } catch (e: Throwable) {
            Log.w(TAG, "checkConnection failed: ${e.message}")
            recordCheck("${ZaiAuthProbeClassifier.ZaiCheckOutcome.CHECK_ERROR}:${e.message}".take(160), null) {
                it.copy(
                    checkingConnection = false,
                    signedIn = null,
                    runMessage = "Connection check failed: ${e.message}",
                )
            }
        }
    }

    /** Loads the previous run's outcome into the state once per process. */
    private fun loadPreviousRunOnce() {
        if (previousRunLoaded) return
        previousRunLoaded = true
        val (outcome, signedIn) = ledger.readPreviousRun()
        update { it.copy(previousRunOutcome = outcome, previousRunSignedIn = signedIn) }
    }

    /** Publishes a finished check (outcome label + timestamp) and records it for the next run. */
    private fun recordCheck(outcome: String, signedIn: Boolean?, extra: (ZaiWebUiState) -> ZaiWebUiState) {
        update {
            extra(it).copy(
                lastCheckOutcome = outcome,
                lastCheckAtMillis = System.currentTimeMillis(),
            )
        }
        ledger.record(outcome, signedIn)
    }

    /**
     * Sends [task] through the hidden WebView and waits for generation to finish.
     * [test] selects the tiny deterministic connection-test prompt.
     *
     * [continueChat] (chat-engine turns, v1.0.25) keeps the page's CURRENT
     * conversation instead of clicking New chat: when the composer is still
     * alive the turn is posted into the same chat.z.ai conversation, so the
     * site remembers the previous turns of this project chat. When the page
     * was reloaded, crashed, or never started (probe finds no composer), it
     * falls back to the normal fresh-load path — the prompt itself always
     * carries the full context, so either way the turn is self-contained.
     * Tab-task runs (AgentZaiCard) keep the default fresh-conversation mode.
     */
    suspend fun runPrompt(task: String, test: Boolean, continueChat: Boolean = false) {
        val config = configLoader.load()
        if (config == null) {
            update { it.copy(runPhase = ZaiRunPhase.ERROR, runMessage = "Selector config unavailable — Z.ai Web disabled.") }
            return
        }
        val prompt = try {
            if (test) ZaiPromptBuilder.TEST_PROMPT else ZaiPromptBuilder.buildTaskPrompt(task)
        } catch (e: IllegalArgumentException) {
            update { it.copy(runPhase = ZaiRunPhase.ERROR, runMessage = e.message) }
            return
        }
        canceled = false
        runNumber += 1
        val thisRun = runNumber
        update {
            it.copy(
                runPhase = ZaiRunPhase.OPENING_PAGE,
                runMessage = "Connecting to chat.z.ai…",
                runStartedAtMillis = System.currentTimeMillis(),
                testRunning = test,
                lastReply = null,
                lastFiles = emptyList(),
                lastFilesDir = null,
                lastFinishedAtMillis = null,
                liveThinking = null,
                liveAnswer = null,
                liveStepStatus = null,
                livePhaseLabel = null,
                liveUpdatedAtMillis = null,
            )
        }
        val view = ensureWebView() ?: return

        runJob = scope.launch {
            try {
                // 1. Load the page and wait until the composer exists (or a blocker shows).
                //    Continue-mode first probes the live page: an alive composer means
                //    the conversation can continue without a reload (a reload would
                //    depend on the site restoring the chat, which is not guaranteed).
                val ready: PageReady = if (continueChat) {
                    val live = runStateProbe(view, config)
                    when {
                        live == null -> {
                            loadChatPage(view, config)
                            waitForComposer(view, config)
                        }
                        live.optBoolean("captchaMatched") -> PageReady(false, PageBlock.CAPTCHA)
                        live.optBoolean("loginMatched") -> PageReady(false, PageBlock.LOGIN)
                        live.optBoolean("inputFound") -> PageReady(true, PageBlock.NONE)
                        else -> {
                            loadChatPage(view, config)
                            waitForComposer(view, config)
                        }
                    }
                } else {
                    loadChatPage(view, config)
                    waitForComposer(view, config)
                }
                if (canceled || thisRun != runNumber) return@launch
                when (ready.blocked) {
                    PageBlock.LOGIN -> {
                        update {
                            it.copy(
                                runPhase = ZaiRunPhase.NEEDS_LOGIN,
                                runMessage = "chat.z.ai is showing the sign-in page. Tap “Sign in to Z.ai”, log in, then send your task again.",
                                testRunning = false,
                            )
                        }
                        return@launch
                    }
                    PageBlock.CAPTCHA -> {
                        update {
                            it.copy(
                                runPhase = ZaiRunPhase.CAPTCHA,
                                runMessage = "chat.z.ai is showing a verification puzzle. Open Z.ai, solve it once, then try again.",
                                testRunning = false,
                            )
                        }
                        return@launch
                    }
                    PageBlock.NONE -> if (!ready.composerReady) {
                        update {
                            it.copy(
                                runPhase = ZaiRunPhase.ERROR,
                                runMessage = "chat.z.ai did not load its message box in time. Check your connection and try again.",
                                testRunning = false,
                            )
                        }
                        return@launch
                    }
                }

                // 2. Fresh conversation so the assistant-count baseline is unambiguous.
                //    Continue-mode skips this entirely: the current conversation IS the
                //    baseline, and the composer was verified in step 1.
                if (!continueChat) {
                    view.evaluateJs(ZaiProbeScripts.buildClickNewChatScript(config))
                    delay(config.timing.pageSettleMs)
                    loadChatPage(view, config)
                }
                val freshReady = if (continueChat) ready else {
                    waitForComposer(view, config)
                }
                if (canceled || thisRun != runNumber) return@launch
                if (!freshReady.composerReady) {
                    when (freshReady.blocked) {
                        PageBlock.LOGIN -> update {
                            it.copy(
                                runPhase = ZaiRunPhase.NEEDS_LOGIN,
                                runMessage = "chat.z.ai is showing the sign-in page. Tap “Sign in to Z.ai”, log in, then send your task again.",
                                testRunning = false,
                            )
                        }
                        PageBlock.CAPTCHA -> update {
                            it.copy(
                                runPhase = ZaiRunPhase.CAPTCHA,
                                runMessage = "chat.z.ai is showing a verification puzzle. Open Z.ai, solve it once, then try again.",
                                testRunning = false,
                            )
                        }
                        PageBlock.NONE -> update {
                            it.copy(
                                runPhase = ZaiRunPhase.ERROR,
                                runMessage = "chat.z.ai did not open a new conversation in time. Try again.",
                                testRunning = false,
                            )
                        }
                    }
                    return@launch
                }

                // 3. Baseline probe, then inject + send.
                val baseline = runStateProbe(view, config)?.optInt("assistantCount") ?: 0
                publishModelLabel(view, config)
                update { it.copy(runPhase = ZaiRunPhase.SENDING, runMessage = "Sending task to Z.ai…") }
                val injectedRaw = view.evaluateJs(ZaiProbeScripts.buildInjectScript(config, prompt))
                val injectedOk = runCatching {
                    JSONObject((injectedRaw ?: "{}").unquoteEvalResult()).optBoolean("ok")
                }.getOrDefault(false)
                if (!injectedOk) {
                    update { it.copy(runPhase = ZaiRunPhase.ERROR, runMessage = "Could not type into the chat box.", testRunning = false) }
                    return@launch
                }
                val sent = sendWithRetries(view, config)
                if (!sent) {
                    if (!canceled && thisRun == runNumber) {
                        update {
                            it.copy(
                                runPhase = ZaiRunPhase.ERROR,
                                runMessage = "The send button stayed disabled. Please try again.",
                                testRunning = false,
                            )
                        }
                    }
                    return@launch
                }

                // 4. Poll with the multi-signal finish detector.
                delay(config.timing.sendSettleMs)
                if (canceled || thisRun != runNumber) return@launch
                update { it.copy(runPhase = ZaiRunPhase.GENERATING, runMessage = "Z.ai is generating…") }
                var detectorState = ZaiFinishDetector.ZaiDetectorState(baselineAssistantCount = baseline)
                var pollElapsed = 0L
                // v1.0.33: the live-status fingerprint feeds the detector's
                // progress signal — tool/step text churns during long silent
                // phases on the site, and that churn must read as progress.
                var lastLiveFingerprint = ""
                while (isActive && !canceled && thisRun == runNumber) {
                    delay(config.timing.pollIntervalMs)
                    pollElapsed += config.timing.pollIntervalMs
                    val probe = runStateProbe(view, config) ?: continue
                    val observation = observationFromProbe(probe)
                    val live = streamLiveStatus(view, config)
                    val liveFingerprint = listOfNotNull(live?.thinking, live?.answer, live?.stepStatus)
                        .joinToString("\u0000")
                    val liveProgress = live != null && liveFingerprint != lastLiveFingerprint
                    lastLiveFingerprint = liveFingerprint
                    val (decision, newState) = ZaiFinishDetector.evaluate(
                        nowMs = pollElapsed,
                        startedMs = 0L,
                        observation = observation.copy(liveProgress = liveProgress),
                        state = detectorState,
                        timing = config.timing,
                    )
                    detectorState = newState
                    // Phase 1 live visibility: read the page's thinking / partial
                    // answer / tool status about once a second (the poll interval)
                    // and stream it into the state. Purely additive — the finish
                    // detector above is untouched.
                    streamLiveStatus(view, config)
                    when (decision) {
                        ZaiFinishDetector.ZaiDecision.WAIT -> update { s ->
                            s.copy(
                                runPhase = ZaiRunPhase.GENERATING,
                                runMessage = "Z.ai is generating… ${pollElapsed / 1000}s",
                            )
                        }
                        ZaiFinishDetector.ZaiDecision.FINISHED -> {
                            finishRun(view, config, test)
                            return@launch
                        }
                        ZaiFinishDetector.ZaiDecision.NEEDS_LOGIN -> {
                            update {
                                it.copy(
                                    runPhase = ZaiRunPhase.NEEDS_LOGIN,
                                    runMessage = "chat.z.ai asked you to sign in mid-task. Sign in and send the task again.",
                                    testRunning = false,
                                )
                            }
                            return@launch
                        }
                        ZaiFinishDetector.ZaiDecision.CAPTCHA -> {
                            update {
                                it.copy(
                                    runPhase = ZaiRunPhase.CAPTCHA,
                                    runMessage = "chat.z.ai showed a verification puzzle. Open Z.ai, solve it, then try again.",
                                    testRunning = false,
                                )
                            }
                            return@launch
                        }
                        ZaiFinishDetector.ZaiDecision.TIMEOUT -> {
                            update {
                                it.copy(
                                    runPhase = ZaiRunPhase.TIMEOUT,
                                    // v1.0.33: the page is QUIET (no progress for
                                    // the no-progress budget, or the overall cap
                                    // hit) — the site may still finish it; the
                                    // notice card offers Re-read / Sync files now.
                                    runMessage = "Z.ai went quiet on chat.z.ai (no page change for " +
                                        "${config.timing.noProgressTimeoutMs / 1000}s). The task may still be running there — " +
                                        "use Re-read reply or Sync files now in a moment.",
                                    testRunning = false,
                                )
                            }
                            return@launch
                        }
                    }
                }
            } catch (e: CancellationException) {
                // cancel() already published the CANCELED state and clicked stop.
                throw e
            } catch (e: Throwable) {
                Log.e(TAG, "Z.ai run failed", e)
                if (thisRun == runNumber && !canceled) {
                    update { it.copy(runPhase = ZaiRunPhase.ERROR, runMessage = "Z.ai Web error: ${e.message}", testRunning = false) }
                }
            }
        }
    }

    /** Cancels the active run (or test), clicks the page's stop button, publishes state. */
    fun cancel() {
        canceled = true
        runNumber += 1
        runJob?.cancel()
        runJob = null
        val view = webView
        val config = configLoader.load()
        if (view != null && config != null) {
            scope.launch(Dispatchers.Main) {
                view.evaluateJs(ZaiProbeScripts.buildClickStopScript(config))
            }
        }
        update { it.copy(runPhase = ZaiRunPhase.CANCELED, runMessage = "Stopped. Z.ai may still finish this turn in the background.", testRunning = false) }
    }

    /**
     * Opens the visible sign-in overlay; rendering is up to the UI layer.
     * The sheet automation payload (selector groups + auth probe path from the
     * loaded config) travels with the state so the UI never hardcodes selectors.
     */
    fun openSignInSheet() {
        val automationJson = configLoader.load()?.let { config ->
            runCatching {
                val obj = JSONObject()
                obj.put("loginFormMarker", JSONArray(config.selectorsFor("loginFormMarker")))
                obj.put("captchaMarker", JSONArray(config.selectorsFor("captchaMarker")))
                obj.put("sidebarToggle", JSONArray(config.selectorsFor("sidebarToggle")))
                obj.put("signInButton", JSONArray(config.selectorsFor("signInButton")))
                obj.put("authProbePath", config.authProbePath)
                obj.toString()
            }.getOrNull()
        }
        update { it.copy(signInSheetOpen = true, sheetAutomationJson = automationJson, sheetVerifiedAtMillis = null) }
    }

    fun closeSignInSheet() = update { it.copy(signInSheetOpen = false, sheetAutomationJson = null) }

    /** Re-checks sign-in after the user completed the visible sign-in flow. */
    suspend fun onSignedInViaSheet() {
        // Record the sheet's claim BEFORE the engine re-checks: if the hidden
        // engine still reports signed-out, the card must show the disagreement
        // instead of silently downgrading to "Not signed in".
        update { it.copy(sheetVerifiedAtMillis = System.currentTimeMillis()) }
        closeSignInSheet()
        checkConnection()
    }

    /** Publishes the visible sheet's diagnostics (all values secret-free). */
    fun publishSheetDiagnostics(url: String?, userAgent: String?, probeSummary: String?) {
        update {
            it.copy(
                sheetLastUrl = url?.takeIf(String::isNotBlank) ?: it.sheetLastUrl,
                sheetUserAgent = userAgent?.takeIf(String::isNotBlank) ?: it.sheetUserAgent,
                sheetProbeSummary = probeSummary ?: it.sheetProbeSummary,
            )
        }
    }

    /**
     * Clears the chat.z.ai session: every cookie for the host (including the JWT
     * "token" cookie) is expired. Values are never read or logged — only names.
     */
    fun disconnect() {
        cancel()
        runCatching {
            val cookieManager = CookieManager.getInstance()
            listOf(
                "https://chat.z.ai/",
                "https://z.ai/",
                "https://chatglm.cn/",
            ).forEach { base ->
                val cookieHeader = cookieManager.getCookie(base)
                cookieHeader?.split(';')?.forEach { piece ->
                    val name = piece.trim().substringBefore('=').trim()
                    if (name.isNotEmpty()) {
                        cookieManager.setCookie(base, "$name=; Path=/; Expires=Thu, 01 Jan 1970 00:00:00 GMT")
                    }
                }
            }
            cookieManager.flush()
        }
        val view = webView
        if (view != null) {
            mainHandler.post {
                runCatching { view.clearCache(true) }
                runCatching { view.clearHistory() }
            }
        }
        update {
            it.copy(
                signedIn = false,
                accountEmail = null,
                accountName = null,
                runPhase = ZaiRunPhase.IDLE,
                runMessage = "Signed out of chat.z.ai on this device.",
                lastReply = null,
                lastFiles = emptyList(),
                lastFilesDir = null,
                testRunning = false,
                lastCheckOutcome = ZaiAuthProbeClassifier.ZaiCheckOutcome.SIGNED_OUT_BY_USER,
                lastCheckAtMillis = System.currentTimeMillis(),
                lastProbeSummary = null,
                sheetVerifiedAtMillis = null,
            )
        }
        ledger.record(ZaiAuthProbeClassifier.ZaiCheckOutcome.SIGNED_OUT_BY_USER, false)
    }

    /** Destroys the hidden WebView; called when the owning view model goes away. */
    fun shutdown() {
        canceled = true
        runNumber += 1
        runJob?.cancel()
        runJob = null
        val view = webView
        webView = null
        view?.let { mainHandler.post { runCatching { it.destroy() } } }
    }

    // ── Internals ──

    private enum class PageBlock { NONE, LOGIN, CAPTCHA }

    private data class PageReady(val composerReady: Boolean, val blocked: PageBlock)

    private suspend fun loadChatPage(view: WebView, config: ZaiWebConfig) {
        withContext(Dispatchers.Main) {
            CookieManager.getInstance().flush()
            view.evaluateJavascript("window.__mhProbePrev=null; 'ok'", null)
            runCatching { view.loadUrl(config.startUrl) }
        }
    }

    private suspend fun waitForComposer(view: WebView, config: ZaiWebConfig): PageReady {
        val deadline = System.currentTimeMillis() + config.timing.pageLoadTimeoutMs
        while (System.currentTimeMillis() < deadline) {
            val probe = runStateProbe(view, config)
            if (probe != null) {
                val url = probe.optString("url")
                if (url.isNotBlank()) update { it.copy(engineLastUrl = url) }
                if (probe.optBoolean("captchaMatched")) return PageReady(false, PageBlock.CAPTCHA)
                if (probe.optBoolean("loginMatched")) return PageReady(false, PageBlock.LOGIN)
                if (probe.optBoolean("inputFound")) {
                    delay(config.timing.pageSettleMs)
                    return PageReady(true, PageBlock.NONE)
                }
            }
            delay(config.timing.pollIntervalMs)
        }
        return PageReady(false, PageBlock.NONE)
    }

    private suspend fun runStateProbe(view: WebView, config: ZaiWebConfig): JSONObject? {
        val raw = view.evaluateJs(ZaiProbeScripts.buildStateProbe(config)) ?: return null
        return try {
            JSONObject(raw.unquoteEvalResult())
        } catch (e: Exception) {
            Log.w(TAG, "State probe parse failed: ${e.message}")
            null
        }
    }

    /**
     * Runs the session probe (store-and-poll) and classifies it. Never throws.
     *
     * v1.0.23: the probe script no longer returns its Promise to
     * evaluateJavascript (which cannot await it — on-device evidence
     * 2026-10-18); it stores the result in `window.__mhAuthResult` and this
     * polls the synchronous reader until it lands or the budget expires.
     */
    private suspend fun probeAuthVerdict(view: WebView, config: ZaiWebConfig): ZaiAuthProbeClassifier.ZaiAuthVerdict {
        val raw = runAuthProbeScript(
            eval = { script -> view.evaluateJs(script) },
            injectScript = ZaiProbeScripts.buildAuthProbe(config),
            readerScript = ZaiProbeScripts.buildAuthResultReaderScript(),
            isReadable = { ZaiAuthProbeClassifier.parseEvalResult(it) != null },
            config = AuthProbePollPolicy.ENGINE_CHECK,
        )
        return if (raw == null) {
            probeTimeoutVerdict(AuthProbePollPolicy.totalWaitMs(AuthProbePollPolicy.ENGINE_CHECK))
        } else {
            ZaiAuthProbeClassifier.classify(ZaiAuthProbeClassifier.parseEvalResult(raw))
        }
    }

    private suspend fun sendWithRetries(view: WebView, config: ZaiWebConfig): Boolean {
        repeat(3) { attempt ->
            val raw = view.evaluateJs(ZaiProbeScripts.buildClickSendScript(config))
            val why = runCatching {
                JSONObject((raw ?: "{}").unquoteEvalResult())
            }.getOrNull()
            if (why?.optBoolean("ok") == true) return true
            if (why?.optString("why") == "send-disabled") {
                // Svelte may not have flushed the enabled state yet; nudge with Enter.
                val enterRaw = view.evaluateJs(ZaiProbeScripts.buildEnterFallbackScript(config))
                val enterOk = runCatching {
                    JSONObject((enterRaw ?: "{}").unquoteEvalResult()).optBoolean("ok")
                }.getOrDefault(false)
                if (enterOk) return true
                delay(400L * (attempt + 1))
            } else {
                delay(400L)
            }
        }
        return false
    }

    private fun observationFromProbe(probe: JSONObject): ZaiFinishDetector.ZaiObservation =
        ZaiFinishDetector.ZaiObservation(
            stopPresent = probe.optBoolean("stopPresent"),
            assistantCount = probe.optInt("assistantCount"),
            normalizedText = if (probe.isNull("normalizedText")) null else probe.optString("normalizedText"),
            loginMarkerMatched = probe.optBoolean("loginMatched"),
            captchaMarkerMatched = probe.optBoolean("captchaMatched"),
        )

    /**
     * Phase 1 live visibility: evaluates the live-status probe and publishes
     * the bounded snapshot into the state. Returns the parsed snapshot so the
     * run loop can feed its fingerprint into the finish detector's progress
     * signal. Never throws; a failed probe just leaves the previous snapshot
     * in place (the card keeps its last view).
     */
    private suspend fun streamLiveStatus(view: WebView, config: ZaiWebConfig): ZaiLiveStatus.Live? {
        val raw = view.evaluateJs(ZaiProbeScripts.buildLiveStatusScript(config)) ?: return null
        val live = ZaiLiveStatus.parse(raw) ?: return null
        update { current ->
            current.copy(
                liveThinking = live.thinking,
                liveAnswer = live.answer,
                liveStepStatus = live.stepStatus,
                livePhaseLabel = live.phase.label.ifBlank { current.livePhaseLabel },
                liveUpdatedAtMillis = System.currentTimeMillis(),
            )
        }
        return live
    }

    /** Drops the live snapshot (turn start / finish) so no stale text lingers. */
    private fun clearLiveStatus() {
        update {
            it.copy(
                liveThinking = null,
                liveAnswer = null,
                liveStepStatus = null,
                livePhaseLabel = null,
                liveUpdatedAtMillis = null,
            )
        }
    }

    /**
     * Reads the site's own model label (best effort) and publishes it. The
     * label NEVER changes anything in the page — read-only.
     */
    private suspend fun publishModelLabel(view: WebView, config: ZaiWebConfig) {
        val raw = view.evaluateJs(ZaiProbeScripts.buildModelLabelScript(config)) ?: return
        val model = ZaiModelCatalog.parseLabel(raw) ?: return
        update { it.copy(currentModel = model) }
    }

    /**
     * Opens the site's model dropdown in the hidden WebView, reads its option
     * rows, caches them into the state and closes the dropdown again.
     *
     * Returns the cached list. An unreadable list returns emptyList() — the
     * picker then falls back to the read-only label with a clear message.
     * Never throws.
     */
    suspend fun refreshModelCatalog(): List<ZaiModelCatalog.ModelOption> {
        val config = configLoader.load() ?: return emptyList()
        val view = ensureWebView() ?: return emptyList()
        update { it.copy(modelPickRunning = true) }
        val result = runCatching { readModelCatalog(view, config) }.getOrDefault(emptyList())
        // Always close whatever the automation opened so it can never cover
        // the composer for the next turn.
        view.evaluateJs(ZaiProbeScripts.buildDismissModelMenuScript())
        update { s ->
            s.copy(
                modelPickRunning = false,
                cachedModels = result.ifEmpty { s.cachedModels },
            )
        }
        return result
    }

    private suspend fun readModelCatalog(view: WebView, config: ZaiWebConfig): List<ZaiModelCatalog.ModelOption> {
        // The dropdown only renders on a live chat.z.ai page: make sure the
        // composer is there before clicking the trigger.
        val live = runStateProbe(view, config)
        if (live == null || !live.optBoolean("inputFound")) {
            loadChatPage(view, config)
            val ready = waitForComposer(view, config)
            if (!ready.composerReady || ready.blocked != PageBlock.NONE) return emptyList()
        }
        view.evaluateJs(ZaiProbeScripts.buildClickModelMenuScript(config))
        delay(700)
        val raw = view.evaluateJs(ZaiProbeScripts.buildModelOptionsScript(config))
        val models = ZaiModelCatalog.parse(raw)
        if (models.isNotEmpty()) publishModelLabel(view, config)
        return models
    }

    /**
     * Drives the site's own dropdown to [modelName] and verifies the pick by
     * reading the label back. Never asks the user anything: on automation
     * failure the outcome explains what happened and the app falls back to
     * the read-only label.
     */
    suspend fun pickModel(modelName: String): ZaiModelPickOutcome {
        val config = configLoader.load() ?: return ZaiModelPickOutcome(
            success = false, model = null, message = "Selector config unavailable — cannot reach chat.z.ai.",
        )
        val view = ensureWebView() ?: return ZaiModelPickOutcome(
            success = false, model = null, message = "The hidden browser is not available yet — try again.",
        )
        update { it.copy(modelPickRunning = true) }
        val outcome = runCatching { driveModelPick(view, config, modelName) }
            .getOrElse { ZaiModelPickOutcome(success = false, model = null, message = "Model switch failed: ${it.message}") }
        view.evaluateJs(ZaiProbeScripts.buildDismissModelMenuScript())
        update { it.copy(modelPickRunning = false) }
        return outcome
    }

    private suspend fun driveModelPick(view: WebView, config: ZaiWebConfig, modelName: String): ZaiModelPickOutcome {
        // 1. Read the catalog (also verifies the page is alive).
        val models = readModelCatalog(view, config)
        update { s -> s.copy(cachedModels = models.ifEmpty { s.cachedModels }) }
        if (models.isEmpty()) {
            return ZaiModelPickOutcome(
                success = false,
                model = null,
                message = "chat.z.ai's model list could not be read. Showing the current model read-only.",
            )
        }
        // 2. Resolve the request against the SITE's own options — never click blind.
        val target = ZaiModelCatalog.resolve(models, modelName)
        if (target == null) {
            return ZaiModelPickOutcome(
                success = false,
                model = null,
                message = "The site does not offer \"$modelName\". Available: ${models.joinToString { it.name }}.",
            )
        }
        // 3. Click the row, then verify by reading the label back.
        val clickedRaw = view.evaluateJs(ZaiProbeScripts.buildClickModelOptionScript(config, target.name))
        val clickedOk = runCatching {
            JSONObject((clickedRaw ?: "{}").unquoteEvalResult()).optBoolean("ok")
        }.getOrDefault(false)
        if (!clickedOk) {
            return ZaiModelPickOutcome(
                success = false,
                model = null,
                message = "Could not tap the \"${target.name}\" entry in the site's dropdown.",
            )
        }
        delay(600)
        val labelRaw = view.evaluateJs(ZaiProbeScripts.buildModelLabelScript(config))
        val label = ZaiModelCatalog.parseLabel(labelRaw)
        return if (ZaiModelCatalog.labelMatches(label, target.name)) {
            update { it.copy(currentModel = label) }
            ZaiModelPickOutcome(success = true, model = label, message = null)
        } else {
            ZaiModelPickOutcome(
                success = false,
                model = label,
                message = "Switched the dropdown but the page still shows \"${label ?: "?"}\" — keeping the read-only label.",
            )
        }
    }

    // ── Tab parity: the site's own Deep Think controls ──

    /**
     * Opens the site's Deep Think menu (when the page renders one), reads its
     * rows and closes it again. A not-found menu is a VALID outcome — the
     * control only exists signed-in on capable models; the UI hides the
     * section then. Never throws.
     */
    suspend fun refreshThinkControls() {
        val config = configLoader.load() ?: return
        val view = ensureWebView() ?: return
        update { it.copy(thinkControlsBusy = true, thinkControlsNote = null) }
        val controls = runCatching { readThinkControls(view, config) }.getOrNull()
        view.evaluateJs(ZaiProbeScripts.buildDismissModelMenuScript())
        update {
            it.copy(
                thinkControlsBusy = false,
                thinkControls = controls ?: it.thinkControls,
                thinkControlsNote = if (controls == null && it.thinkControls == null) {
                    "No reasoning controls found on the current page — the site shows them only for some models after sign-in."
                } else {
                    null
                },
            )
        }
    }

    private suspend fun readThinkControls(view: WebView, config: ZaiWebConfig): ZaiThinkControls.Controls? {
        // The menu lives on a live chat.z.ai page; make sure the composer is there.
        val live = runStateProbe(view, config)
        if (live == null || !live.optBoolean("inputFound")) {
            loadChatPage(view, config)
            val ready = waitForComposer(view, config)
            if (!ready.composerReady || ready.blocked != PageBlock.NONE) return null
        }
        val clicked = view.evaluateJs(ZaiProbeScripts.buildClickThinkMenuScript(config))
        val found = runCatching {
            JSONObject((clicked ?: "{}").unquoteEvalResult()).optBoolean("found")
        }.getOrDefault(false)
        if (!found) return null
        delay(600)
        val raw = view.evaluateJs(ZaiProbeScripts.buildThinkMenuReadScript(config))
        return ZaiThinkControls.parse(raw)
    }

    /**
     * Drives the site's own thinking controls: toggles Deep Think on/off
     * ([autoThink]) and/or selects an effort level ([effort]), then VERIFIES
     * by re-reading the menu. On any mismatch the outcome explains it and the
     * UI keeps the read state (read-only fallback).
     */
    suspend fun driveThinkControls(autoThink: Boolean?, effort: String?): ZaiThinkDriveOutcome {
        val config = configLoader.load() ?: return ZaiThinkDriveOutcome(false, "Selector config unavailable.")
        val view = ensureWebView() ?: return ZaiThinkDriveOutcome(false, "The hidden browser is not available yet — try again.")
        update { it.copy(thinkControlsBusy = true, thinkControlsNote = null) }
        val outcome = runCatching { driveThink(view, config, autoThink, effort) }
            .getOrElse { ZaiThinkDriveOutcome(false, "Could not drive the site's thinking controls: ${it.message}") }
        view.evaluateJs(ZaiProbeScripts.buildDismissModelMenuScript())
        update { it.copy(thinkControlsBusy = false) }
        return outcome
    }

    private suspend fun driveThink(view: WebView, config: ZaiWebConfig, autoThink: Boolean?, effort: String?): ZaiThinkDriveOutcome {
        if (autoThink == null && effort == null) {
            return ZaiThinkDriveOutcome(false, "Nothing to change.")
        }
        val before = readThinkControls(view, config) ?: return ZaiThinkDriveOutcome(
            false,
            "The site's thinking menu is not available on this page/model — controls stay hidden until the site shows them.",
        )
        if (autoThink != null && before.autoThink != null && before.autoThink != autoThink) {
            // Re-open and click the toggle row (the read left the menu closed).
            view.evaluateJs(ZaiProbeScripts.buildClickThinkMenuScript(config))
            delay(500)
            val clickedRaw = view.evaluateJs(ZaiProbeScripts.buildClickMenuItemScript(config, "Deep Think"))
            val clicked = runCatching {
                JSONObject((clickedRaw ?: "{}").unquoteEvalResult()).optBoolean("ok")
            }.getOrDefault(false)
            if (!clicked) {
                return ZaiThinkDriveOutcome(false, "Could not tap the site's Deep Think toggle.")
            }
            delay(500)
        }
        if (effort != null && before.effort?.equals(effort, ignoreCase = true) != true) {
            view.evaluateJs(ZaiProbeScripts.buildClickThinkMenuScript(config))
            delay(500)
            val clickedRaw = view.evaluateJs(ZaiProbeScripts.buildClickMenuItemScript(config, effort))
            val clicked = runCatching {
                JSONObject((clickedRaw ?: "{}").unquoteEvalResult()).optBoolean("ok")
            }.getOrDefault(false)
            if (!clicked) {
                return ZaiThinkDriveOutcome(false, "The site does not offer a \"$effort\" row in its thinking menu.")
            }
            delay(500)
        }
        // Verify by reading the menu state back.
        val after = readThinkControls(view, config)
        return if (after != null && ZaiThinkControls.verify(after, autoThink, effort)) {
            update { it.copy(thinkControls = after) }
            ZaiThinkDriveOutcome(true, null)
        } else {
            ZaiThinkDriveOutcome(
                false,
                "The menu did not reflect the change (site state: thinking=${after?.autoThink ?: "?"}, effort=${after?.effort ?: "?"}). Keeping the read-only view.",
            )
        }
    }

    // ── v1.0.33: file sync from the site ──

    /**
     * Manual "Sync files from Z.ai": reads the files the site wrote for the
     * CURRENT conversation (tool calls + versions store) without sending
     * anything, merges them with FILE: markers from the last reply the app
     * saw, and publishes them into the state ([ZaiWebUiState.lastFiles] +
     * [ZaiWebUiState.lastSync]) so the chat engine's apply pipeline can review
     * them. Runs even when a turn timed out earlier and the site has since
     * finished. Returns the honesty report.
     */
    suspend fun syncFilesFromSite(): ZaiFileSync.SyncReport {
        val config = configLoader.load()
        if (config == null) return syncFailure("Selector config unavailable — cannot reach chat.z.ai.")
        val view = ensureWebView() ?: return syncFailure("The hidden browser is not available yet — try again.")
        val markers = ZaiFileExtractor.parse(currentState().lastReply.orEmpty()).files
        val payload = runFileSyncProbe(view)
        val merged = ZaiFileSync.merge(payload, markers)
        update {
            it.copy(
                lastFiles = merged.files,
                lastSync = merged.report,
                lastFilesDir = null,
            )
        }
        return merged.report
    }

    private suspend fun syncFailure(message: String): ZaiFileSync.SyncReport {
        update { it.copy(fileSyncRunning = false) }
        return ZaiFileSync.SyncReport(
            wroteCount = 0,
            readablePaths = emptyList(),
            unreadablePaths = emptyList(),
            binarySkipped = emptyList(),
            sourceNotes = listOf(message),
            probeOk = false,
            attempts = 0,
        )
    }

    /**
     * "Re-read reply": re-attaches to the page after a timeout WITHOUT sending
     * anything. When the site has finished meanwhile, the normal finish flow
     * runs (reply extraction + file sync → DONE). When it is still generating,
     * the state says so honestly and stays in TIMEOUT so the notice card keeps
     * its buttons. Never throws.
     */
    suspend fun reattachToReply() {
        val config = configLoader.load() ?: return
        val view = ensureWebView() ?: return
        val probe = runStateProbe(view, config) ?: run {
            update {
                it.copy(
                    runPhase = ZaiRunPhase.TIMEOUT,
                    runMessage = "chat.z.ai could not be reached for a re-check. Try again in a moment.",
                )
            }
            return
        }
        val blocked = probe.optBoolean("loginMatched") || probe.optBoolean("captchaMatched")
        if (blocked) {
            update {
                it.copy(
                    runPhase = if (probe.optBoolean("loginMatched")) ZaiRunPhase.NEEDS_LOGIN else ZaiRunPhase.CAPTCHA,
                    runMessage = "chat.z.ai is asking for sign-in / verification on the re-check.",
                    testRunning = false,
                )
            }
            return
        }
        val generating = probe.optBoolean("stopPresent")
        if (generating) {
            update {
                it.copy(
                    runPhase = ZaiRunPhase.TIMEOUT,
                    runMessage = "Z.ai is still generating on the website — try Re-read again shortly, or Sync files once it finishes.",
                )
            }
            return
        }
        // Looks finished: run the normal finish flow (reply + files → DONE).
        finishRun(view, config, test = false)
    }

    private suspend fun finishRun(view: WebView, config: ZaiWebConfig, test: Boolean) {
        update { it.copy(runPhase = ZaiRunPhase.EXTRACTING, runMessage = "Reading Z.ai's reply…") }
        // The live streaming card hands over to the full reply now.
        clearLiveStatus()
        val raw = view.evaluateJs(ZaiProbeScripts.buildExtractScript(config))
        val extraction = parseExtraction(raw)
        if (extraction == null) {
            update {
                it.copy(
                    runPhase = ZaiRunPhase.ERROR,
                    runMessage = "Generation finished but the reply could not be read. Try again.",
                    testRunning = false,
                    lastFinishedAtMillis = System.currentTimeMillis(),
                )
            }
            return
        }
        val parsed = ZaiFileExtractor.parse(extraction.text)
        // v1.0.33: files Z.ai wrote in its OWN sandbox (tool calls + the site's
        // versions store) are read through the file-sync probe and MERGED with
        // the text markers. A connection test skips the probe entirely.
        val sync: ZaiFileSync.Merged = if (test) {
            ZaiFileSync.merge(ZaiFileSync.emptyPayload(), parsed.files)
        } else {
            val payload = runFileSyncProbe(view)
            ZaiFileSync.merge(payload, parsed.files)
        }
        val savedDir = if (sync.files.isNotEmpty() && !test) saveFiles(sync.files) else null
        update {
            it.copy(
                runPhase = ZaiRunPhase.DONE,
                runMessage = when {
                    test -> "Connection test finished."
                    sync.files.isEmpty() -> "Z.ai replied."
                    else -> "Z.ai replied with ${sync.files.size} file${if (sync.files.size == 1) "" else "s"}."
                },
                lastReply = extraction.text.ifBlank { "(empty reply)" },
                lastFiles = sync.files,
                lastFilesDir = savedDir,
                lastSync = sync.report.takeIf { !test },
                lastFinishedAtMillis = System.currentTimeMillis(),
                testRunning = false,
                signedIn = true,
            )
        }
    }

    /**
     * Runs the file-sync probe (store-and-poll) and parses the payload.
     * Never throws: an unreadable probe degrades to an empty payload and the
     * merge falls back to the reply-text markers alone.
     */
    private suspend fun runFileSyncProbe(view: WebView): ZaiFileSync.ProbePayload {
        update { it.copy(fileSyncRunning = true) }
        val raw = runStoreAndPoll(
            view = view,
            injectScript = ZaiProbeScripts.buildFileSyncProbeScript(),
            readerScript = ZaiProbeScripts.buildFileSyncResultReaderScript(),
            isReadable = { it != null && it != "null" && it.isNotBlank() },
            poll = FILE_SYNC_POLL,
        )
        update { it.copy(fileSyncRunning = false) }
        if (raw == null) {
            return ZaiFileSync.ProbePayload(
                chatFound = false, messageId = null,
                note = "the site's file data could not be read (probe timeout)",
                writes = emptyList(), edits = emptyList(), versionFiles = emptyList(),
                versionContents = emptyMap(), binaryKeys = emptyList(), failedKeys = emptyList(),
            )
        }
        return ZaiFileSync.parseProbe(raw)
    }

    /**
     * Generic store-and-poll loop (the v1.0.23 contract, generalized): inject
     * the async script, then read the synchronous reader until the stored
     * result lands or the budget expires.
     */
    private suspend fun runStoreAndPoll(
        view: WebView,
        injectScript: String,
        readerScript: String,
        isReadable: (String?) -> Boolean,
        poll: SyncPollConfig,
    ): String? {
        var state = SyncPollState()
        while (state.round < poll.rounds) {
            if (state.readInRound == 0) {
                view.evaluateJs(injectScript)
            }
            val raw = view.evaluateJs(readerScript)
            if (isReadable(raw)) return raw
            state = if (state.readInRound + 1 >= poll.readsPerRound) {
                SyncPollState(round = state.round + 1, readInRound = 0)
            } else {
                state.copy(readInRound = state.readInRound + 1)
            }
            delay(poll.readDelayMs)
        }
        return null
    }

    private data class SyncPollState(val round: Int = 0, val readInRound: Int = 0)

    private data class SyncPollConfig(val rounds: Int, val readsPerRound: Int, val readDelayMs: Long)

    /** ~16s worst case for the chat-detail + versions + per-file fetch chain. */
    private val FILE_SYNC_POLL = SyncPollConfig(rounds = 4, readsPerRound = 10, readDelayMs = 400)

    private fun parseExtraction(raw: String?): ExtractionResult? {
        if (raw == null) return null
        return try {
            val json = JSONObject(raw.unquoteEvalResult())
            if (!json.optBoolean("ok")) return null
            val blocks = json.optJSONArray("blocks") ?: JSONArray()
            val blockTexts = ArrayList<String>(blocks.length())
            for (i in 0 until blocks.length()) {
                val b = blocks.optJSONObject(i) ?: continue
                blockTexts.add(b.optString("text"))
            }
            ExtractionResult(text = json.optString("text"), codeBlocks = blockTexts)
        } catch (e: Exception) {
            Log.w(TAG, "Extraction parse failed: ${e.message}")
            null
        }
    }

    private data class ExtractionResult(val text: String, val codeBlocks: List<String>)

    private suspend fun saveFiles(files: List<ZaiFileExtractor.ZaiExtractedFile>): String? =
        withContext(Dispatchers.IO) {
            try {
                val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
                val dir = File(File(context.filesDir, OUTPUT_ROOT), stamp)
                files.forEach { file ->
                    val target = File(dir, file.path)
                    if (target.canonicalPath.startsWith(dir.canonicalPath)) {
                        target.parentFile?.mkdirs()
                        target.writeText(file.content)
                    }
                }
                dir.absolutePath
            } catch (e: Exception) {
                Log.w(TAG, "Saving extracted files failed: ${e.message}")
                null
            }
        }

    private fun refreshConfigInBackground() {
        scope.launch(Dispatchers.IO) {
            val newVersion = configLoader.refreshFromRemote()
            if (newVersion > 0) update { it.copy(configVersion = newVersion) }
        }
    }

    /**
     * `evaluateJavascript` hands expression results back as a JSON-quoted string;
     * unwrap that one level of quoting (never logs the payload — it can hold
     * user content).
     */
    private fun String.unquoteEvalResult(): String {
        val trimmed = trim()
        if (trimmed.length >= 2 && trimmed.first() == '"' && trimmed.last() == '"') {
            return runCatching {
                JSONArray("[$trimmed]").getString(0)
            }.getOrDefault(trimmed)
        }
        return trimmed
    }

    companion object {
        private const val TAG = "ZaiWebEngine"
        const val OUTPUT_ROOT = "zweb/outputs"
        const val LAYOUT_WIDTH_PX = 1080
        const val LAYOUT_HEIGHT_PX = 1920
    }
}
