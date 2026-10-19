package com.jarves.mh.zweb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ZaiModelCatalogTest {

    // Matches the live probe (2026-10-19): GLM-5.3-Flash (current), GLM-5.3, GLM-5.2.
    private val optionsJson = """
        [
          {"name":"GLM-5.3-Flash","description":"Lightweight flagship model, premium quality, instant respons","by":".popoverContainer div[class*='line-clamp-1'][class*='select-none']"},
          {"name":"GLM-5.3","description":"Flagship model, excels at coding and long-horizon tasks","by":".popoverContainer"},
          {"name":"GLM-5.2","description":"Previous flagship model","by":".popoverContainer"}
        ]
    """.trimIndent()

    @Test
    fun `parses the live-probed option list`() {
        val models = ZaiModelCatalog.parse(optionsJson)
        assertEquals(listOf("GLM-5.3-Flash", "GLM-5.3", "GLM-5.2"), models.map { it.name })
        assertTrue(models[0].description.startsWith("Lightweight flagship"))
    }

    @Test
    fun `parses quoted eval result form`() {
        // JSON.stringify-style quoting also escapes newlines — mirror that here.
        val quoted = "\"" + optionsJson.trim()
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n") + "\""
        assertEquals(3, ZaiModelCatalog.parse(quoted).size)
    }

    @Test
    fun `deduplicates case-insensitively keeping first`() {
        val dup = """[{"name":"GLM-5.3"},{"name":"glm-5.3"},{"name":"GLM-5.2"}]"""
        val models = ZaiModelCatalog.parse(dup)
        assertEquals(listOf("GLM-5.3", "GLM-5.2"), models.map { it.name })
    }

    @Test
    fun `blank newline and garbage names are dropped`() {
        val dirty = """[{"name":""},{"name":"  "},{"name":"Bad\nName"},{"name":"GLM-5.3"},{"name":null}]"""
        val models = ZaiModelCatalog.parse(dirty)
        assertEquals(listOf("GLM-5.3"), models.map { it.name })
    }

    @Test
    fun `unreadable input yields empty list`() {
        assertEquals(emptyList<ZaiModelCatalog.ModelOption>(), ZaiModelCatalog.parse(null))
        assertEquals(emptyList<ZaiModelCatalog.ModelOption>(), ZaiModelCatalog.parse(""))
        assertEquals(emptyList<ZaiModelCatalog.ModelOption>(), ZaiModelCatalog.parse("undefined"))
        assertEquals(emptyList<ZaiModelCatalog.ModelOption>(), ZaiModelCatalog.parse("{broken"))
    }

    @Test
    fun `list is capped at MAX_MODELS`() {
        val many = (1..(ZaiModelCatalog.MAX_MODELS + 10)).joinToString(",") { """{"name":"Model-$it"}""" }
        assertEquals(ZaiModelCatalog.MAX_MODELS, ZaiModelCatalog.parse("[$many]").size)
    }

    @Test
    fun `label parse reads ok payload and rejects the rest`() {
        assertEquals("GLM-5.3-Flash", ZaiModelCatalog.parseLabel("""{"ok":true,"model":"GLM-5.3-Flash"}"""))
        assertEquals("GLM-5.3-Flash", ZaiModelCatalog.parseLabel("\"{\\\"ok\\\":true,\\\"model\\\":\\\"GLM-5.3-Flash\\\"}\""))
        assertNull(ZaiModelCatalog.parseLabel("""{"ok":false}"""))
        assertNull(ZaiModelCatalog.parseLabel("""{"ok":true,"model":""}"""))
        assertNull(ZaiModelCatalog.parseLabel(null))
        assertNull(ZaiModelCatalog.parseLabel("garbage"))
    }

    @Test
    fun `label matching is exact and case-insensitive`() {
        assertTrue(ZaiModelCatalog.labelMatches("GLM-5.3-Flash", "GLM-5.3-Flash"))
        assertTrue(ZaiModelCatalog.labelMatches("glm-5.3-flash", "GLM-5.3-FLASH"))
        assertTrue(ZaiModelCatalog.labelMatches("  GLM-5.3  ", "GLM-5.3"))
        assertFalse(ZaiModelCatalog.labelMatches("GLM-5.3", "GLM-5.3-Flash"))
        assertFalse(ZaiModelCatalog.labelMatches(null, "GLM-5.3"))
        assertFalse(ZaiModelCatalog.labelMatches("", "GLM-5.3"))
    }

    @Test
    fun `resolve prefers exact then unique prefix then contains`() {
        val models = ZaiModelCatalog.parse(optionsJson)
        assertEquals("GLM-5.3", ZaiModelCatalog.resolve(models, "GLM-5.3")!!.name)
        assertEquals("GLM-5.3-Flash", ZaiModelCatalog.resolve(models, "glm-5.3-flash")!!.name)
        // "GLM" is a prefix of everything — ambiguous prefix falls through to contains,
        // which is still ambiguous, so the FIRST contains match is not accepted blindly:
        // contains-match must be unique too. "GLM" matches 3 → null.
        assertNull(ZaiModelCatalog.resolve(models, "GLM"))
        assertEquals("GLM-5.2", ZaiModelCatalog.resolve(models, "5.2")!!.name)
        assertNull(ZaiModelCatalog.resolve(models, "Claude"))
        assertNull(ZaiModelCatalog.resolve(models, "  "))
        assertNull(ZaiModelCatalog.resolve(emptyList(), "GLM-5.3"))
    }

    @Test
    fun `resolve unique prefix works`() {
        val models = ZaiModelCatalog.parse(optionsJson)
        // "GLM-5.3-F" prefixes exactly one option.
        assertEquals("GLM-5.3-Flash", ZaiModelCatalog.resolve(models, "GLM-5.3-F")!!.name)
    }
}
