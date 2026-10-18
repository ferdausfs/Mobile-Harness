package com.jarves.mh.zweb

/**
 * Pure round/attempt state machine for reading the auth probe result that the
 * injected script stores in `window.__mhAuthResult` on the page.
 *
 * Why polling exists (on-device evidence 2026-10-18, user Diagnostics):
 * `WebView.evaluateJavascript` hands its callback the script's SYNCHRONOUS
 * completion value, JSON-serialized. The pre-v1.0.23 auth probe's completion
 * value was a `Promise` (fetch + Promise.all), which the real Android WebView
 * never awaits — the callback received `"{}"`/null and every probe read
 * `outcome=probe-error, error="probe did not return a readable result"` even
 * though the site session cookies existed and login had succeeded. Headless
 * Chromium tests awaited the promise, so unit/remote checks could not catch it.
 *
 * The probe script now STORES its result (`window.__mhAuthResult`) and callers
 * poll a tiny synchronous reader script. This object owns the loop decisions;
 * [runAuthProbeScript] owns the loop itself, and both are JVM-testable.
 */
object AuthProbePollPolicy {

    enum class Action { INJECT_AND_READ, READ, GIVE_UP }

    data class State(val round: Int = 0, val readInRound: Int = 0)

    data class Config(
        val rounds: Int,
        val readsPerRound: Int,
        val readDelayMs: Long,
    )

    /**
     * Engine check: one-shot user-triggered action, a spinner is visible, so a
     * generous budget is fine (~9s worst case incl. eval overhead).
     */
    val ENGINE_CHECK = Config(rounds = 3, readsPerRound = 10, readDelayMs = 300)

    /**
     * Sheet poll tick: the tick must not stall the sign-in automation for long.
     * A short budget relies on the stored result surviving the tick — the next
     * tick's reader finds a slow fetch's result (the injector's in-flight guard
     * keeps it single-flight per document instead of re-clearing).
     */
    val SHEET_POLL = Config(rounds = 1, readsPerRound = 4, readDelayMs = 250)

    /** Sheet close / page-finished fast path: short, then act. */
    val SHEET_FINAL = Config(rounds = 2, readsPerRound = 3, readDelayMs = 250)

    /** What the caller should do in [State]. */
    fun next(state: State, config: Config): Action = when {
        state.round >= config.rounds -> Action.GIVE_UP
        state.readInRound == 0 -> Action.INJECT_AND_READ
        else -> Action.READ
    }

    /**
     * Advance after an UNREADABLE read. A readable result ends the loop in the
     * caller and must not be passed here.
     */
    fun afterUnreadableRead(state: State, config: Config): State =
        if (state.readInRound + 1 >= config.readsPerRound) {
            State(round = state.round + 1, readInRound = 0)
        } else {
            state.copy(readInRound = state.readInRound + 1)
        }

    /** Nominal wait budget (excludes eval overhead) — used in timeout messages. */
    fun totalWaitMs(config: Config): Long =
        config.rounds.toLong() * config.readsPerRound * config.readDelayMs
}
