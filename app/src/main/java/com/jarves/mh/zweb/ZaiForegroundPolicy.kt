package com.jarves.mh.zweb

/**
 * Pure policy for the Z.ai engine's foreground service (Phase 3).
 *
 * The service is declared `dataSync` because its job is exactly that:
 * streaming generation data between the app and chat.z.ai while the user is
 * away. Android 14+ caps dataSync foreground services at 6 hours per 24h
 * window — the engine must checkpoint BEFORE the platform kills it and
 * notify the user instead of dying silently. All the timing math lives here
 * so it is JVM-testable.
 */
object ZaiForegroundPolicy {

    /** Android 14+ dataSync limit: 6 hours per 24h window. */
    const val DATA_SYNC_LIMIT_MILLIS = 6 * 60 * 60_000L

    /** Checkpoint this long BEFORE the limit so the shutdown stays graceful. */
    const val CHECKPOINT_LEAD_MILLIS = 10 * 60_000L

    /** Elapsed time at which the service must checkpoint and step down. */
    val checkpointDeadlineMillis: Long = DATA_SYNC_LIMIT_MILLIS - CHECKPOINT_LEAD_MILLIS

    /**
     * True when the service has been running long enough that it must
     * checkpoint + notify + stop foreground gracefully.
     */
    fun mustCheckpoint(elapsedMillis: Long): Boolean = elapsedMillis >= checkpointDeadlineMillis

    /**
     * Human line for the persistent notification while the engine runs.
     * `phase` is the user-facing label ("Thinking", "Answering", ...),
     * `step` the latest tool status line; the step wins when present.
     */
    fun notificationDetail(phase: String?, step: String?, elapsedSeconds: Long): String {
        val elapsed = when {
            elapsedSeconds < 60 -> "${elapsedSeconds}s"
            elapsedSeconds < 3600 -> "${elapsedSeconds / 60}m ${elapsedSeconds % 60}s"
            else -> "${elapsedSeconds / 3600}h ${(elapsedSeconds % 3600) / 60}m"
        }
        return when {
            !step.isNullOrBlank() -> "$step · $elapsed"
            !phase.isNullOrBlank() -> "$phase · $elapsed"
            else -> "Working on your task · $elapsed"
        }
    }

    /**
     * The checkpoint notification's text when the dataSync window forces a
     * graceful stop.
     */
    val checkpointMessage: String =
        "Z.ai ran up to Android's 6-hour background limit for data sync. The task was checkpointed — " +
            "reopen the app to continue; the queue keeps unfinished tasks."

    /** Wake-lock duration: the dataSync window, not more. */
    const val WAKE_LOCK_MILLIS: Long = DATA_SYNC_LIMIT_MILLIS
}
