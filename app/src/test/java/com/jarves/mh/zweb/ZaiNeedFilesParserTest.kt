package com.jarves.mh.zweb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ZaiNeedFilesParserTest {

    @Test
    fun `plain request parses comma separated paths`() {
        val result = ZaiNeedFilesParser.parse("NEED_FILES: src/main.kt, app/build.gradle.kts")
        assertTrue(result.markerPresent)
        assertTrue(result.hasRequest)
        assertEquals(listOf("src/main.kt", "app/build.gradle.kts"), result.requested)
    }

    @Test
    fun `bold and bulleted marker variants are accepted`() {
        assertTrue(ZaiNeedFilesParser.parse("**NEED_FILES:** a.txt").hasRequest)
        assertTrue(ZaiNeedFilesParser.parse("- NEED_FILES: a.txt").hasRequest)
        assertTrue(ZaiNeedFilesParser.parse("need_files: a.txt").hasRequest)
    }

    @Test
    fun `semicolon and comma separated single-line lists parse`() {
        val result = ZaiNeedFilesParser.parse("NEED_FILES: a.txt; b/c.md, d.json")
        assertEquals(listOf("a.txt", "b/c.md", "d.json"), result.requested)
    }

    @Test
    fun `unsafe paths are rejected and reported`() {
        val result = ZaiNeedFilesParser.parse("NEED_FILES: ../secret.txt, /etc/passwd, ok.txt")
        // Leading slashes are stripped by the sanitizer (workspace-relative),
        // only traversal and drive prefixes are truly refused.
        assertEquals(listOf("etc/passwd", "ok.txt"), result.requested)
        assertEquals(listOf("../secret.txt"), result.invalidPaths)
    }

    @Test
    fun `duplicates collapse`() {
        val result = ZaiNeedFilesParser.parse("NEED_FILES: a.txt, a.txt, a.txt")
        assertEquals(listOf("a.txt"), result.requested)
    }

    @Test
    fun `at most ten paths honoured, rest reported as overflow`() {
        val paths = (1..13).joinToString(", ") { "f$it.txt" }
        val result = ZaiNeedFilesParser.parse("NEED_FILES: $paths")
        assertEquals(10, result.requested.size)
        assertEquals(3, result.overflowPaths.size)
    }

    @Test
    fun `marker without usable paths is not a request`() {
        val result = ZaiNeedFilesParser.parse("NEED_FILES: ../.., C:/x")
        assertTrue(result.markerPresent)
        assertFalse(result.hasRequest)
    }

    @Test
    fun `no marker parses to empty result`() {
        val result = ZaiNeedFilesParser.parse("Here is your answer.\nSummary: done")
        assertFalse(result.markerPresent)
        assertFalse(result.hasRequest)
    }

    @Test
    fun `blank reply is not a request`() {
        assertFalse(ZaiNeedFilesParser.parse("   ").markerPresent)
    }

    @Test
    fun `reply that already outputs files is not auto-fulfillable`() {
        val reply = """
            Plan: do it.
            FILE: src/a.kt
            ```kotlin
            fun a() {}
            ```

            Also NEED_FILES: src/b.kt
            Summary: done
        """.trimIndent()
        assertFalse(ZaiNeedFilesParser.isAutoFulfillable(reply))
    }

    @Test
    fun `bare request is auto-fulfillable`() {
        assertTrue(ZaiNeedFilesParser.isAutoFulfillable("NEED_FILES: src/a.kt, src/b.kt"))
    }

    @Test
    fun `request with short prose around it is auto-fulfillable`() {
        val reply = """
            I need the current implementation before I can change it.

            NEED_FILES: lib/engine.dart

            Summary: waiting for files
        """.trimIndent()
        assertTrue(ZaiNeedFilesParser.isAutoFulfillable(reply))
    }
}
