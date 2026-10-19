package com.jarves.mh.zweb

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Owns the Z.ai Web feature end to end for [com.jarves.mh.ui.MainViewModel]:
 * config loading, the hidden-WebView engine, and the UI state publication.
 *
 * Deliberately NOT part of AgentRegistry / AgentDriver — Z.ai Web is not a CLI
 * agent and never runs in the private Linux runtime.
 */
class ZaiWebController(private val appContext: Context, private val onState: (ZaiWebUiState) -> Unit) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val configLoader = ZaiConfigLoader(appContext)

    @Volatile
    private var initialized = false

    private var engine: ZaiWebEngine? = null

    private fun ensureEngine(): ZaiWebEngine? {
        if (initialized) return engine
        synchronized(this) {
            if (initialized) return engine
            initialized = true
            val stateCallback: (ZaiWebUiState) -> Unit = { state -> onState(state) }
            engine = try {
                ZaiWebEngine(appContext, scope, configLoader, stateCallback)
            } catch (e: Throwable) {
                Log.e(TAG, "ZaiWebEngine init failed", e)
                null
            }
            return engine
        }
    }

    fun state(): ZaiWebUiState = engine?.currentState() ?: ZaiWebUiState(configVersion = configLoader.load()?.version ?: 0)

    /** Lazily creates the engine and refreshes the sign-in state. */
    fun checkConnection() {
        val engine = ensureEngine() ?: return
        scope.launch { engine.checkConnection() }
    }

    fun runTask(task: String) {
        val engine = ensureEngine() ?: return
        scope.launch { engine.runPrompt(task, test = false) }
    }

    /**
     * One turn of the project workspace chat when Z.ai is the chat engine.
     * [continueChat] keeps the page's current chat.z.ai conversation
     * (see [ZaiWebEngine.runPrompt]); the Z.ai tab's task runner never uses it.
     */
    fun runChatTurn(prompt: String, continueChat: Boolean) {
        val engine = ensureEngine() ?: return
        scope.launch { engine.runPrompt(prompt, test = false, continueChat = continueChat) }
    }

    fun runTest() {
        val engine = ensureEngine() ?: return
        scope.launch { engine.runPrompt("", test = true) }
    }

    /**
     * Phase 1 model picker: reads the site's own dropdown options through the
     * hidden WebView and caches them. [onResult] receives the fresh list
     * (empty when the automation could not read it — keep the old cache).
     */
    fun refreshModelCatalog(onResult: (List<ZaiModelCatalog.ModelOption>) -> Unit = {}) {
        val engine = ensureEngine() ?: run {
            onResult(emptyList())
            return
        }
        scope.launch { onResult(engine.refreshModelCatalog()) }
    }

    /**
     * Phase 1 model picker: drives the site's own dropdown to [modelName] and
     * verifies the pick by reading the label back. The outcome carries a
     * human message for the fallback case (read-only label).
     */
    fun pickModel(modelName: String, onResult: (ZaiModelPickOutcome) -> Unit = {}) {
        val engine = ensureEngine() ?: run {
            onResult(ZaiModelPickOutcome(success = false, model = null, message = "The Z.ai engine is not started yet."))
            return
        }
        scope.launch { onResult(engine.pickModel(modelName)) }
    }

    /** Tab parity: reads what reasoning controls the site currently offers (null = none found). */
    fun refreshThinkControls() {
        val engine = ensureEngine() ?: return
        scope.launch { engine.refreshThinkControls() }
    }

    /** Tab parity: drives the site's own Deep Think toggle / effort selection with verify-by-reread. */
    fun driveThinkControls(autoThink: Boolean?, effort: String?, onResult: (ZaiThinkDriveOutcome) -> Unit = {}) {
        val engine = ensureEngine() ?: run {
            onResult(ZaiThinkDriveOutcome(false, "The Z.ai engine is not started yet."))
            return
        }
        scope.launch { onResult(engine.driveThinkControls(autoThink, effort)) }
    }

    fun cancel() {
        engine?.cancel()
    }

    fun openSignInSheet() {
        val engine = ensureEngine() ?: return
        engine.openSignInSheet()
    }

    fun closeSignInSheet() {
        engine?.closeSignInSheet()
    }

    fun onSignedInViaSheet() {
        val engine = ensureEngine() ?: return
        scope.launch { engine.onSignedInViaSheet() }
    }

    /** Publishes the visible sheet's secret-free diagnostics into the UI state. */
    fun publishSheetDiagnostics(diagnostics: ZaiSheetDiagnostics) {
        engine?.publishSheetDiagnostics(diagnostics.url, diagnostics.userAgent, diagnostics.probeSummary)
    }

    fun disconnect() {
        engine?.disconnect()
    }

    /** Tears everything down (ViewModel.onCleared). */
    fun shutdown() {
        engine?.shutdown()
        engine = null
        initialized = false
        scope.cancel()
    }

    companion object {
        private const val TAG = "ZaiWebController"
    }
}
