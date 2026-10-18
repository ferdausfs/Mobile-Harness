package com.jarves.mh.zweb

/**
 * Builds the prompts sent through the hidden WebView for the PROJECT WORKSPACE
 * CHAT when Z.ai Web is the active chat engine.
 *
 * This is the chat-engine adapter described in AGENT_LOG (v1.0.25): Z.ai Web
 * is not a CLI agent — it only returns text — so the app assembles everything
 * the model needs into one prompt: the user's message, the project file tree,
 * the contents of attached/requested files, and (for a fresh Z.ai conversation)
 * a short recap of the project chat history.
 *
 * Per-turn chat policy (decided in ZaiChatTurnPolicy, documented in AGENT_LOG):
 * each turn CONTINUES the same chat.z.ai conversation per project chat; a NEW
 * Z.ai conversation is started only when that chat has no live page conversation
 * (first message, app restart, after Stop, or after an error) — in that case the
 * prompt carries a history recap so follow-up messages still make sense. The
 * workspace context (tree + files) is refreshed on EVERY turn either way.
 *
 * The answer contract taught here mirrors [ZaiPromptBuilder] (the extractor's
 * expectations are identical) and adds two chat-engine specific rules: a short
 * PLAN before the files, and the NEED_FILES protocol.
 */
object ZaiChatPromptBuilder {

    /** Marker the model is taught to emit when it needs workspace file contents. */
    const val NEED_FILES_MARKER = "NEED_FILES:"

    /** UI title used everywhere the project chat names the active engine. */
    const val ZAI_ENGINE_TITLE = "Z.ai"

    /** Stable id of the Z.ai chat engine in the chat-engine selector. */
    const val ZAI_WEB_ENGINE_ID = "zai-web"

    private val TEMPLATE = buildString {
        appendLine("You are the coding assistant inside the Mobile Harness app's project chat. Work on the user's message below using the workspace context provided.")
        appendLine()
        appendLine("OUTPUT FORMAT (required):")
        appendLine("- Start with a short plan (a few lines).")
        appendLine(ZaiPromptBuilder.FILE_MARKER_RULE)
        appendLine("- Immediately after each FILE: line, output that file's complete content in one fenced code block (one code block per file, never merge files).")
        appendLine("- Always output the complete content of every file you create or change - never truncate, never use ellipses or placeholders, never output diffs.")
        appendLine("- Only output files you actually create or change; do not re-output untouched files.")
        appendLine("- After all files, end with a short plain-text summary section starting with \"Summary:\" (outside any code block).")
        appendLine("- If the request needs no file changes, just answer directly and still end with the \"Summary:\" section.")
        appendLine("- If you need the contents of workspace files that were not included above, and nothing else blocks you, reply with a single line in exactly this format and nothing else: NEED_FILES: relative/path1, relative/path2")
        appendLine("- Deleting files is not supported in this mode; say so in the plan instead.")
    }

    /** One workspace file bundled into a turn (attachment or NEED_FILES fulfilment). */
    data class IncludedFile(
        val path: String,
        val content: String,
    )

    /** Assembly inputs for one chat-engine turn. */
    data class TurnContext(
        /** Pre-formatted project file tree (paths only). Null → section omitted. */
        val fileTree: String?,
        /** Files bundled with this turn (attachments, NEED_FILES fulfilment). */
        val includedFiles: List<IncludedFile>,
        /** Recap of the project chat; used only when starting a fresh Z.ai conversation. */
        val historyRecap: String?,
        /** True when this turn opens a NEW chat.z.ai conversation. */
        val freshConversation: Boolean,
        /** Note about files the model asked for in the previous reply (NEED_FILES). */
        val needFilesNote: String? = null,
    )

    /** What the budget actually did to the turn, for user-visible honesty. */
    data class BudgetReport(
        val totalChars: Int,
        val truncatedFiles: List<String>,
        val droppedFiles: List<String>,
        val treeTruncated: Boolean,
        val historyTruncated: Boolean,
        val messageTruncated: Boolean,
    ) {
        val isWithinBudget: Boolean get() = totalChars <= 0 || truncatedFiles.isEmpty() && droppedFiles.isEmpty()
    }

    data class BuiltTurn(
        val prompt: String,
        val report: BudgetReport,
    )

    fun buildTurnPrompt(
        userMessage: String,
        context: TurnContext,
        budget: ZaiPromptBudget = ZaiPromptBudget(),
    ): BuiltTurn {
        val cleanedMessage = userMessage.trim()
        require(cleanedMessage.isNotEmpty()) { "User message must not be blank" }

        val tree = context.fileTree
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?.let { ZaiPromptBudget.truncateContent(it, budget.maxTreeChars, "the file tree") }
        val recap = if (context.freshConversation) {
            context.historyRecap
                ?.trim()
                ?.takeIf(String::isNotEmpty)
                ?.let { ZaiPromptBudget.truncateContent(it, budget.maxHistoryChars, "the conversation recap") }
        } else {
            null
        }
        val message = ZaiPromptBudget.truncateContent(cleanedMessage, budget.maxMessageChars, "your message")

        // Assemble everything except file contents first, so the remaining
        // budget for files is computed from the real fixed sections.
        val head = buildString {
            append(TEMPLATE)
            appendLine()
            if (tree != null) {
                appendLine("PROJECT FILE TREE (relative paths; build, cache and VCS directories omitted):")
                appendLine(tree.content)
                appendLine()
            }
            if (recap != null) {
                appendLine("CONVERSATION SO FAR (new Z.ai conversation — recap of this project chat):")
                appendLine(recap.content)
                appendLine()
            }
            context.needFilesNote?.let {
                appendLine(it)
                appendLine()
            }
        }

        val fileBudget = budget.fileBudgetChars(head.length + message.content.length)
        val fitted = fitFiles(context.includedFiles, fileBudget, budget.maxFileChars)

        val prompt = buildString {
            append(head)
            if (fitted.files.isNotEmpty()) {
                appendLine("WORKSPACE FILES INCLUDED WITH THIS MESSAGE:")
                fitted.files.forEach { file ->
                    appendLine("=== FILE: ${file.path} ===")
                    appendLine("````")
                    appendLine(file.content)
                    appendLine("````")
                    appendLine()
                }
            }
            fitted.dropped.forEach { dropped ->
                appendLine("[FILE NOT INCLUDED: ${dropped.path} — ${dropped.reason}. Request it with NEED_FILES: ${dropped.path}]")
            }
            appendLine("USER MESSAGE:")
            append(message.content)
        }

        return BuiltTurn(
            prompt = prompt,
            report = BudgetReport(
                totalChars = prompt.length,
                truncatedFiles = fitted.files.filter { it.truncated }.map { it.path },
                droppedFiles = fitted.dropped.map { it.path },
                treeTruncated = tree?.truncated == true,
                historyTruncated = recap?.truncated == true,
                messageTruncated = message.truncated,
            ),
        )
    }

    internal data class FittedFile(
        val path: String,
        val content: String,
        val truncated: Boolean,
    )

    internal data class DroppedFile(
        val path: String,
        val reason: String,
    )

    internal data class Fitting(
        val files: List<FittedFile>,
        val dropped: List<DroppedFile>,
    )

    /**
     * Sequential allocation: earlier files are filled first (up to
     * [ZaiPromptBudget.maxFileChars] each), leftovers go to the next file, and
     * anything below [ZaiPromptBudget.MIN_FILE_SLICE_CHARS] is dropped with a
     * reason instead of being included as an unusable stub. NEED_FILES exists
     * precisely so the model can pull a dropped file in a follow-up round.
     */
    internal fun fitFiles(
        files: List<IncludedFile>,
        availableChars: Int,
        maxFileChars: Int,
    ): Fitting {
        var remaining = availableChars.coerceAtLeast(0)
        val fitted = ArrayList<FittedFile>(files.size)
        val dropped = ArrayList<DroppedFile>()
        files.forEach { file ->
            val total = file.content.length
            val allowance = minOf(maxFileChars, remaining)
            if (allowance < ZaiPromptBudget.MIN_FILE_SLICE_CHARS) {
                dropped += DroppedFile(
                    path = file.path,
                    reason = "prompt budget exhausted (file is $total characters)",
                )
                return@forEach
            }
            val piece = ZaiPromptBudget.truncateContent(file.content, allowance, file.path)
            remaining -= piece.content.length
            fitted += FittedFile(path = file.path, content = piece.content, truncated = piece.truncated)
        }
        return Fitting(fitted, dropped)
    }
}
