package com.jarves.mh.zweb

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Runs the EXACT loop the app runs on a real WebView against a fake
 * evaluateJavascript bridge. This is the regression net for the v1.0.23 bug:
 * a Promise-returning probe read fine in headless Chromium (which awaits
 * promises) but never returned a readable result through the real
 * evaluateJavascript — the loop tests pin the store-and-poll contract.
 */
class ZaiAuthProbePollTest {

    /** Script-kind log + canned responses, mimicking the WebView's contract. */
    private class FakeBridge(
        /** responder: (script, per-script invocation index -> completion value) */
        private val responder: (String, Int) -> String?,
    ) {
        val scripts = mutableListOf<String>()
        private val counts = HashMap<String, Int>()
        val injectCount: Int get() = counts[INJECT] ?: 0
        val readCount: Int get() = counts[READER] ?: 0

        suspend fun eval(script: String): String? {
            scripts.add(script)
            val idx = (counts[script] ?: 0) + 1
            counts[script] = idx
            return responder(script, idx)
        }

        companion object {
            const val INJECT = "INJECT"
            const val READER = "READER"
        }
    }

    private val readable = "\"{\"\"signedIn\":true}\"" // any raw payload — readability is the caller's predicate
    private val unreadable = "\"{}\"" // what a Promise completion value looks like on a real WebView

    private fun isReadable(raw: String?): Boolean = raw != null && raw != unreadable

    private suspend fun run(
        bridge: FakeBridge,
        config: AuthProbePollPolicy.Config,
        delays: MutableList<Long> = mutableListOf(),
    ): String? = runAuthProbeScript(
        eval = { script -> bridge.eval(script) },
        injectScript = FakeBridge.INJECT,
        readerScript = FakeBridge.READER,
        isReadable = ::isReadable,
        config = config,
        delayFn = { delays.add(it) },
    )

    @Test
    fun `result on the first read wins with exactly one inject`() = runBlocking {
        val bridge = FakeBridge { script, _ -> if (script == FakeBridge.READER) readable else null }
        val result = run(bridge, AuthProbePollPolicy.Config(rounds = 3, readsPerRound = 5, readDelayMs = 10))
        assertEquals(readable, result)
        assertEquals(1, bridge.injectCount)
        assertEquals(1, bridge.readCount)
    }

    @Test
    fun `result on a later read of the first round stops without re-injecting`() = runBlocking {
        // reads 1-2 unreadable, read 3 readable (indexes are per reader script)
        val bridge = FakeBridge { script, idx ->
            if (script != FakeBridge.READER) null else if (idx <= 2) unreadable else readable
        }
        val result = run(bridge, AuthProbePollPolicy.Config(rounds = 3, readsPerRound = 5, readDelayMs = 10))
        assertEquals(readable, result)
        assertEquals(1, bridge.injectCount)
        assertEquals(3, bridge.readCount)
    }

    @Test
    fun `slow fetch lands in the next round via a fresh inject`() = runBlocking {
        // The whole first round reads unreadable; the second round's reads find it.
        val bridge = FakeBridge { script, idx ->
            if (script != FakeBridge.READER) null else if (idx <= 4) unreadable else readable
        }
        val result = run(bridge, AuthProbePollPolicy.Config(rounds = 3, readsPerRound = 3, readDelayMs = 10))
        assertEquals(readable, result)
        assertEquals(2, bridge.injectCount) // round 1 + round 2
    }

    @Test
    fun `exhausted budget returns null after every configured attempt`() = runBlocking {
        val bridge = FakeBridge { script, _ -> if (script == FakeBridge.READER) unreadable else null }
        val config = AuthProbePollPolicy.Config(rounds = 2, readsPerRound = 3, readDelayMs = 10)
        val result = run(bridge, config)
        assertNull(result)
        assertEquals(2, bridge.injectCount) // one per round
        assertEquals(6, bridge.readCount) // readsPerRound per round
    }

    @Test
    fun `delay runs between attempts but not after the final give-up`() = runBlocking {
        val bridge = FakeBridge { script, _ -> if (script == FakeBridge.READER) unreadable else null }
        val config = AuthProbePollPolicy.Config(rounds = 2, readsPerRound = 3, readDelayMs = 42)
        val delays = mutableListOf<Long>()
        assertNull(run(bridge, config, delays))
        // 6 attempts total -> 5 inter-attempt delays (none after the last)
        assertEquals(listOf(42L, 42L, 42L, 42L, 42L), delays)
    }

    @Test
    fun `bridge failures count as unreadable attempts, not crashes`() = runBlocking {
        val bridge = FakeBridge { script, idx ->
            if (script != FakeBridge.READER) null else if (idx <= 2) null else readable
        }
        val result = run(bridge, AuthProbePollPolicy.Config(rounds = 3, readsPerRound = 5, readDelayMs = 10))
        assertEquals(readable, result)
    }

    @Test
    fun `real classifier payload flows through the contract end to end`() = runBlocking {
        // The stored payload is the same shape v1.0.21 defined; assert the raw
        // WebView-quoted form survives the loop and classifies as signed-in.
        val payload = org.json.JSONObject(
            "{\"signedIn\":true,\"hasLocalToken\":true," +
                "\"bearer\":{\"status\":200,\"shape\":\"user\",\"role\":\"user\",\"emailPresent\":true,\"guest\":false,\"error\":\"\"}," +
                "\"cookie\":{\"status\":200,\"shape\":\"user\",\"role\":\"user\",\"emailPresent\":true,\"guest\":false,\"error\":\"\"}}",
        ).toString()
        val quoted = "\"" + payload.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        val bridge = FakeBridge { script, _ -> if (script == FakeBridge.READER) quoted else null }
        val result = run(bridge, AuthProbePollPolicy.Config(rounds = 2, readsPerRound = 4, readDelayMs = 5))
        val verdict = ZaiAuthProbeClassifier.classify(ZaiAuthProbeClassifier.parseEvalResult(result))
        assertTrue(verdict.signedIn)
        assertEquals(ZaiAuthProbeClassifier.ZaiCheckOutcome.SIGNED_IN_BEARER, verdict.outcome)
    }

    @Test
    fun `timeout verdict names the budget instead of a generic error`() {
        val verdict = probeTimeoutVerdict(9000L)
        assertEquals(ZaiAuthProbeClassifier.ZaiCheckOutcome.PROBE_ERROR, verdict.outcome)
        assertTrue(!verdict.signedIn)
        assertTrue(verdict.errorText.contains("9000ms"))
        // still secret-free
        assertEquals("", verdict.role)
        assertEquals(false, verdict.emailPresent)
        assertEquals(false, verdict.hasLocalToken)
    }
}
