package com.jarves.mh.zweb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ZaiChatPromptBuilderTest {

    private val budget = ZaiPromptBudget()

    private fun context(
        files: List<ZaiChatPromptBuilder.IncludedFile> = emptyList(),
        tree: String? = "src/main.kt\nREADME.md",
        recap: String? = "User asked for a login screen earlier.",
        fresh: Boolean = false,
        note: String? = null,
    ) = ZaiChatPromptBuilder.TurnContext(
        fileTree = tree,
        includedFiles = files,
        historyRecap = recap,
        freshConversation = fresh,
        needFilesNote = note,
    )

    @Test
    fun `prompt carries the answer contract - plan, FILE marker, full content, summary, NEED_FILES rule`() {
        val built = ZaiChatPromptBuilder.buildTurnPrompt("add a footer", context())
        assertTrue(built.prompt.contains("Start with a short plan"))
        assertTrue(built.prompt.contains(ZaiPromptBuilder.FILE_MARKER_RULE))
        assertTrue(built.prompt.contains("complete content"))
        assertTrue(built.prompt.contains("never output diffs"))
        assertTrue(built.prompt.contains("Summary:"))
        assertTrue(built.prompt.contains("NEED_FILES: relative/path1, relative/path2"))
        assertTrue(built.prompt.contains("Deleting files is not supported"))
    }

    @Test
    fun `user message goes last`() {
        val built = ZaiChatPromptBuilder.buildTurnPrompt("make the button blue", context())
        val index = built.prompt.lastIndexOf("USER MESSAGE:")
        assertTrue(index > 0)
        assertTrue(built.prompt.substring(index).contains("make the button blue"))
    }

    @Test
    fun `file tree section present when given`() {
        val built = ZaiChatPromptBuilder.buildTurnPrompt("hi", context(tree = "src/a.kt"))
        assertTrue(built.prompt.contains("PROJECT FILE TREE"))
        assertTrue(built.prompt.contains("src/a.kt"))
    }

    @Test
    fun `history recap appears only for fresh conversations`() {
        val continued = ZaiChatPromptBuilder.buildTurnPrompt("hi", context(recap = "earlier stuff", fresh = false))
        assertFalse(continued.prompt.contains("earlier stuff"))
        val fresh = ZaiChatPromptBuilder.buildTurnPrompt("hi", context(recap = "earlier stuff", fresh = true))
        assertTrue(fresh.prompt.contains("CONVERSATION SO FAR"))
        assertTrue(fresh.prompt.contains("earlier stuff"))
    }

    @Test
    fun `included files are wrapped with marker header and four-backtick fence`() {
        val built = ZaiChatPromptBuilder.buildTurnPrompt(
            "hi",
            context(files = listOf(ZaiChatPromptBuilder.IncludedFile("src/main.kt", "fun main() {}\n"))),
        )
        assertTrue(built.prompt.contains("=== FILE: src/main.kt ==="))
        assertTrue(built.prompt.contains("````\nfun main() {}"))
        assertTrue(built.prompt.contains("WORKSPACE FILES INCLUDED WITH THIS MESSAGE:"))
    }

    @Test
    fun `markdown file containing triple backticks cannot break the fence`() {
        val md = "# Readme\n```kotlin\nval x = 1\n```\n"
        val built = ZaiChatPromptBuilder.buildTurnPrompt(
            "hi",
            context(files = listOf(ZaiChatPromptBuilder.IncludedFile("README.md", md))),
        )
        assertTrue(built.prompt.contains("````\n# Readme"))
    }

    @Test
    fun `oversized file is truncated with marker and reported`() {
        val big = "y".repeat(30_000)
        val built = ZaiChatPromptBuilder.buildTurnPrompt(
            "hi",
            context(files = listOf(ZaiChatPromptBuilder.IncludedFile("big.txt", big))),
        )
        assertTrue(built.report.truncatedFiles.contains("big.txt"))
        assertTrue(built.prompt.contains(ZaiPromptBudget.TRUNCATION_MARKER))
        assertTrue(built.prompt.length < budget.maxTotalChars)
    }

    @Test
    fun `total prompt never exceeds the hard budget even with many files`() {
        val files = (1..12).map { ZaiChatPromptBuilder.IncludedFile("f$it.txt", "z".repeat(20_000)) }
        val built = ZaiChatPromptBuilder.buildTurnPrompt("hi", context(files = files, fresh = true))
        assertTrue("prompt was ${built.prompt.length}", built.prompt.length <= budget.maxTotalChars)
        assertTrue(built.report.droppedFiles.isNotEmpty() || built.report.truncatedFiles.isNotEmpty())
    }

    @Test
    fun `dropped files are listed with a NEED_FILES hint`() {
        val files = (1..8).map { ZaiChatPromptBuilder.IncludedFile("d$it.txt", "z".repeat(20_000)) }
        val built = ZaiChatPromptBuilder.buildTurnPrompt("hi", context(files = files))
        if (built.report.droppedFiles.isNotEmpty()) {
            built.report.droppedFiles.forEach { path ->
                assertTrue(built.prompt.contains("[FILE NOT INCLUDED: $path"))
                assertTrue(built.prompt.contains("NEED_FILES: $path]"))
            }
        }
    }

    @Test
    fun `need-files note is embedded`() {
        val built = ZaiChatPromptBuilder.buildTurnPrompt("ok continue", context(note = "Providing requested files: a.txt"))
        assertTrue(built.prompt.contains("Providing requested files: a.txt"))
    }

    @Test
    fun `blank message is rejected`() {
        try {
            ZaiChatPromptBuilder.buildTurnPrompt("   ", context())
            throw AssertionError("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun `sequential fit fills earlier files first and drops stubs`() {
        val fitting = ZaiChatPromptBuilder.fitFiles(
            files = listOf(
                ZaiChatPromptBuilder.IncludedFile("a.txt", "a".repeat(8_000)),
                ZaiChatPromptBuilder.IncludedFile("b.txt", "b".repeat(5_000)),
            ),
            availableChars = 7_000,
            maxFileChars = 12_000,
        )
        assertEquals(listOf("a.txt"), fitting.files.map { it.path })
        assertTrue(fitting.files.first().truncated)
        assertEquals(listOf("b.txt"), fitting.dropped.map { it.path })
    }

    @Test
    fun `fit below the minimum slice drops instead of stubbing`() {
        val fitting = ZaiChatPromptBuilder.fitFiles(
            files = listOf(ZaiChatPromptBuilder.IncludedFile("tiny.txt", "t".repeat(4_000))),
            availableChars = 500,
            maxFileChars = 12_000,
        )
        assertTrue(fitting.files.isEmpty())
        assertEquals(listOf("tiny.txt"), fitting.dropped.map { it.path })
    }
}
