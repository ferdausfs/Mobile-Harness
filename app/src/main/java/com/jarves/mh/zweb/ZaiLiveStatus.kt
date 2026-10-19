package com.jarves.mh.zweb

import org.json.JSONObject

/**
 * Pure model of what the hidden WebView is doing RIGHT NOW during a
 * generation turn (Phase 1 live visibility). The engine polls the page with
 * [ZaiProbeScripts.buildLiveStatusScript] about once a second; this object
 * turns the raw probe JSON into bounded, UI-ready values and derives the
 * user-facing phase label.
 *
 * Caps are deliberate: the DOM of a long thinking chain can be hundreds of
 * KB, and the chat card only needs a bounded window of it. Every value is
 * also a candidate for on-screen display, so control characters are stripped.
 *
 * Secrets: the probe reads ONLY the page's visible text (thinking block,
 * partial answer, tool status lines). It never touches cookies, localStorage
 * or fetch payloads, so nothing secret can flow through here.
 */
object ZaiLiveStatus {

    /** Upper bounds (characters) — keeps the Compose card and the state holder small. */
    const val MAX_THINKING_CHARS = 4_000
    const val MAX_ANSWER_CHARS = 8_000
    const val MAX_STEP_CHARS = 500

    private val CONTROL_CHARS = Regex("[\\p{Cntrl}\\uFFFD]")

    /** What the user sees as the live phase line in the chat card. */
    enum class Phase(val label: String) {
        IDLE(""),
        THINKING("Thinking"),
        TOOLING("Working with tools"),
        ANSWERING("Answering"),
        UNKNOWN("Generating"),
    }

    data class Live(
        /** Collapsible "Thinking" body; null = no thinking block on the page (yet). */
        val thinking: String?,
        /** Thinking section is still streaming (details open / no answer text yet). */
        val thinkingActive: Boolean,
        /** The partial answer text of the newest assistant message (may be empty). */
        val answer: String?,
        /** Latest tool/step status line(s), e.g. "Searching the web…". */
        val stepStatus: String?,
        /** Derived phase for the card's headline. */
        val phase: Phase,
    )

    /**
     * Parses the live-status probe JSON. Returns null when the probe produced
     * nothing readable (script error, page navigating) — the UI keeps showing
     * the previous snapshot in that case.
     */
    fun parse(json: String?): Live? {
        if (json.isNullOrBlank()) return null
        val obj = runCatching { JSONObject(cleanEvalResult(json)) }.getOrNull() ?: return null
        val thinkingRaw = obj.optString("thinkingText", "").takeIf(String::isNotBlank)
        val answerRaw = obj.optString("answerText", "").takeIf(String::isNotBlank)
        val stepRaw = obj.optString("stepText", "").takeIf(String::isNotBlank)
        val thinkingActive = obj.optBoolean("thinkingActive", false)
        val thinking = thinkingRaw?.let { bound(it, MAX_THINKING_CHARS) }
        val answer = answerRaw?.let { bound(it, MAX_ANSWER_CHARS) }
        val step = stepRaw?.let { bound(it, MAX_STEP_CHARS) }
        return Live(
            thinking = thinking,
            thinkingActive = thinkingActive,
            answer = answer,
            stepStatus = step,
            phase = derivePhase(
                stopPresent = obj.optBoolean("stopPresent", false),
                thinkingPresent = thinking != null,
                thinkingActive = thinkingActive,
                answerPresent = answer != null,
                stepPresent = step != null,
            ),
        )
    }

    /**
     * Phase rule (kept pure so it is testable):
     *  - tool/step status line visible → TOOLING (the model is calling tools)
     *  - thinking block still streaming → THINKING
     *  - thinking text exists but done and no answer yet → THINKING (summary view)
     *  - answer text exists → ANSWERING
     *  - page still generating but nothing readable → UNKNOWN
     *  - no stop indicator → IDLE (nothing is running)
     */
    fun derivePhase(
        stopPresent: Boolean,
        thinkingPresent: Boolean,
        thinkingActive: Boolean,
        answerPresent: Boolean,
        stepPresent: Boolean,
    ): Phase = when {
        !stopPresent -> Phase.IDLE
        stepPresent -> Phase.TOOLING
        thinkingActive || (thinkingPresent && !answerPresent) -> Phase.THINKING
        answerPresent -> Phase.ANSWERING
        else -> Phase.UNKNOWN
    }

    /** Trims, strips control characters and hard-caps a streamed text window. */
    fun bound(text: String, maxChars: Int): String {
        // Control characters (ANSI escapes, NULs, BOM junk) are dropped outright
        // rather than space-replaced, so words they split stay whole.
        val cleaned = CONTROL_CHARS.replace(text, "").replace(Regex("\\s+"), " ").trim()
        if (cleaned.length <= maxChars) return cleaned
        // Keep the TAIL of thinking/answer text: the newest words matter most
        // while watching a stream.
        return "… " + cleaned.takeLast(maxChars - 2)
    }

    private fun cleanEvalResult(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.length >= 2 && trimmed.first() == '"' && trimmed.last() == '"') {
            return runCatching {
                org.json.JSONArray("[$trimmed]").getString(0)
            }.getOrDefault(trimmed)
        }
        return trimmed
    }
}
