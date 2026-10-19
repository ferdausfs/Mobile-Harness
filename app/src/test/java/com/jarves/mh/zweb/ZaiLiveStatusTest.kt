package com.jarves.mh.zweb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ZaiLiveStatusTest {

    // The completion value WebView wraps in one level of JSON quoting — the
    // parser must accept both the quoted and the bare form.
    private val inner =
        """{"thinkingText":"Summarize the repo\nReading files","thinkingActive":true,""" +
            """"answerText":"Here is my partial","stepText":"Searching the web",""" +
            """"stopPresent":true,"url":"https://chat.z.ai/c/x"}"""
    private val quoted = "\"" + inner.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    @Test
    fun `parses quoted eval result with all fields`() {
        val live = ZaiLiveStatus.parse(quoted)
        assertNotNull(live)
        live!!
        assertTrue(live.thinking!!.startsWith("Summarize the repo"))
        assertTrue(live.thinkingActive)
        assertEquals("Here is my partial", live.answer)
        assertEquals("Searching the web", live.stepStatus)
        assertEquals(ZaiLiveStatus.Phase.TOOLING, live.phase)
    }

    @Test
    fun `parses bare json object form too`() {
        val bare = """{"thinkingText":"t","thinkingActive":false,"answerText":"a","stepText":"","stopPresent":true}"""
        val live = ZaiLiveStatus.parse(bare)
        assertNotNull(live)
        assertEquals("t", live!!.thinking)
        assertEquals("a", live.answer)
        assertEquals(ZaiLiveStatus.Phase.ANSWERING, live.phase)
    }

    @Test
    fun `null blank or garbage input yields null`() {
        assertNull(ZaiLiveStatus.parse(null))
        assertNull(ZaiLiveStatus.parse(""))
        assertNull(ZaiLiveStatus.parse("   "))
        assertNull(ZaiLiveStatus.parse("undefined"))
        assertNull(ZaiLiveStatus.parse("not json at all"))
    }

    @Test
    fun `empty probe keeps every text field null`() {
        val live = ZaiLiveStatus.parse("""{"thinkingText":"","thinkingActive":false,"answerText":"","stepText":"","stopPresent":true}""")
        assertNotNull(live)
        assertNull(live!!.thinking)
        assertNull(live.answer)
        assertNull(live.stepStatus)
        assertEquals(ZaiLiveStatus.Phase.UNKNOWN, live.phase)
    }

    @Test
    fun `phase derivation follows the documented rule`() {
        // nothing running
        assertEquals(ZaiLiveStatus.Phase.IDLE, ZaiLiveStatus.derivePhase(stopPresent = false, thinkingPresent = true, thinkingActive = true, answerPresent = false, stepPresent = true))
        // tool status wins while visible
        assertEquals(ZaiLiveStatus.Phase.TOOLING, ZaiLiveStatus.derivePhase(stopPresent = true, thinkingPresent = true, thinkingActive = true, answerPresent = false, stepPresent = true))
        // thinking still streaming
        assertEquals(ZaiLiveStatus.Phase.THINKING, ZaiLiveStatus.derivePhase(stopPresent = true, thinkingPresent = true, thinkingActive = true, answerPresent = false, stepPresent = false))
        // thinking finished, answer not started
        assertEquals(ZaiLiveStatus.Phase.THINKING, ZaiLiveStatus.derivePhase(stopPresent = true, thinkingPresent = true, thinkingActive = false, answerPresent = false, stepPresent = false))
        // answer text streaming
        assertEquals(ZaiLiveStatus.Phase.ANSWERING, ZaiLiveStatus.derivePhase(stopPresent = true, thinkingPresent = true, thinkingActive = false, answerPresent = true, stepPresent = false))
        // generating but nothing readable yet
        assertEquals(ZaiLiveStatus.Phase.UNKNOWN, ZaiLiveStatus.derivePhase(stopPresent = true, thinkingPresent = false, thinkingActive = false, answerPresent = false, stepPresent = false))
    }

    @Test
    fun `long thinking text is tail-bounded with an ellipsis marker`() {
        val long = "x".repeat(ZaiLiveStatus.MAX_THINKING_CHARS + 500)
        val bounded = ZaiLiveStatus.bound(long, ZaiLiveStatus.MAX_THINKING_CHARS)
        assertEquals(ZaiLiveStatus.MAX_THINKING_CHARS, bounded.length)
        assertTrue("tail must keep the newest words", bounded.startsWith("… "))
    }

    @Test
    fun `short text passes through unchanged except whitespace folding`() {
        assertEquals("hello world", ZaiLiveStatus.bound("hello   world\n", 100))
    }

    @Test
    fun `control characters are stripped from streamed text`() {
        val dirty = "an\u0000sw\u0007er\u001B[31m with controls"
        val cleaned = ZaiLiveStatus.bound(dirty, 100)
        assertFalse(cleaned.contains('\u0000'))
        assertFalse(cleaned.contains('\u0007'))
        assertFalse(cleaned.contains('\u001B'))
        assertTrue(cleaned.contains("answer"))
    }

    @Test
    fun `phase labels exist for every enum value`() {
        ZaiLiveStatus.Phase.entries.forEach { phase ->
            assertNotNull(phase.label)
        }
        assertEquals("Thinking", ZaiLiveStatus.Phase.THINKING.label)
        assertEquals("Answering", ZaiLiveStatus.Phase.ANSWERING.label)
        assertEquals("", ZaiLiveStatus.Phase.IDLE.label)
    }
}
