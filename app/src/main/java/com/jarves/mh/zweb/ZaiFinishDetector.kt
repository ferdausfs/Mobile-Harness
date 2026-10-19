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
 *  5. none of the above exceeded the timing budgets (see below).
 *
 * v1.0.33 TIMEOUT SEMANTICS (real-phone evidence: v1.0.30 showed "Z.ai timed
 * out (300s)" while the site was still working — the old detector applied a
 * hard TOTAL cap): the page is judged on PROGRESS, not on elapsed time.
 *  - Any sign of life — the stop indicator up, the live status changing
 *    ([ZaiObservation.liveProgress]), the assistant text or count changing —
 *    keeps the turn alive.
 *  - Only [ZaiTimingConfig.noProgressTimeoutMs] of NO change at all (default
 *    300s) ends the wait.
 *  - [ZaiTimingConfig.maxOverallMs] (default 30 min) stays the absolute cap so
 *    a wedged page can never hang the app forever.
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
        /**
         * v1.0.33: the live-status probe saw movement this tick (thinking /
         * partial answer / tool step text changed). Counts as progress so long
         * silent tool phases on the site never read as a stall.
         */
        val liveProgress: Boolean = false,
    )

    /** Mutable detector state between polls. Engine keeps exactly one per run. */
    data class ZaiDetectorState(
        val baselineAssistantCount: Int = 0,
        val stopSeen: Boolean = false,
        val stableSinceMs: Long? = null,
        val lastNormalizedText: String? = null,
        /** v1.0.33: last [evaluate] timestamp that showed ANY page progress. */
        val lastProgressAtMs: Long? = null,
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

        // v1.0.33 progress: ANY sign of life resets the no-progress timer.
        val progressed = observation.stopPresent ||
            observation.liveProgress ||
            observation.assistantCount != state.baselineAssistantCount ||
            (newText != null && newText != state.lastNormalizedText)
        val lastProgressAt = if (progressed) nowMs else state.lastProgressAtMs ?: nowMs

        val nextState = state.copy(
            stopSeen = stopSeen,
            stableSinceMs = stableSince,
            lastNormalizedText = lastText,
            lastProgressAtMs = lastProgressAt,
        )

        // Finish requires: no stop indicator + a new assistant message (or a stop
        // indicator that appeared and then vanished) + text stable for the window.
        val newMessage = observation.assistantCount > state.baselineAssistantCount
        val stopAppearedAndVanished = stopSeen && !observation.stopPresent
        if (!observation.stopPresent && (newMessage || stopAppearedAndVanished)) {
            val stableEnough = stableSince != null && (nowMs - stableSince) >= timing.stableWindowMs
            if (stableEnough) return ZaiEvaluation(ZaiDecision.FINISHED, nextState)
        }

        // v1.0.33: timeout = no page progress for [noProgressTimeoutMs], or the
        // absolute [maxOverallMs] cap. [startedMs] is kept for compatibility;
        // the overall cap is measured on [nowMs] which the engine feeds as the
        // elapsed poll time since the send.
        val stalled = nowMs - lastProgressAt >= timing.noProgressTimeoutMs
        val overCap = nowMs - startedMs >= timing.maxOverallMs
        if (stalled || overCap) {
            return ZaiEvaluation(ZaiDecision.TIMEOUT, nextState)
        }

        return ZaiEvaluation(ZaiDecision.WAIT, nextState)
    }

    /** Whitespace-normalisation used for stability comparisons (mirrors the probe JS). */
    fun normalizeForStability(raw: String): String = raw.replace(Regex("\\s+"), "")
}
