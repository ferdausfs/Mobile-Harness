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

    private var webView: WebView? = null
    private var runJob: Job? = null
    private var runNumber = 0

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
            cookieManager.setAcceptThirdPartyCookies(view, true)
            cookieManager.flush()
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
        update { it.copy(checkingConnection = true, runMessage = null) }
        val config = configLoader.load()
        if (config == null) {
            update { it.copy(checkingConnection = false, signedIn = false, runMessage = "Selector config unavailable.") }
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
            if (ready.blocked == PageBlock.LOGIN) {
                update { it.copy(checkingConnection = false, signedIn = false, accountEmail = null) }
                return
            }
            if (ready.blocked == PageBlock.CAPTCHA) {
                update {
                    it.copy(
                        checkingConnection = false,
                        signedIn = null,
                        runMessage = "chat.z.ai is showing a verification puzzle. Open Z.ai once, solve it, then check again.",
                    )
                }
                return
            }
            val auth = probeAuth(view, config)
            update {
                it.copy(
                    checkingConnection = false,
                    signedIn = auth?.optBoolean("signedIn") == true,
                    accountEmail = auth?.optString("email").orEmpty().takeIf(String::isNotBlank),
                )
            }
        } catch (e: CancellationException) {
            update { it.copy(checkingConnection = false) }
            throw e
        } catch (e: Throwable) {
            Log.w(TAG, "checkConnection failed: ${e.message}")
            update { it.copy(checkingConnection = false, signedIn = null, runMessage = "Connection check failed: ${e.message}") }
        }
    }

    /**
     * Sends [task] through the hidden WebView and waits for generation to finish.
     * [test] selects the tiny deterministic connection-test prompt.
     */
    suspend fun runPrompt(task: String, test: Boolean) {
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
            )
        }
        val view = ensureWebView() ?: return

        runJob = scope.launch {
            try {
                // 1. Load the page and wait until the composer exists (or a blocker shows).
                loadChatPage(view, config)
                val ready = waitForComposer(view, config)
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
                view.evaluateJs(ZaiProbeScripts.buildClickNewChatScript(config))
                delay(config.timing.pageSettleMs)
                loadChatPage(view, config)
                val freshReady = waitForComposer(view, config)
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
                while (isActive && !canceled && thisRun == runNumber) {
                    delay(config.timing.pollIntervalMs)
                    pollElapsed += config.timing.pollIntervalMs
                    val probe = runStateProbe(view, config) ?: continue
                    val observation = observationFromProbe(probe)
                    val (decision, newState) = ZaiFinishDetector.evaluate(
                        nowMs = pollElapsed,
                        startedMs = 0L,
                        observation = observation,
                        state = detectorState,
                        timing = config.timing,
                    )
                    detectorState = newState
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
                                    runMessage = "Z.ai did not finish within ${config.timing.generationTimeoutMs / 1000}s. Try a smaller task or send it again.",
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
        update { it.copy(signInSheetOpen = true, sheetAutomationJson = automationJson) }
    }

    fun closeSignInSheet() = update { it.copy(signInSheetOpen = false, sheetAutomationJson = null) }

    /** Re-checks sign-in after the user completed the visible sign-in flow. */
    suspend fun onSignedInViaSheet() {
        closeSignInSheet()
        checkConnection()
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
                runPhase = ZaiRunPhase.IDLE,
                runMessage = "Signed out of chat.z.ai on this device.",
                lastReply = null,
                lastFiles = emptyList(),
                lastFilesDir = null,
                testRunning = false,
            )
        }
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

    private suspend fun probeAuth(view: WebView, config: ZaiWebConfig): JSONObject? {
        val raw = view.evaluateJs(ZaiProbeScripts.buildAuthProbe(config)) ?: return null
        return try {
            JSONObject(raw.unquoteEvalResult())
        } catch (e: Exception) {
            null
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

    private suspend fun finishRun(view: WebView, config: ZaiWebConfig, test: Boolean) {
        update { it.copy(runPhase = ZaiRunPhase.EXTRACTING, runMessage = "Reading Z.ai's reply…") }
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
        val savedDir = if (parsed.files.isNotEmpty() && !test) saveFiles(parsed.files) else null
        update {
            it.copy(
                runPhase = ZaiRunPhase.DONE,
                runMessage = when {
                    test -> "Connection test finished."
                    parsed.files.isEmpty() -> "Z.ai replied."
                    else -> "Z.ai replied with ${parsed.files.size} file${if (parsed.files.size == 1) "" else "s"}."
                },
                lastReply = extraction.text.ifBlank { "(empty reply)" },
                lastFiles = parsed.files,
                lastFilesDir = savedDir,
                lastFinishedAtMillis = System.currentTimeMillis(),
                testRunning = false,
                signedIn = true,
            )
        }
    }

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
