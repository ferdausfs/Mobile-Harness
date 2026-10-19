package com.jarves.mh.zweb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ZaiFinishDetectorTest {

    private val timing = ZaiTimingConfig(
        pageLoadTimeoutMs = 25_000,
        pageSettleMs = 1_500,
        sendSettleMs = 2_500,
        pollIntervalMs = 900,
        stableWindowMs = 4_000,
        generationTimeoutMs = 300_000,
    )

    private val baseline = ZaiFinishDetector.ZaiDetectorState(baselineAssistantCount = 1)

    private fun obs(
        stop: Boolean = false,
        count: Int = 1,
        text: String? = null,
        login: Boolean = false,
        captcha: Boolean = false,
        liveProgress: Boolean = false,
    ) = ZaiFinishDetector.ZaiObservation(
        stopPresent = stop,
        assistantCount = count,
        normalizedText = text,
        loginMarkerMatched = login,
        captchaMarkerMatched = captcha,
        liveProgress = liveProgress,
    )

    @Test
    fun `captcha and login short-circuit before anything else`() {
        val (captchaDecision, _) = ZaiFinishDetector.evaluate(
            nowMs = 1_000, startedMs = 0,
            observation = obs(captcha = true, stop = true),
            state = baseline, timing = timing,
        )
        assertEquals(ZaiFinishDetector.ZaiDecision.CAPTCHA, captchaDecision)

        val (loginDecision, _) = ZaiFinishDetector.evaluate(
            nowMs = 1_000, startedMs = 0,
            observation = obs(login = true),
            state = baseline, timing = timing,
        )
        assertEquals(ZaiFinishDetector.ZaiDecision.NEEDS_LOGIN, loginDecision)
    }

    @Test
    fun `stop indicator present means wait even if text looks stable`() {
        var state = baseline
        // Text arrives while stop is up.
        val e1 = ZaiFinishDetector.evaluate(0, 0, obs(stop = true, count = 2, text = "hello"), state, timing)
        state = e1.state
        // 10 seconds of the same text, but stop is STILL present.
        val e2 = ZaiFinishDetector.evaluate(10_000, 0, obs(stop = true, count = 2), state, timing)
        assertEquals(ZaiFinishDetector.ZaiDecision.WAIT, e2.decision)
    }

    @Test
    fun `finish requires new message plus stability plus no stop`() {
        var state = baseline
        // t=0: stop up, reply appears.
        state = ZaiFinishDetector.evaluate(0, 0, obs(stop = true, count = 2, text = "hello"), state, timing).state
        // t=1000: stop gone, text unchanged → stability window started at first identical sample.
        val e2 = ZaiFinishDetector.evaluate(1_000, 0, obs(stop = false, count = 2), state, timing)
        assertEquals(ZaiFinishDetector.ZaiDecision.WAIT, e2.decision)
        state = e2.state
        // t=5100: stable for ≥4s → FINISHED.
        val e3 = ZaiFinishDetector.evaluate(5_100, 0, obs(stop = false, count = 2), state, timing)
        assertEquals(ZaiFinishDetector.ZaiDecision.FINISHED, e3.decision)
    }

    @Test
    fun `text change resets the stability window`() {
        var state = baseline
        state = ZaiFinishDetector.evaluate(0, 0, obs(stop = true, count = 2, text = "hello"), state, timing).state
        val e2 = ZaiFinishDetector.evaluate(1_000, 0, obs(stop = false, count = 2), state, timing)
        state = e2.state
        // Text grew again right before the window would elapse.
        val e3 = ZaiFinishDetector.evaluate(4_500, 0, obs(stop = false, count = 2, text = "hello world"), state, timing)
        assertEquals(ZaiFinishDetector.ZaiDecision.WAIT, e3.decision)
        state = e3.state
        // 3.9s later: not yet stable.
        val e4 = ZaiFinishDetector.evaluate(8_400, 0, obs(stop = false, count = 2), state, timing)
        assertEquals(ZaiFinishDetector.ZaiDecision.WAIT, e4.decision)
        // 4.1s after the change: finished.
        val e5 = ZaiFinishDetector.evaluate(8_700, 0, obs(stop = false, count = 2), state, timing)
        assertEquals(ZaiFinishDetector.ZaiDecision.FINISHED, e5.decision)
    }

    @Test
    fun `no new message and no stop ever seen never finishes`() {
        var state = baseline
        // Page idle: stop absent, count unchanged, no text ever.
        val e1 = ZaiFinishDetector.evaluate(60_000, 0, obs(stop = false, count = 1, text = ""), state, timing)
        assertEquals(ZaiFinishDetector.ZaiDecision.WAIT, e1.decision)
    }

    @Test
    fun `stop appears then vanishes finishes even if the count baseline raced`() {
        // If the page replaced the assistant node instead of appending one, the
        // count never passes the baseline — the stop-indicator lifecycle is the
        // fallback signal.
        var state = ZaiFinishDetector.ZaiDetectorState(baselineAssistantCount = 2)
        state = ZaiFinishDetector.evaluate(0, 0, obs(stop = true, count = 2, text = "fast reply"), state, timing).state
        val e2 = ZaiFinishDetector.evaluate(1_000, 0, obs(stop = false, count = 2), state, timing)
        assertEquals(ZaiFinishDetector.ZaiDecision.WAIT, e2.decision)
        val e3 = ZaiFinishDetector.evaluate(5_200, 0, obs(stop = false, count = 2), state = e2.state, timing = timing)
        assertEquals(ZaiFinishDetector.ZaiDecision.FINISHED, e3.decision)
    }

    @Test
    fun `timeout fires when generation never settles (overall cap)`() {
        var state = baseline
        // Stop indicator up the whole time → constant progress → the overall
        // cap is the only thing that can end the wait (v1.0.33 semantics).
        val e1 = ZaiFinishDetector.evaluate(0, 0, obs(stop = true, count = 2, text = "…"), state, timing)
        state = e1.state
        val e2 = ZaiFinishDetector.evaluate(299_000, 0, obs(stop = true, count = 2), state, timing.copy(maxOverallMs = 300_000))
        assertEquals(ZaiFinishDetector.ZaiDecision.WAIT, e2.decision)
        val e3 = ZaiFinishDetector.evaluate(300_100, 0, obs(stop = true, count = 2), state, timing.copy(maxOverallMs = 300_000))
        assertEquals(ZaiFinishDetector.ZaiDecision.TIMEOUT, e3.decision)
    }

    @Test
    fun `v1_0_33 timeout needs NO progress for the no-progress budget`() {
        var state = baseline
        // One text sample, stop never up, count never grows: the page went
        // completely quiet right after the send.
        state = ZaiFinishDetector.evaluate(0, 0, obs(stop = false, count = 1, text = "partial"), state, timing).state
        // 299s of total silence: still WAIT.
        val e2 = ZaiFinishDetector.evaluate(299_000, 0, obs(stop = false, count = 1), state, timing)
        assertEquals(ZaiFinishDetector.ZaiDecision.WAIT, e2.decision)
        // 301s of total silence (noProgressTimeoutMs = 300s): TIMEOUT.
        val e3 = ZaiFinishDetector.evaluate(301_000, 0, obs(stop = false, count = 1), state, timing)
        assertEquals(ZaiFinishDetector.ZaiDecision.TIMEOUT, e3.decision)
    }

    @Test
    fun `v1_0_33 progress keeps a long task alive past the old 300s total`() {
        var state = baseline
        // A tool phase where the stop indicator stays up for 20 minutes: the
        // old hard 300s cap would have killed this (the v1.0.30 bug).
        var t = 0L
        var last = ZaiFinishDetector.evaluate(t, 0, obs(stop = true, count = 2, text = "x"), state, timing)
        state = last.state
        while (t < 20 * 60_000) {
            t += timing.stableWindowMs * 10
            last = ZaiFinishDetector.evaluate(t, 0, obs(stop = true, count = 2), state, timing)
            state = last.state
            assertEquals(ZaiFinishDetector.ZaiDecision.WAIT, last.decision)
        }
    }

    @Test
    fun `v1_0_33 live-status churn counts as progress`() {
        var state = baseline
        state = ZaiFinishDetector.evaluate(0, 0, obs(stop = false, count = 1, text = "partial"), state, timing).state
        // The page is textually quiet but the tool step line keeps changing.
        val e2 = ZaiFinishDetector.evaluate(240_000, 0, obs(stop = false, count = 1, liveProgress = true), state, timing)
        assertEquals(ZaiFinishDetector.ZaiDecision.WAIT, e2.decision)
        // And the timer restarts from that point.
        val e3 = ZaiFinishDetector.evaluate(400_000, 0, obs(stop = false, count = 1), e2.state, timing)
        assertEquals(ZaiFinishDetector.ZaiDecision.WAIT, e3.decision)
        val e4 = ZaiFinishDetector.evaluate(540_100, 0, obs(stop = false, count = 1), e3.state, timing)
        assertEquals(ZaiFinishDetector.ZaiDecision.TIMEOUT, e4.decision)
    }

    @Test
    fun `v1_0_33 count growth counts as progress`() {
        var state = baseline
        state = ZaiFinishDetector.evaluate(0, 0, obs(stop = false, count = 1, text = ""), state, timing).state
        // A new assistant message element appeared late (agent round starting)
        // while its text is still arriving: the growth keeps the wait alive
        // (the text just restarted, so the finish window cannot elapse yet).
        val e2 = ZaiFinishDetector.evaluate(200_000, 0, obs(stop = false, count = 2, text = "…"), state, timing)
        assertEquals(ZaiFinishDetector.ZaiDecision.WAIT, e2.decision)
        // Total silence after that (count back to baseline, text unchanged):
        // the timer restarted at the growth, so silence must be measured from
        // 200_000, not from 0.
        val e3 = ZaiFinishDetector.evaluate(400_000, 0, obs(stop = false, count = 1), e2.state, timing)
        assertEquals(ZaiFinishDetector.ZaiDecision.WAIT, e3.decision)
        val e4 = ZaiFinishDetector.evaluate(500_100, 0, obs(stop = false, count = 1), e3.state, timing)
        assertEquals(ZaiFinishDetector.ZaiDecision.TIMEOUT, e4.decision)
    }

    @Test
    fun `finished wins over timeout at the same instant`() {
        var state = baseline
        state = ZaiFinishDetector.evaluate(0, 0, obs(stop = true, count = 2, text = "done"), state, timing).state
        // Stability window elapses exactly at the overall cap.
        val e2 = ZaiFinishDetector.evaluate(4_000, 0, obs(stop = false, count = 2), state, timing.copy(maxOverallMs = 4_000))
        assertEquals(ZaiFinishDetector.ZaiDecision.FINISHED, e2.decision)
    }

    @Test
    fun `normalizeForStability strips all whitespace`() {
        assertEquals("helloworldsecond", ZaiFinishDetector.normalizeForStability("hello  world\n\tsecond"))
        assertTrue(ZaiFinishDetector.normalizeForStability("   ").isEmpty())
    }
}
