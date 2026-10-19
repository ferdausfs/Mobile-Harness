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
    /**
     * Selector groups + auth probe path for the sheet's auto-login automation,
     * serialized by the engine from the loaded config. Null = automation
     * unavailable (sheet degrades to hints only).
     */
    val sheetAutomationJson: String? = null,
    val configVersion: Int = 0,
    // ── Diagnostics (v1.0.21) — all values are secret-free ──
    /** Label of the last checkConnection outcome ([ZaiAuthProbeClassifier.ZaiCheckOutcome]). */
    val lastCheckOutcome: String? = null,
    /** One-line secret-free probe summary, e.g. "role=user email=yes outcome=signed-in-bearer localToken=yes". */
    val lastProbeSummary: String? = null,
    val lastCheckAtMillis: Long? = null,
    /** The URL the hidden engine's page last reported through the state probe. */
    val engineLastUrl: String? = null,
    /** User agent of the hidden engine WebView (captured at creation). */
    val engineUserAgent: String? = null,
    /** Last URL the visible sign-in sheet WebView reported. */
    val sheetLastUrl: String? = null,
    /** User agent of the visible sign-in sheet WebView. */
    val sheetUserAgent: String? = null,
    /** One-line secret-free summary of the sheet's own last session probe. */
    val sheetProbeSummary: String? = null,
    /** When the sign-in sheet last reported a signed-in session (agreement check). */
    val sheetVerifiedAtMillis: Long? = null,
    /** Outcome of the last check of the PREVIOUS app run (session-persistence hint). */
    val previousRunOutcome: String? = null,
    val previousRunSignedIn: Boolean? = null,
    // ── Phase 1 live visibility (streamed while a turn generates) ──
    /** Bounded tail of the page's thinking/reasoning block; null = none visible yet. */
    val liveThinking: String? = null,
    /** Bounded tail of the newest assistant message's partial answer text. */
    val liveAnswer: String? = null,
    /** Latest tool/step status line from the page (e.g. "Searching the web…"). */
    val liveStepStatus: String? = null,
    /** User-facing live phase label ("Thinking" / "Answering" / ...). */
    val livePhaseLabel: String? = null,
    /** When the live snapshot was last refreshed (drives the card's staleness). */
    val liveUpdatedAtMillis: Long? = null,
    // ── Phase 1 model picker ──
    /** Current model as the SITE's own label shows it. Null = never read. */
    val currentModel: String? = null,
    /** Cache of the site's own dropdown options (refreshed on demand). */
    val cachedModels: List<ZaiModelCatalog.ModelOption> = emptyList(),
    /** True while a pick/refresh automation is driving the hidden WebView. */
    val modelPickRunning: Boolean = false,
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

/**
 * Secret-free diagnostics published by the visible sign-in sheet: its last
 * page URL, its user agent, and the one-line summary of its last session
 * probe. Feeds the Z.ai tab's Diagnostics report.
 */
data class ZaiSheetDiagnostics(
    val url: String?,
    val userAgent: String?,
    val probeSummary: String?,
)
