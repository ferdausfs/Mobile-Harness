package com.jarves.mh.zweb

/**
 * Hard per-prompt size budget for Z.ai chat-engine turns.
 *
 * chat.z.ai's composer is a web text box with an undocumented input limit, so
 * every prompt the hidden engine injects is capped HERE, before injection —
 * the limit must never be discovered by the prompt being silently cut off at
 * the site side. All numbers are characters (the injection path is text, not
 * bytes).
 *
 * The budget is pure logic: the caller hands in the prompt pieces, the budget
 * truncates with explicit markers and reports what was dropped so the model
 * can ask for missing pieces via NEED_FILES and the user can see why a file
 * was left out.
 */
data class ZaiPromptBudget(
    /** Hard ceiling for the fully assembled prompt. */
    val maxTotalChars: Int = 60_000,
    /** Cap for the user's own message (still truncates, with a marker). */
    val maxMessageChars: Int = 8_000,
    /** Cap for the fresh-conversation history recap. */
    val maxHistoryChars: Int = 6_000,
    /** Cap for the project file tree listing. */
    val maxTreeChars: Int = 6_000,
    /** Cap for any single included file's content. */
    val maxFileChars: Int = 12_000,
    /** Reserve for the fixed instruction block so files can never squeeze it out. */
    val instructionReserveChars: Int = 2_500,
) {
    /**
     * Characters available for file contents once the fixed sections have been
     * assembled. Never negative; a zero means no file content can be included.
     */
    fun fileBudgetChars(fixedUsedChars: Int): Int =
        (maxTotalChars - fixedUsedChars - instructionReserveChars).coerceAtLeast(0)

    data class TruncatedContent(
        val content: String,
        val totalChars: Int,
        val truncatedChars: Int,
    ) {
        val truncated: Boolean get() = truncatedChars > 0
    }

    companion object {
        /** Every truncation in a chat-engine prompt carries this marker prefix. */
        const val TRUNCATION_MARKER = "[TRUNCATED"

        /** Below this slice a file is dropped entirely instead of being uselessly stubbed. */
        const val MIN_FILE_SLICE_CHARS = 800

        /**
         * Hard-truncates [content] to [capChars] with a clear, machine-readable
         * marker telling the model how to request the rest. Cap <= 0 → empty
         * content (fully truncated); content within the cap is returned as-is.
         */
        fun truncateContent(content: String, capChars: Int, path: String): TruncatedContent {
            val total = content.length
            if (capChars <= 0) {
                return TruncatedContent(
                    content = marker(0, total, path),
                    totalChars = total,
                    truncatedChars = total,
                )
            }
            if (total <= capChars) return TruncatedContent(content, total, 0)
            val head = content.take(capChars)
            return TruncatedContent(
                content = head + "\n" + marker(capChars, total, path),
                totalChars = total,
                truncatedChars = total - capChars,
            )
        }

        private fun marker(shownChars: Int, totalChars: Int, path: String): String =
            "$TRUNCATION_MARKER: showing first $shownChars of $totalChars characters. " +
                "Request the rest with NEED_FILES: $path]"
    }
}
