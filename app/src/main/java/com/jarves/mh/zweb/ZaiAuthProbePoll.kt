package com.jarves.mh.zweb

import kotlinx.coroutines.delay

/**
 * Drives the store-and-poll auth probe contract over any `evaluateJavascript`
 * bridge.
 *
 * Android-free by design: the eval bridge is injected as a lambda, so JVM tests
 * run the EXACT loop the app runs on a real WebView. [eval] receives a script
 * and returns the raw evaluateJavascript callback payload (the WebView's
 * JSON-quoted completion value) — or null when the bridge itself failed.
 *
 * Loop shape per [AuthProbePollPolicy]:
 *  - each round starts with INJECT_AND_READ: run the injector (clears + starts
 *    the in-page fetches, guarded single-flight) and read once immediately;
 *  - then READ-only attempts every [AuthProbePollPolicy.Config.readDelayMs];
 *  - the first READABLE read wins and is returned raw (callers parse it with
 *    [ZaiAuthProbeClassifier.parseEvalResult] — the stored shape is unchanged);
 *  - an exhausted budget returns null, which callers render as
 *    [probeTimeoutVerdict] so Diagnostics shows the real reason instead of a
 *    silent "not signed in".
 */
suspend fun runAuthProbeScript(
    eval: suspend (String) -> String?,
    injectScript: String,
    readerScript: String,
    isReadable: (String?) -> Boolean,
    config: AuthProbePollPolicy.Config = AuthProbePollPolicy.ENGINE_CHECK,
    delayFn: suspend (Long) -> Unit = { delay(it) },
): String? {
    var state = AuthProbePollPolicy.State()
    while (true) {
        val action = AuthProbePollPolicy.next(state, config)
        if (action == AuthProbePollPolicy.Action.GIVE_UP) return null
        if (action == AuthProbePollPolicy.Action.INJECT_AND_READ) {
            eval(injectScript)
        }
        val raw = eval(readerScript)
        if (isReadable(raw)) return raw
        state = AuthProbePollPolicy.afterUnreadableRead(state, config)
        if (AuthProbePollPolicy.next(state, config) != AuthProbePollPolicy.Action.GIVE_UP) {
            delayFn(config.readDelayMs)
        }
    }
}

/**
 * Verdict for "the poll budget expired without a readable result". Deliberately
 * distinct from [ZaiAuthProbeClassifier.classify](null)'s generic message: this
 * names the timeout and its budget so the Diagnostics screen shows the real
 * reason (page still loading / probe fetch hung) instead of a bare
 * "did not return a readable result".
 */
fun probeTimeoutVerdict(waitedMs: Long): ZaiAuthProbeClassifier.ZaiAuthVerdict =
    ZaiAuthProbeClassifier.ZaiAuthVerdict(
        signedIn = false,
        outcome = ZaiAuthProbeClassifier.ZaiCheckOutcome.PROBE_ERROR,
        role = "",
        emailPresent = false,
        hasLocalToken = false,
        errorText = "probe result did not arrive within ${waitedMs}ms (page may still be loading or the probe fetch hung)",
    )
