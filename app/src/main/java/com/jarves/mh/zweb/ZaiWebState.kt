package com.jarves.mh.zweb

/**
 * UI-facing state of the Z.ai Web agent feature, rendered by the Z.ai tab.
 * Kept fully inside the zweb package — the CLI agent state machine is untouched.
 */
data class ZaiWebUiState(
    /** null = not checked yet; true/false = result of the last connection check. */
    val signedIn: Boolean? = null,
    val accountEmail: String? = null,
    val checkingConnection: Boolean = false,
    val runPhase: ZaiRunPhase = ZaiRunPhase.IDLE,
    /** Human-readable progress/error line for the current phase. */
    val runMessage: String? = null,
    val runStartedAtMillis: Long? = null,
    val lastReply: String? = null,
    val lastFiles: List<ZaiFileExtractor.ZaiExtractedFile> = emptyList(),
    val lastFilesDir: String? = null,
    val lastFinishedAtMillis: Long? = null,
    val testRunning: Boolean = false,
    /** Opens the visible sign-in WebView overlay when true. */
    val signInSheetOpen: Boolean = false,
    val configVersion: Int = 0,
) {
    val busy: Boolean
        get() = checkingConnection || testRunning ||
            runPhase == ZaiRunPhase.OPENING_PAGE ||
            runPhase == ZaiRunPhase.SENDING ||
            runPhase == ZaiRunPhase.GENERATING ||
            runPhase == ZaiRunPhase.EXTRACTING
}

enum class ZaiRunPhase {
    IDLE,
    OPENING_PAGE,
    SENDING,
    GENERATING,
    EXTRACTING,
    DONE,
    NEEDS_LOGIN,
    CAPTCHA,
    TIMEOUT,
    ERROR,
    CANCELED,
}
