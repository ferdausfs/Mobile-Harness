package com.jarves.mh

/**
 * Pure decision logic for the startup crash-recovery gate (v1.0.31).
 *
 * Why this exists: the 2026-10-19 v1.0.30 report ("app installs fine but
 * NEVER opens again") arrived with no logcat. CrashGuard already wrote the
 * report to disk on the device — but the ONLY reader for that report is the
 * Settings screen, which a startup-crash loop can never reach. This turns
 * the recorded crash into a decision the Activity can act on BEFORE the main
 * UI: show a recovery dialog (copy report / reset app data / continue), or a
 * full-screen variant when the crash loop repeats.
 *
 * The scheme is deliberately simple and time-based:
 *  - every time the Activity's first composition completes, the app stamps
 *    [lastOpenAtMillis] (a successful "the app opened" marker);
 *  - a crash captured AFTER that stamp (its report is newer) means the NEXT
 *    launch is a suspected startup crash;
 *  - two or more such launches in a row escalate to the full-screen variant.
 *
 * Everything here is JVM-testable; the Compose gate in MainActivity only
 * applies the decision.
 */
object CrashRecovery {

    /** How many suspected startup crashes escalate from a dialog to a full-screen gate. */
    const val FULL_SCREEN_THRESHOLD = 2

    /**
     * A crash report is treated as "newer than the last good open" when it is
     * stamped at least this much later. Clock skew between the crash write and
     * the open stamp is impossible in-process, but the two writes can legally
     * be nearly simultaneous (crash right after open) — the slack keeps that
     * ambiguous pair from looping the gate forever.
     */
    const val NEWER_THAN_SLACK_MILLIS = 1_000L

    enum class Action { NONE, DIALOG, FULL_SCREEN }

    data class State(
        /** Suspected startup crashes since the last successful open. */
        val startupCrashes: Int,
        /** Timestamp of the newest crash report; null = none on disk. */
        val lastCrashAtMillis: Long?,
        /** Timestamp of the last successful first-frame render; 0 = never. */
        val lastOpenAtMillis: Long,
    )

    /**
     * The decision for this launch. [state] is computed from disk + prefs
     * BEFORE the main UI renders; the result tells the Activity whether to
     * gate it behind the recovery UI.
     */
    fun decide(state: State): Action {
        val crashAt = state.lastCrashAtMillis ?: return Action.NONE
        // No "opened" marker yet (fresh install / cleared prefs / the very
        // first launch of an update): ANY recorded crash counts as newer —
        // there was no successful open for it to predate.
        if (state.lastOpenAtMillis <= 0L) {
            return if (state.startupCrashes >= FULL_SCREEN_THRESHOLD) Action.FULL_SCREEN else Action.DIALOG
        }
        // The crash predates the last good open: it belongs to a launch that
        // already recovered. Normal startup.
        if (crashAt <= state.lastOpenAtMillis + NEWER_THAN_SLACK_MILLIS) return Action.NONE
        return if (state.startupCrashes >= FULL_SCREEN_THRESHOLD) Action.FULL_SCREEN else Action.DIALOG
    }

    /**
     * Counter bookkeeping for one launch: called when the Activity starts and
     * the crash report is newer than the last good open (a suspected startup
     * crash this process is about to walk into again).
     */
    fun countStartupCrash(startupCrashes: Int): Int = (startupCrashes + 1).coerceAtMost(99)

    /** Called when the first frame actually rendered — the app opened. */
    fun resetStartupCrashes(): Int = 0
}
