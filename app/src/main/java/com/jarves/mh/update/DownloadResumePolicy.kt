package com.jarves.mh.update

/**
 * Pure decision logic for the resumable APK download. Kept free of Android
 * and network types so resume behavior is JVM-testable: on the user's phone
 * the network repeatedly fails to carry an ~88 MB download in one connection
 * (three releases' worth of "download complete hoy na" reports), so the
 * updater must be able to continue an interrupted download instead of
 * starting over — and a partially downloaded file must never be handed to
 * the installer.
 */
object DownloadResumePolicy {

    /** Total connection attempts for one update download (the first try included). */
    const val MAX_ATTEMPTS = 5

    /** Linear backoff after failed attempt N: 1s, 2s, 3s, 4s (capped). */
    fun backoffMillis(failedAttempt: Int): Long = 1_000L * failedAttempt.coerceIn(1, 4)

    enum class Action {
        /** Server honored the Range request (HTTP 206): append at [Decision.offset]. */
        RESUME,

        /** Full body is coming (server ignored Range, or fresh start): write from zero. */
        RESTART,

        /** The partial file is unusable (HTTP 416): delete it, retry from scratch. */
        RETRY_FRESH,

        /** Permanent failure (auth, missing asset, …): surface [Decision.failure], no retry. */
        FAIL,
    }

    data class Decision(
        val action: Action,
        /** Absolute offset in the full file where this response's body starts. */
        val offset: Long,
        /** Expected full-file size; non-positive when unknown. */
        val total: Long,
        val failure: String? = null,
    )

    /**
     * @param httpCode          response code actually received
     * @param resumeFrom        bytes already on disk when the request was sent
     * @param contentLength     Content-Length of THIS response body (-1 when absent; for 206 this is the REMAINING length)
     * @param manifestSizeBytes full size advertised by the update manifest (-1 when absent)
     */
    fun decide(httpCode: Int, resumeFrom: Long, contentLength: Long, manifestSizeBytes: Long): Decision = when {
        httpCode == 206 -> Decision(
            action = Action.RESUME,
            offset = resumeFrom,
            total = if (contentLength > 0) resumeFrom + contentLength else manifestSizeBytes,
        )
        httpCode in 200..299 -> Decision(
            action = Action.RESTART,
            offset = 0,
            total = if (contentLength > 0) contentLength else manifestSizeBytes,
        )
        // Range not satisfiable: the partial file does not line up with the
        // current resource. Drop it and let the next attempt be a clean start.
        httpCode == 416 -> Decision(action = Action.RETRY_FRESH, offset = 0, total = manifestSizeBytes)
        else -> Decision(
            action = Action.FAIL,
            offset = 0,
            total = -1L,
            failure = "Update download failed (HTTP $httpCode)",
        )
    }
}
