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

    fun runTest() {
        val engine = ensureEngine() ?: return
        scope.launch { engine.runPrompt("", test = true) }
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
