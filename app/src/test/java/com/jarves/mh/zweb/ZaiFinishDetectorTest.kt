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
    ) = ZaiFinishDetector.ZaiObservation(
        stopPresent = stop,
        assistantCount = count,
        normalizedText = text,
        loginMarkerMatched = login,
        captchaMarkerMatched = captcha,
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
    fun `timeout fires when generation never settles`() {
        var state = baseline
        // Stop indicator up the whole time.
        val e1 = ZaiFinishDetector.evaluate(0, 0, obs(stop = true, count = 2, text = "…"), state, timing)
        state = e1.state
        val e2 = ZaiFinishDetector.evaluate(300_100, 0, obs(stop = true, count = 2), state, timing)
        assertEquals(ZaiFinishDetector.ZaiDecision.TIMEOUT, e2.decision)
    }

    @Test
    fun `finished wins over timeout at the same instant`() {
        var state = baseline
        state = ZaiFinishDetector.evaluate(0, 0, obs(stop = true, count = 2, text = "done"), state, timing).state
        // Stability window elapses exactly at the timeout.
        val e2 = ZaiFinishDetector.evaluate(4_000, 0, obs(stop = false, count = 2), state, timing.copy(generationTimeoutMs = 4_000))
        assertEquals(ZaiFinishDetector.ZaiDecision.FINISHED, e2.decision)
    }

    @Test
    fun `normalizeForStability strips all whitespace`() {
        assertEquals("helloworldsecond", ZaiFinishDetector.normalizeForStability("hello  world\n\tsecond"))
        assertTrue(ZaiFinishDetector.normalizeForStability("   ").isEmpty())
    }
}
