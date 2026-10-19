package com.jarves.mh.zweb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 4 executor + protocol tests (pure pieces only — the PRoot exec path
 * itself needs a device and is covered by the executor's defensive contract).
 */
class ZaiRunExecutorTest {

    // ── credential-helper assembly ──

    @Test
    fun `git push gets the inline credential helper when a token is available`() {
        val assembled = ZaiRunExecutor.assembleCommand(
            "git push origin main",
            setOf("GITHUB_TOKEN"),
        )
        assertTrue(assembled.startsWith("git "))
        assertTrue(assembled.contains("push origin main"))
        // The helper references the env var NAME, single-quoted so the outer
        // shell never expands it — the value never appears in argv.
        assertTrue(assembled.contains("password=\$GITHUB_TOKEN"))
        assertFalse(assembled.contains("ghp_"))
        // The persisted helper list is reset first (nothing reaches .git/config).
        assertTrue(assembled.contains("credential.helper='"))
    }

    @Test
    fun `git commands pass through unchanged without any token env var`() {
        assertEquals("git push origin main", ZaiRunExecutor.assembleCommand("git push origin main", emptySet()))
    }

    @Test
    fun `non-git commands are never touched`() {
        val command = "curl -s -H \"Authorization: Bearer \$GITHUB_TOKEN\" https://api.github.com/user"
        assertEquals(command, ZaiRunExecutor.assembleCommand(command, setOf("GITHUB_TOKEN")))
    }

    @Test
    fun `git status is not a network command (no helper)`() {
        assertEquals(
            "git status --short",
            ZaiRunExecutor.assembleCommand("git status --short", setOf("GITHUB_TOKEN")),
        )
    }

    // ── output bounding ──

    @Test
    fun `short output passes through`() {
        assertEquals("hello", ZaiRunExecutor.boundTail("hello", 100))
    }

    @Test
    fun `long output keeps the tail with a trim marker`() {
        val long = (1..500).joinToString("\n") { "line $it" }
        val bounded = ZaiRunExecutor.boundTail(long, 1000)
        assertTrue(bounded.startsWith("[… earlier output trimmed …]"))
        assertTrue(bounded.contains("line 500"))
        assertFalse(bounded.contains("line 1\n"))
        // The bound is respected (plus the marker line).
        assertTrue(bounded.length < 1100)
    }

    // ── result blocks for the model ──

    @Test
    fun `refused outcome reports the refusal verbatim`() {
        val block = ZaiRunExecutor.RunOutcome(
            command = "sudo rm -rf /",
            exitCode = null,
            output = "refused: system-level command",
            refused = true,
            timedOut = false,
            durationMillis = 0L,
        ).asReportBlock()
        assertTrue(block.contains("$ sudo rm -rf /"))
        assertTrue(block.contains("refused: system-level command"))
        assertFalse(block.contains("exit:"))
    }

    @Test
    fun `normal outcome carries exit code and output`() {
        val block = ZaiRunExecutor.RunOutcome(
            command = "ls src",
            exitCode = 0,
            output = "main.kt",
            refused = false,
            timedOut = false,
            durationMillis = 12L,
        ).asReportBlock()
        assertTrue(block.contains("$ ls src"))
        assertTrue(block.contains("exit: 0"))
        assertTrue(block.contains("main.kt"))
        assertFalse(block.contains("timed out"))
    }

    @Test
    fun `timed-out outcome says so`() {
        val block = ZaiRunExecutor.RunOutcome(
            command = "sleep 1000",
            exitCode = null,
            output = "(no output)",
            refused = false,
            timedOut = true,
            durationMillis = 120_000L,
        ).asReportBlock()
        assertTrue(block.contains("timed out"))
        assertTrue(block.contains("exit: -1"))
    }
}

class ZaiRunProtocolTest {

    @Test
    fun `protocol section teaches the whole contract`() {
        val section = ZaiRunProtocol.runLoopSection(maxIterations = 40, perCommandTimeoutSeconds = 120)
        assertTrue(section.contains("RUN: "))
        assertTrue(section.contains("DONE:"))
        assertTrue(section.contains("NEED_FILES:"))
        // Untrusted-data rule (mandated).
        assertTrue(section.contains("UNTRUSTED DATA RULE"))
        // Token rule: names only.
        assertTrue(section.contains("\$GITHUB_TOKEN"))
        assertTrue(section.contains("NEVER print, echo, write, or save"))
        // Budget + timeout numbers are baked in.
        assertTrue(section.contains("40"))
        assertTrue(section.contains("120s"))
    }

    @Test
    fun `results message carries the blocks and the continue instruction`() {
        val message = ZaiRunProtocol.resultsMessage(
            blocks = listOf("$ ls\nexit: 0\n--- output ---\nsrc"),
            iterationsUsed = 2,
            maxIterations = 40,
        )
        assertTrue(message.startsWith("RESULTS (round 2/40):"))
        assertTrue(message.contains("ls"))
        assertTrue(message.contains("If it is complete, reply with DONE:"))
    }

    @Test
    fun `budget-exhausted message forbids further commands`() {
        val message = ZaiRunProtocol.budgetExhaustedMessage("Reached the iteration limit (40 commands).")
        assertTrue(message.contains("Do NOT request any more RUN commands"))
        assertTrue(message.contains("DONE:"))
        assertTrue(message.contains("Reached the iteration limit (40 commands)"))
    }

    @Test
    fun `report title summarizes counts`() {
        val title = ZaiRunProtocol.reportTitle(
            ZaiRunProtocol.LoopReport(
                taskId = "zr-1",
                mode = "FULL_AUTO",
                iterationsUsed = 3,
                commandsRun = 5,
                refusals = 1,
                durationMillis = 65_000L,
                stoppedBecause = "the model reported DONE",
                summary = "did the thing",
            ),
        )
        assertTrue(title.contains("3 round(s)"))
        assertTrue(title.contains("5 command(s)"))
    }
}

class ZaiRunSafeAutoTest {

    @Test
    fun `read-only heads are auto-approved`() {
        assertTrue(ZaiRunLoopPolicy.isReadOnlyCommand("ls -la src"))
        assertTrue(ZaiRunLoopPolicy.isReadOnlyCommand("cat README.md"))
        assertTrue(ZaiRunLoopPolicy.isReadOnlyCommand("grep -r TODO ."))
        assertTrue(ZaiRunLoopPolicy.isReadOnlyCommand("git status --short"))
        assertTrue(ZaiRunLoopPolicy.isReadOnlyCommand("git log --oneline -5"))
        assertTrue(ZaiRunLoopPolicy.isReadOnlyCommand("node --version"))
    }

    @Test
    fun `anything that writes, removes or reaches out waits`() {
        assertFalse(ZaiRunLoopPolicy.isReadOnlyCommand("rm -rf build"))
        assertFalse(ZaiRunLoopPolicy.isReadOnlyCommand("git push origin main"))
        assertFalse(ZaiRunLoopPolicy.isReadOnlyCommand("npm install"))
        assertFalse(ZaiRunLoopPolicy.isReadOnlyCommand("curl https://api.github.com/user"))
        assertFalse(ZaiRunLoopPolicy.isReadOnlyCommand("cat a > b"))
        assertFalse(ZaiRunLoopPolicy.isReadOnlyCommand("ls && rm x"))
        assertFalse(ZaiRunLoopPolicy.isReadOnlyCommand("echo \$(dangerous)"))
        assertFalse(ZaiRunLoopPolicy.isReadOnlyCommand(""))
    }
}
