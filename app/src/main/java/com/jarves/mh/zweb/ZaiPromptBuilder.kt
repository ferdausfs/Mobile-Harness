package com.jarves.mh.zweb

/**
 * Builds the prompts sent through the chat.z.ai hidden WebView.
 *
 * The task prompt embeds the output-format contract the extractor relies on:
 * every file must be emitted as its own fenced code block, immediately preceded
 * by a `FILE: path/name` line, followed by a closing plain-text summary.
 */
object ZaiPromptBuilder {

    const val FILE_MARKER_RULE =
        "- Before each file you create or modify, write a line on its own in exactly this format: FILE: relative/path/to/file.name"

    private val TASK_TEMPLATE = buildString {
        appendLine("You are assisting inside the Mobile Harness app. Work on the task below.")
        appendLine()
        appendLine("TASK:")
        appendLine("%TASK%")
        appendLine()
        appendLine("OUTPUT FORMAT (required):")
        appendLine(FILE_MARKER_RULE)
        appendLine("- Immediately after each FILE: line, output that file's complete content in one fenced code block (one code block per file, never merge files).")
        appendLine("- Always output the complete content of every file - never truncate, never use ellipses or placeholders.")
        appendLine("- After all files, end with a short plain-text summary section starting with \"Summary:\" (outside any code block) describing what you did.")
        appendLine("- If the task needs no files, just answer directly and still end with the \"Summary:\" section.")
    }

    /** Test prompt: tiny, deterministic, cheap. */
    const val TEST_PROMPT =
        "This is a connection test from the Mobile Harness app. " +
            "Reply with exactly one short sentence and nothing else: \"Mobile Harness connection to Z.ai works.\""

    fun buildTaskPrompt(task: String): String {
        val cleaned = task.trim()
        require(cleaned.isNotEmpty()) { "Task must not be blank" }
        return TASK_TEMPLATE.replace("%TASK%", cleaned)
    }
}
