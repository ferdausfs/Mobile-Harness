package com.jarves.mh.zweb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ZaiPromptBuilderTest {

    @Test
    fun `task prompt embeds the task and the FILE contract`() {
        val prompt = ZaiPromptBuilder.buildTaskPrompt("Build a todo app")
        assertTrue(prompt.contains("Build a todo app"))
        assertTrue(prompt.contains(ZaiPromptBuilder.FILE_MARKER_RULE))
        assertTrue(prompt.contains("FILE: relative/path/to/file.name"))
        assertTrue(prompt.contains("Summary:"))
    }

    @Test
    fun `v1_0_33 prompt teaches whole files, disclosure and optional commands`() {
        val prompt = ZaiPromptBuilder.buildTaskPrompt("Build a todo app")
        // Whole files only — partial snippets cannot be synced.
        assertTrue(prompt.contains(ZaiPromptBuilder.FILE_WHOLENESS_RULE))
        // The app reads sandbox-written files; every change must be listed.
        assertTrue(prompt.contains(ZaiPromptBuilder.FILE_DISCLOSURE_RULE))
        // Commands stay optional — a reply without RUN is still valid.
        assertTrue(prompt.contains(ZaiPromptBuilder.RUN_OPTIONAL_RULE))
        assertTrue(prompt.contains("OPTIONAL"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `blank task is rejected`() {
        ZaiPromptBuilder.buildTaskPrompt("   ")
    }

    @Test
    fun `task is trimmed before embedding`() {
        val prompt = ZaiPromptBuilder.buildTaskPrompt("  fix the bug  ")
        assertFalse(prompt.contains("  fix the bug  "))
        assertTrue(prompt.contains("fix the bug\n"))
    }

    @Test
    fun `test prompt is short and deterministic`() {
        assertTrue(ZaiPromptBuilder.TEST_PROMPT.length < 300)
        assertTrue(ZaiPromptBuilder.TEST_PROMPT.isNotBlank())
    }

    @Test
    fun `multiline task keeps its shape`() {
        val prompt = ZaiPromptBuilder.buildTaskPrompt("line one\nline two")
        assertTrue(prompt.contains("line one\nline two"))
    }
}
