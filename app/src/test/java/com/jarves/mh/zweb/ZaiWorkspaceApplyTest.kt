package com.jarves.mh.zweb

import com.jarves.mh.model.DiffLineType
import com.jarves.mh.zweb.ZaiFileExtractor.ZaiExtractedFile
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ZaiWorkspaceApplyTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** Stand-in for WorkspaceCheckpoints.buildDiffLines (same contract, tiny impl). */
    private val diff = { before: ByteArray, after: ByteArray ->
        val beforeLines = before.decodeToString().split('\n')
        val afterLines = after.decodeToString().split('\n')
        buildList {
            afterLines.forEachIndexed { index, line ->
                if (index >= beforeLines.size || beforeLines[index] != line) {
                    add(com.jarves.mh.model.DiffLine(DiffLineType.ADDITION, line))
                }
            }
            if (beforeLines.size > afterLines.size) {
                add(com.jarves.mh.model.DiffLine(DiffLineType.INFO, "removed lines"))
            }
        }
    }

    private fun extracted(path: String, content: String) = ZaiExtractedFile(path, "", content)

    @Test
    fun `new file is classified NEW with a diff`() {
        val workspace = tmp.newFolder("ws")
        val result = ZaiWorkspaceApply.plan(listOf(extracted("src/new.kt", "fun a() {}")), workspace, diff)
        assertTrue(result.hasWork)
        val file = result.files.single()
        assertEquals("src/new.kt", file.path)
        assertEquals(ZaiWorkspaceApply.FileStatus.NEW, file.status)
        assertTrue(file.diffLines.isNotEmpty())
        assertTrue(result.rejected.isEmpty())
        assertTrue(result.unchanged.isEmpty())
    }

    @Test
    fun `modified file is classified MODIFIED`() {
        val workspace = tmp.newFolder("ws").apply {
            File(this, "src").mkdirs()
            File(this, "src/a.kt").writeText("old\n")
        }
        val result = ZaiWorkspaceApply.plan(listOf(extracted("src/a.kt", "new\n")), workspace, diff)
        val file = result.files.single()
        assertEquals(ZaiWorkspaceApply.FileStatus.MODIFIED, file.status)
        assertTrue(file.diffLines.any { it.type == DiffLineType.ADDITION })
    }

    @Test
    fun `identical content lands in unchanged and is not planned`() {
        val workspace = tmp.newFolder("ws").apply {
            File(this, "a.kt").writeText("same\n")
        }
        val result = ZaiWorkspaceApply.plan(listOf(extracted("a.kt", "same\n")), workspace, diff)
        assertFalse(result.hasWork)
        assertEquals(listOf("a.kt"), result.unchanged)
    }

    @Test
    fun `unsafe paths are rejected - traversal and drive prefixes`() {
        val workspace = tmp.newFolder("ws")
        val result = ZaiWorkspaceApply.plan(
            listOf(
                extracted("../escape.kt", "x"),
                extracted("C:/x.y", "x"),
                extracted("/etc/passwd", "x"),
                extracted("ok.txt", "x"),
            ),
            workspace,
            diff,
        )
        // Leading slashes are stripped by the sanitizer: /etc/passwd becomes a
        // workspace-relative etc/passwd (no escape). Traversal and drive
        // prefixes are genuinely refused.
        assertEquals(listOf("etc/passwd", "ok.txt"), result.files.map { it.path })
        assertEquals(2, result.rejected.size)
    }

    @Test
    fun `protected directories are never written`() {
        val workspace = tmp.newFolder("ws")
        val result = ZaiWorkspaceApply.plan(
            listOf(
                extracted(".git/hooks/pre-commit", "#!/bin/sh\n"),
                extracted("node_modules/left-pad/index.js", "x"),
                extracted("build/out.txt", "x"),
                extracted("src/app.kt", "x"),
            ),
            workspace,
            diff,
        )
        assertEquals(listOf("src/app.kt"), result.files.map { it.path })
        assertEquals(3, result.rejected.size)
    }

    @Test
    fun `apply writes files including missing parent dirs`() {
        val workspace = tmp.newFolder("ws")
        val planned = listOf(
            ZaiWorkspaceApply.PlannedFile("deep/nested/file.txt", "content", ZaiWorkspaceApply.FileStatus.NEW, emptyList()),
        )
        val outcome = ZaiWorkspaceApply.apply(planned, workspace)
        assertEquals(listOf("deep/nested/file.txt"), outcome.applied)
        assertEquals("content", File(workspace, "deep/nested/file.txt").readText())
        assertTrue(outcome.failed.isEmpty())
    }

    @Test
    fun `revert restores modified files from checkpoint and deletes created ones`() {
        val workspace = tmp.newFolder("ws")
        val checkpoint = tmp.newFolder("ckpt").resolve("project").apply { mkdirs() }

        // Existing file modified by the apply.
        File(workspace, "mod.txt").writeText("original\n")
        File(checkpoint, "mod.txt").writeText("original\n")

        val planned = listOf(
            ZaiWorkspaceApply.PlannedFile("mod.txt", "changed\n", ZaiWorkspaceApply.FileStatus.MODIFIED, emptyList()),
            ZaiWorkspaceApply.PlannedFile("created.txt", "brand new\n", ZaiWorkspaceApply.FileStatus.NEW, emptyList()),
        )
        ZaiWorkspaceApply.apply(planned, workspace)
        assertEquals("changed\n", File(workspace, "mod.txt").readText())
        assertTrue(File(workspace, "created.txt").isFile)

        val result = ZaiWorkspaceApply.revertFiles(workspace, checkpoint, planned.map { it.path })
        assertEquals(setOf("mod.txt", "created.txt"), result.restored.toSet())
        assertEquals("original\n", File(workspace, "mod.txt").readText())
        assertFalse(File(workspace, "created.txt").exists())
    }

    @Test
    fun `revert of a path missing from both sides is reported`() {
        val workspace = tmp.newFolder("ws")
        val checkpoint = tmp.newFolder("ckpt").resolve("project").apply { mkdirs() }
        val result = ZaiWorkspaceApply.revertFiles(workspace, checkpoint, listOf("ghost.txt"))
        assertTrue(result.restored.isEmpty())
        assertEquals(listOf("ghost.txt"), result.missingFromCheckpoint)
    }
}
