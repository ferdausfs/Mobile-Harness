package com.jarves.mh.zweb

/**
 * v1.0.35 — retry/backoff policy for reading the finished reply out of the
 * hidden WebView (the "Generation finished but the reply could not be read"
 * hotfix).
 *
 * WHY: finish detection fires when the stop indicator disappears, but the
 * page's markdown tree can still be hydrating (or the assistant container
 * re-mounts right at that moment). v1.0.34 read the DOM exactly ONCE and
 * gave up — a transient miss became a hard error. The user order: retry
 * with backoff for ~30 s after the stop button disappears before giving up.
 *
 * The schedule is deliberately FRONT-loaded (the re-render usually settles
 * within a few seconds) and bounded (~30 s total) so a genuinely dead page
 * still produces an honest error quickly. Pure JVM — the engine just sleeps
 * between attempts and reports [attempt] results.
 */
object ZaiReplyCapturePolicy {

    /**
     * Delay before each extraction ATTEMPT, ms. Index 0 runs immediately
     * (the v1.0.34 single read), later attempts back off. The last entry
     * keeps the final wait inside the ~30 s window.
     */
    val ATTEMPT_DELAYS_MILLIS = longArrayOf(0, 2_000, 4_000, 7_000, 11_000, 16_000, 22_000)

    /** Total retry window (the user's "~30 s"). Cumulative idle before the last attempt. */
    const val WINDOW_MILLIS = 30_000L

    fun maxAttempts(): Int {
        var total = 0L
        var count = 0
        for (d in ATTEMPT_DELAYS_MILLIS) {
            if (total + d > WINDOW_MILLIS) break
            total += d
            count++
        }
        return count
    }

    /** Delay to sleep BEFORE attempt [attempt] (0-based). 0 when out of schedule. */
    fun delayBeforeAttempt(attempt: Int): Long =
        ATTEMPT_DELAYS_MILLIS.getOrNull(attempt) ?: 0L

    /**
     * True when [attempt] (0-based) is the last one the policy allows.
     * The engine must produce an honest final error after it.
     */
    fun isFinalAttempt(attempt: Int): Boolean = attempt >= maxAttempts() - 1

    /**
     * Redacted capture diagnostic — counts and lengths only, never content
     * (the reply text itself may carry secrets; it never enters diagnostics).
     */
    data class CaptureDiag(
        val attempt: Int,
        val ok: Boolean,
        /** Which assistant-message selector group matched, if any (index into the group). */
        val assistantSelectorHit: Int?,
        /** How many assistant-message elements the winning selector matched. */
        val assistantElementCount: Int,
        /** True when the stop indicator was still present during this read. */
        val stopPresent: Boolean,
        /** Reply text length in chars (0 when the read failed). */
        val textLength: Int,
        /** The page URL at read time (path only — no query, no tokens). */
        val urlPath: String?,
        /** Script-level failure reason when ok=false ("no-assistant" etc.). */
        val why: String?,
    ) {
        /** One-line, secret-free summary for the honesty card / logs. */
        fun summary(): String = buildString {
            append("attempt ").append(attempt + 1)
            append(if (ok) " ok" else " failed")
            append(", assistant selectors matched: ")
            append(assistantSelectorHit?.let { "#$it" } ?: "none")
            append(" (count ").append(assistantElementCount).append(")")
            append(", stop button ")
            append(if (stopPresent) "still visible" else "gone")
            append(", reply length ").append(textLength)
            urlPath?.let { append(", url ").append(it.take(60)) }
            why?.let { append(", why ").append(it.take(40)) }
        }
    }
}
