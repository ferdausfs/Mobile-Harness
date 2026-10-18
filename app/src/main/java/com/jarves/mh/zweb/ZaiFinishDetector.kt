package com.jarves.mh.zweb

/**
 * Pure finish-detection state machine for the Z.ai Web agent.
 *
 * A reply is considered finished only when ALL of these hold (never a single
 * signal, per the project's hard rules):
 *
 *  1. no login/captcha blocker is on the page;
 *  2. the page's stop/generating indicator is absent;
 *  3. at least one NEW assistant message appeared after the send (count grew
 *     beyond the pre-send baseline) — or the stop indicator was seen once and
 *     then disappeared;
 *  4. the assistant text stayed character-stable for [ZaiTimingConfig.stableWindowMs]
 *     (normalised: whitespace removed so blinking cursors cannot keep resetting it);
 *  5. none of the above exceeded [ZaiTimingConfig.generationTimeoutMs].
 */
object ZaiFinishDetector {

    enum class ZaiDecision { WAIT, FINISHED, NEEDS_LOGIN, CAPTCHA, TIMEOUT }

    /** One page snapshot handed to [evaluate]; produced by the engine's probe script. */
    data class ZaiObservation(
        val stopPresent: Boolean,
        val assistantCount: Int,
        /** Whitespace-normalised assistant text; null when unchanged since the previous probe. */
        val normalizedText: String?,
        val loginMarkerMatched: Boolean,
        val captchaMarkerMatched: Boolean,
    )

    /** Mutable detector state between polls. Engine keeps exactly one per run. */
    data class ZaiDetectorState(
        val baselineAssistantCount: Int = 0,
        val stopSeen: Boolean = false,
        val stableSinceMs: Long? = null,
        val lastNormalizedText: String? = null,
    )

    data class ZaiEvaluation(
        val decision: ZaiDecision,
        val state: ZaiDetectorState,
    )

    fun evaluate(
        nowMs: Long,
        startedMs: Long,
        observation: ZaiObservation,
        state: ZaiDetectorState,
        timing: ZaiTimingConfig,
    ): ZaiEvaluation {
        // Blockers first: a captcha or login page means we must stop cleanly
        // regardless of what the generator is doing.
        if (observation.captchaMarkerMatched) return ZaiEvaluation(ZaiDecision.CAPTCHA, state)
        if (observation.loginMarkerMatched) return ZaiEvaluation(ZaiDecision.NEEDS_LOGIN, state)

        val stopSeen = state.stopSeen || observation.stopPresent
        val newText = observation.normalizedText
        var stableSince = state.stableSinceMs
        var lastText = state.lastNormalizedText

        if (newText != null && newText != lastText) {
            // Text changed (or first sample): restart the stability window.
            stableSince = nowMs
            lastText = newText
        } else if (newText != null && stableSince == null) {
            // Same text as before but no window started (e.g. state reset) — start it.
            stableSince = nowMs
        }

        val nextState = state.copy(
            stopSeen = stopSeen,
            stableSinceMs = stableSince,
            lastNormalizedText = lastText,
        )

        // Finish requires: no stop indicator + a new assistant message (or a stop
        // indicator that appeared and then vanished) + text stable for the window.
        val newMessage = observation.assistantCount > state.baselineAssistantCount
        val stopAppearedAndVanished = stopSeen && !observation.stopPresent
        if (!observation.stopPresent && (newMessage || stopAppearedAndVanished)) {
            val stableEnough = stableSince != null && (nowMs - stableSince) >= timing.stableWindowMs
            if (stableEnough) return ZaiEvaluation(ZaiDecision.FINISHED, nextState)
        }

        if (nowMs - startedMs >= timing.generationTimeoutMs) {
            return ZaiEvaluation(ZaiDecision.TIMEOUT, nextState)
        }

        return ZaiEvaluation(ZaiDecision.WAIT, nextState)
    }

    /** Whitespace-normalisation used for stability comparisons (mirrors the probe JS). */
    fun normalizeForStability(raw: String): String = raw.replace(Regex("\\s+"), "")
}
