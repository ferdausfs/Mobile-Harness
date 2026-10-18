package com.jarves.mh.update

/**
 * Pure decision logic for when the in-app update check runs and when an
 * attempt may be recorded against its throttle.
 *
 * Why this exists: up to v1.0.21 the startup check stamped
 * `lastAppUpdateCheckMillis` even when the network check threw, so one
 * failed startup check (flaky connection, GitHub hiccup) silenced the
 * updater for a full day, and release builds had no manual way to re-check.
 * Reported on v1.0.20 as "the app never updates" while v1.0.21 was already
 * published and served correctly — the manifest was fine, the client just
 * refused to look again.
 */
object AppUpdateCheckPolicy {

    /** Minimum interval between automatic (non-forced) update checks. */
    const val DEFAULT_THROTTLE_MILLIS: Long = 24L * 60L * 60L * 1000L

    /**
     * Whether a check may run now. Forced checks (Settings → Check for
     * updates) always run. Automatic checks run when no check has ever
     * completed ([lastCheckMillis] <= 0) or the throttle window has elapsed.
     */
    fun shouldCheck(
        lastCheckMillis: Long,
        nowMillis: Long,
        force: Boolean,
        throttleMillis: Long = DEFAULT_THROTTLE_MILLIS,
    ): Boolean {
        if (force) return true
        if (lastCheckMillis <= 0L) return true
        return nowMillis - lastCheckMillis >= throttleMillis
    }

    /**
     * Whether this attempt may be recorded as the last check time. Only a
     * check that actually completed — found an update, or definitively
     * reported none — may set the stamp. A check that threw (network error,
     * bad response) must NOT advance the throttle, otherwise the next
     * automatic attempt would be blocked for a full day after a failure.
     */
    fun shouldRecordAttempt(checkCompleted: Boolean): Boolean = checkCompleted
}
