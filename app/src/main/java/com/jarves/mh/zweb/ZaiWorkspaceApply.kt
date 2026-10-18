package com.jarves.mh.zweb

import com.jarves.mh.model.DiffLine
import com.jarves.mh.model.DiffLineType
import com.jarves.mh.zweb.ZaiFileExtractor.ZaiExtractedFile
import java.io.File

/**
 * Plans and applies the files a Z.ai reply returned to the PROJECT WORKSPACE.
 *
 * This is the chat-engine apply pipeline (v1.0.25). It never touches the
 * agent bridges: the ViewModel creates the workspace checkpoint BEFORE calling
 * [apply] (so the app's existing Undo restores the pre-apply state) and then
 * surfaces the outcome in the normal Changes tab.
 *
 * Safety rules (all enforced here, independent of what the model wrote):
 *  - paths are re-sanitized with [ZaiFileExtractor.sanitizeZaiPath] even though
 *    the extractor already sanitized — the workspace is the valuable thing;
 *  - absolute paths, `..` traversal, Windows drive prefixes and any path
 *    touching VCS/dependency/generated directories are REJECTED, never written;
 *  - the resolved target must stay inside the workspace (canonical containment);
 *  - deletions are not supported in this mode (the prompt forbids them and the
 *    plan simply has no delete action).
 */
object ZaiWorkspaceApply {

    enum class FileStatus { NEW, MODIFIED }

    data class PlannedFile(
        val path: String,
        val newContent: String,
        val status: FileStatus,
        val diffLines: List<DiffLine>,
    )

    data class RejectedFile(val path: String, val reason: String)

    data class PlanResult(
        val files: List<PlannedFile>,
        val rejected: List<RejectedFile>,
        /** Extracted paths whose content is byte-identical to the current file. */
        val unchanged: List<String>,
    ) {
        val hasWork: Boolean get() = files.isNotEmpty()
    }

    data class ApplyOutcome(
        val applied: List<String>,
        val failed: List<RejectedFile>,
    )

    data class RevertResult(
        val restored: List<String>,
        val missingFromCheckpoint: List<String>,
    )

    /** Directories the chat engine must never write into (VCS/deps/generated). */
    internal val DENIED_DIRECTORY_NAMES = setOf(
        ".git",
        ".claude",
        ".gradle",
        ".idea",
        ".next",
        ".dart_tool",
        ".venv",
        "build",
        "dist",
        "node_modules",
        "target",
        "venv",
    )

    /** Files larger than this get a "too large to preview" diff stub (mirrors the Changes tab). */
    private const val MAX_PREVIEW_BYTES = 1_000_000L

    fun plan(
        extracted: List<ZaiExtractedFile>,
        workspace: File,
        diff: (before: ByteArray, after: ByteArray) -> List<DiffLine>,
    ): PlanResult {
        val files = ArrayList<PlannedFile>()
        val rejected = ArrayList<RejectedFile>()
        val unchanged = ArrayList<String>()

        extracted.forEach { candidate ->
            val path = ZaiFileExtractor.sanitizeZaiPath(candidate.path)
            if (path == null) {
                rejected += RejectedFile(candidate.path, "unsafe path")
                return@forEach
            }
            val denied = path.split('/').any { it in DENIED_DIRECTORY_NAMES }
            if (denied) {
                rejected += RejectedFile(path, "protected directory (VCS, dependencies, or build output)")
                return@forEach
            }
            val target = File(workspace, path)
            val workspacePath = workspace.canonicalFile.toPath()
            val targetPath = target.canonicalFile.toPath()
            if (!targetPath.startsWith(workspacePath)) {
                rejected += RejectedFile(path, "path escapes the project workspace")
                return@forEach
            }
            val exists = target.isFile
            val before = if (exists) {
                runCatching { target.readBytes() }.getOrElse {
                    rejected += RejectedFile(path, "existing file could not be read")
                    return@forEach
                }
            } else {
                ByteArray(0)
            }
            val after = candidate.content.toByteArray(Charsets.UTF_8)
            if (before.contentEquals(after)) {
                unchanged += path
                return@forEach
            }
            val diffLines = if (before.size > MAX_PREVIEW_BYTES || after.size > MAX_PREVIEW_BYTES) {
                listOf(DiffLine(DiffLineType.INFO, "File is too large to preview safely. Undo and Keep still work."))
            } else {
                diff(before, after)
            }
            files += PlannedFile(
                path = path,
                newContent = candidate.content,
                status = if (exists) FileStatus.MODIFIED else FileStatus.NEW,
                diffLines = diffLines,
            )
        }
        return PlanResult(files = files, rejected = rejected, unchanged = unchanged)
    }

    /** Writes the planned files under [workspace]. Already-validated paths are re-checked. */
    fun apply(plan: List<PlannedFile>, workspace: File): ApplyOutcome {
        val applied = ArrayList<String>(plan.size)
        val failed = ArrayList<RejectedFile>()
        val workspacePath = workspace.canonicalFile.toPath()
        plan.forEach { file ->
            try {
                val target = File(workspace, file.path)
                if (!target.canonicalFile.toPath().startsWith(workspacePath)) {
                    failed += RejectedFile(file.path, "path escapes the project workspace")
                    return@forEach
                }
                target.parentFile?.mkdirs()
                target.writeText(file.newContent, Charsets.UTF_8)
                applied += file.path
            } catch (e: Exception) {
                failed += RejectedFile(file.path, e.message ?: "write failed")
            }
        }
        return ApplyOutcome(applied = applied, failed = failed)
    }

    /**
     * Restores [paths] from the checkpoint's project copy: a file present in
     * the checkpoint is copied back, a file absent (created after the
     * checkpoint) is deleted. Mirrors the bridge undo semantics so the Changes
     * tab behaves identically for Z.ai applies.
     */
    fun revertFiles(workspace: File, checkpointProjectDir: File, paths: List<String>): RevertResult {
        val restored = ArrayList<String>()
        val missing = ArrayList<String>()
        val workspacePath = workspace.canonicalFile.toPath()
        paths.forEach { path ->
            val sanitized = ZaiFileExtractor.sanitizeZaiPath(path)
            if (sanitized == null) return@forEach
            try {
                val target = File(workspace, sanitized)
                if (!target.canonicalFile.toPath().startsWith(workspacePath)) return@forEach
                val original = File(checkpointProjectDir, sanitized)
                if (original.isFile) {
                    target.parentFile?.mkdirs()
                    original.copyTo(target, overwrite = true)
                    restored += sanitized
                } else if (target.isFile) {
                    target.delete()
                    restored += sanitized
                } else {
                    missing += sanitized
                }
            } catch (e: Exception) {
                missing += sanitized
            }
        }
        return RevertResult(restored = restored, missingFromCheckpoint = missing)
    }
}

/**
 * A reply's apply plan waiting for the user's review in the chat (Apply per
 * file / Apply all / Reject). In-memory only: the transcript itself is
 * persisted, the pending review card is not — after a process death the
 * assistant message is still there and the user can reject and re-ask.
 */
data class ZaiPendingApply(
    val projectId: String,
    val chatId: String,
    val messageId: String,
    val files: List<ZaiWorkspaceApply.PlannedFile>,
    val rejected: List<ZaiWorkspaceApply.RejectedFile>,
    val unchanged: List<String>,
) {
    val isEmpty: Boolean get() = files.isEmpty() && rejected.isEmpty()
}
