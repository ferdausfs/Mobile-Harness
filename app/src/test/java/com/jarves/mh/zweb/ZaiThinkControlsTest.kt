package com.jarves.mh.zweb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ZaiThinkControlsTest {

    @Test
    fun `parses the bundle-shaped menu with toggle and efforts`() {
        val json = """
            {"found":true,"items":[
              {"text":"Deep Think","checked":"true"},
              {"text":"Reasoning Effort","checked":"true"},
              {"text":"Low","checked":"false"},
              {"text":"High","checked":"false"},
              {"text":"Max","checked":"true"}
            ]}
        """.trimIndent()
        val controls = ZaiThinkControls.parse(json)!!
        // The first checked row matching the toggle vocabulary wins.
        assertEquals(true, controls.autoThink)
        assertEquals(listOf("low", "high", "max"), controls.effortOptions)
        assertEquals("max", controls.effort)
        assertEquals("Thinking on · max effort", ZaiThinkControls.caption(controls))
        assertTrue(controls.hasAnything)
    }

    @Test
    fun `parses quoted eval result form`() {
        val inner = """{"found":true,"items":[{"text":"Deep Think","checked":"false"}]}"""
        val quoted = "\"" + inner.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        val controls = ZaiThinkControls.parse(quoted)!!
        assertEquals(false, controls.autoThink)
        assertTrue(controls.effortOptions.isEmpty())
        assertEquals("Thinking off · direct answers", ZaiThinkControls.caption(controls))
    }

    @Test
    fun `not-found and garbage yield null`() {
        assertNull(ZaiThinkControls.parse(null))
        assertNull(ZaiThinkControls.parse(""))
        assertNull(ZaiThinkControls.parse("garbage"))
        assertNull(ZaiThinkControls.parse("""{"found":false,"items":[]}"""))
        assertNull(ZaiThinkControls.parse("""{"found":true,"items":[]}"""))
    }

    @Test
    fun `toggle row matching covers the bundle vocabulary`() {
        val deepThink = ZaiThinkControls.fromItems(
            listOf(ZaiThinkControls.MenuItem("Deep Think", "false")),
        )
        assertEquals(false, deepThink.autoThink)
        val reasoning = ZaiThinkControls.fromItems(
            listOf(ZaiThinkControls.MenuItem("Reasoning Effort", "true")),
        )
        assertEquals(true, reasoning.autoThink)
        // No checked toggle row → autoThink stays null (site may not expose it).
        val none = ZaiThinkControls.fromItems(listOf(ZaiThinkControls.MenuItem("Max", "")))
        assertNull(none.autoThink)
    }

    @Test
    fun `effort rows tolerate glyphs and case`() {
        val controls = ZaiThinkControls.fromItems(
            listOf(
                ZaiThinkControls.MenuItem("✓ Low", ""),
                ZaiThinkControls.MenuItem("MAX ", "true"),
                ZaiThinkControls.MenuItem("Use web search", ""),
            ),
        )
        assertEquals(listOf("low", "max"), controls.effortOptions)
        assertEquals("max", controls.effort)
    }

    @Test
    fun `checked state parser knows the site's variants`() {
        assertEquals(true, ZaiThinkControls.parseChecked("true"))
        assertEquals(true, ZaiThinkControls.parseChecked("checked"))
        assertEquals(false, ZaiThinkControls.parseChecked("false"))
        assertEquals(false, ZaiThinkControls.parseChecked("unchecked"))
        assertNull(ZaiThinkControls.parseChecked(""))
        assertNull(ZaiThinkControls.parseChecked("closed"))
    }

    @Test
    fun `caption covers every state combination`() {
        assertEquals("Thinking on · high effort", ZaiThinkControls.caption(ZaiThinkControls.Controls(true, "high", listOf("high"))))
        assertEquals("Thinking on", ZaiThinkControls.caption(ZaiThinkControls.Controls(true, null, emptyList())))
        assertEquals("Thinking off · direct answers", ZaiThinkControls.caption(ZaiThinkControls.Controls(false, null, emptyList())))
        assertEquals("Site defaults", ZaiThinkControls.caption(ZaiThinkControls.Controls(null, null, emptyList())))
    }

    @Test
    fun `verify demands a matching re-read`() {
        val after = ZaiThinkControls.Controls(autoThink = true, effort = "max", effortOptions = listOf("low", "high", "max"))
        assertTrue(ZaiThinkControls.verify(after, autoThink = true, effort = null))
        assertTrue(ZaiThinkControls.verify(after, autoThink = null, effort = "max"))
        assertTrue(ZaiThinkControls.verify(after, autoThink = true, effort = "max"))
        assertFalse(ZaiThinkControls.verify(after, autoThink = false, effort = null))
        assertFalse(ZaiThinkControls.verify(after, autoThink = null, effort = "low"))
        // Nothing requested → nothing to verify.
        assertFalse(ZaiThinkControls.verify(after, autoThink = null, effort = null))
        // The site not exposing a state at all is not a mismatch.
        val unknown = ZaiThinkControls.Controls(null, null, emptyList())
        assertTrue(ZaiThinkControls.verify(unknown, autoThink = true, effort = null))
    }

    @Test
    fun `item list is capped`() {
        val many = (1..40).map { ZaiThinkControls.MenuItem("Row $it", "") }
        val controls = ZaiThinkControls.fromItems(many)
        assertTrue(controls.effortOptions.isEmpty())
    }
}
