package com.jarves.mh.zweb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ZaiPromptBudgetTest {

    private val budget = ZaiPromptBudget()

    @Test
    fun `content within cap passes through untouched`() {
        val result = ZaiPromptBudget.truncateContent("hello world", 100, "a.txt")
        assertEquals("hello world", result.content)
        assertEquals(0, result.truncatedChars)
        assertFalse(result.truncated)
    }

    @Test
    fun `content over cap is truncated with a clear marker`() {
        val content = "x".repeat(500)
        val result = ZaiPromptBudget.truncateContent(content, 200, "src/a.txt")
        assertTrue(result.truncated)
        assertEquals(300, result.truncatedChars)
        assertEquals(500, result.totalChars)
        assertTrue(result.content.startsWith("x".repeat(200)))
        assertTrue(result.content.contains("[TRUNCATED: showing first 200 of 500 characters."))
        assertTrue(result.content.contains("NEED_FILES: src/a.txt]"))
    }

    @Test
    fun `zero or negative cap yields only the marker`() {
        val result = ZaiPromptBudget.truncateContent("data", 0, "f.txt")
        assertTrue(result.truncated)
        assertEquals(4, result.truncatedChars)
        assertTrue(result.content.contains("showing first 0 of 4"))
    }

    @Test
    fun `file budget clamps at zero`() {
        assertEquals(0, budget.fileBudgetChars(budget.maxTotalChars + 5_000))
        val expected = budget.maxTotalChars - 10_000 - budget.instructionReserveChars
        assertEquals(expected, budget.fileBudgetChars(10_000))
    }

    @Test
    fun `default budget keeps the prompt within chat-zai-safe sizes`() {
        assertTrue(budget.maxTotalChars <= 60_000)
        assertTrue(budget.maxFileChars < budget.maxTotalChars)
        assertTrue(budget.maxMessageChars < budget.maxTotalChars)
    }
}
