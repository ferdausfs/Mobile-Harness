package com.jarves.mh.ui

/**
 * v1.0.34 (CONCERN B): the single source of truth for which bottom tabs a
 * project workspace shows. The rule the user set: "Never gate Terminal/Preview
 * on the engine; they depend only on the project folder" — so the SAME tabs
 * (Chat, Files, Terminal, Preview) appear for a Z.ai-engine project and an
 * Antigravity project alike. The Changes overlay stays out of the bar (it is a
 * post-apply surface, reachable from the chat cards), exactly as before.
 *
 * Pure so the engine-independence contract is JVM-testable.
 */
object WorkspaceTabPolicy {

    /** Stable ids for the bottom bar (the Compose enum maps 1:1 onto these). */
    val TAB_CHAT = "Chat"
    val TAB_FILES = "Files"
    val TAB_TERMINAL = "Terminal"
    val TAB_PREVIEW = "Preview"

    /**
     * The tabs for a project workspace. [engineTitle] is informational (the
     * chat engine currently driving the conversation); the output MUST NOT
     * depend on it — that is the contract this object documents and the test
     * enforces.
     */
    fun visibleTabs(engineTitle: String?): List<String> = listOf(
        TAB_CHAT,
        TAB_FILES,
        TAB_TERMINAL,
        TAB_PREVIEW,
    )

    /**
     * Whether the Preview tab can actually render something: a preview URL is
     * running (Antigravity event, a dev server the user started in the
     * Terminal, or the v1.0.34 local project server after a Z.ai sync).
     */
    fun previewUsable(previewReady: Boolean, previewUrl: String?): Boolean =
        previewReady && !previewUrl.isNullOrBlank()
}
