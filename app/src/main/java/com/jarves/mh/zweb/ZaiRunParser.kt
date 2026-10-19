package com.jarves.mh.zweb

/**
 * Parses RUN blocks out of a Z.ai reply (Phase 4, the autonomous loop).
 *
 * Contract taught to the model:
 *  - `RUN: <command>` — the rest of the line is one shell command; several
 *    RUN lines per reply are allowed (executed in order).
 *  - `RUN:` alone on a line followed by a fenced code block — multi-line
 *    script (the fenced content is the command).
 *  - `DONE:` — starts the final summary; everything after it is the summary.
 *  - `NEED_FILES:` — handled by the existing [ZaiNeedFilesParser], kept
 *    working inside the loop.
 *  - FILE: blocks keep working exactly as before (an answer with FILE blocks
 *    and no RUN/DONE is still just an answer).
 *
 * Caps: at most [MAX_RUNS_PER_REPLY] commands per reply and
 * [MAX_COMMAND_CHARS] per command (excess is REFUSED by the parser, never
 * truncated into a different command).
 */
object ZaiRunParser {

    const val RUN_MARKER = "RUN:"
    const val DONE_MARKER = "DONE:"
    const val MAX_RUNS_PER_REPLY = 8
    const val MAX_COMMAND_CHARS = 2_000

    /** One requested command (raw, unexecuted — the denylist decides later). */
    data class RunCommand(
        val command: String,
        /** Source line where the RUN appeared (1-based) for the activity log. */
        val sourceLine: Int,
    )

    data class ParsedReply(
        val runs: List<RunCommand>,
        /** Text after DONE: (the final summary). Null = no DONE in this reply. */
        val doneSummary: String?,
        /** True when the reply asked for files (NEED_FILES) — loop pauses for fulfilment. */
        val needsFiles: Boolean,
    )

    fun parse(reply: String): ParsedReply {
        val runs = ArrayList<RunCommand>()
        var doneSummary: String? = null
        var needsFiles = false

        val lines = reply.lines()
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val trimmed = line.trim()
            when {
                doneSummary == null && trimmed.startsWith(DONE_MARKER, ignoreCase = true) -> {
                    // The final summary is everything after the marker line.
                    val inline = trimmed.substring(DONE_MARKER.length).trim()
                    val rest = lines.drop(i + 1).joinToString("\n").trim()
                    doneSummary = listOf(inline, rest).filter { it.isNotEmpty() }.joinToString("\n").trim()
                    break
                }
                doneSummary == null && trimmed.startsWith(RUN_MARKER, ignoreCase = true) && runs.size < MAX_RUNS_PER_REPLY -> {
                    val inline = trimmed.substring(RUN_MARKER.length).trim()
                    if (inline.isNotEmpty()) {
                        if (inline.length <= MAX_COMMAND_CHARS) {
                            runs.add(RunCommand(inline, i + 1))
                        }
                    } else {
                        // RUN: followed by a fenced block = multi-line script.
                        val fence = findNextFence(lines, i + 1)
                        if (fence != null) {
                            val (content, nextIndex) = fence
                            if (content.length <= MAX_COMMAND_CHARS) {
                                runs.add(RunCommand(content.trim(), i + 1))
                            }
                            i = nextIndex
                        }
                    }
                }
                doneSummary == null && trimmed.startsWith(ZaiChatPromptBuilder.NEED_FILES_MARKER, ignoreCase = true) -> {
                    needsFiles = true
                }
            }
            i++
        }
        return ParsedReply(runs = runs, doneSummary = doneSummary, needsFiles = needsFiles)
    }

    /** Finds the next fenced code block; returns its content and the line index after the closing fence. */
    private fun findNextFence(lines: List<String>, from: Int): Pair<String, Int>? {
        var opening = -1
        var fenceToken = ""
        for (j in from until lines.size) {
            val t = lines[j].trim()
            if (t.length >= 3 && (t.startsWith("```") || t.startsWith("~~~"))) {
                opening = j
                fenceToken = t.take(3)
                break
            }
        }
        if (opening == -1) return null
        for (k in opening + 1 until lines.size) {
            if (lines[k].trim().startsWith(fenceToken)) {
                return lines.subList(opening + 1, k).joinToString("\n") to k
            }
        }
        // Unterminated fence: take everything to the end (mirrors the extractor).
        return lines.subList(opening + 1, lines.size).joinToString("\n") to lines.size
    }
}
