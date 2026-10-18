package com.jarves.mh.zweb

/**
 * Parses a chat.z.ai reply for the NEED_FILES protocol taught by
 * [ZaiChatPromptBuilder]: when the model needs workspace file contents that
 * were not included in the prompt, it replies with a single
 * `NEED_FILES: relative/path1, relative/path2` line and the app fulfils the
 * request automatically (reads the files, re-sends them in the SAME Z.ai
 * conversation).
 *
 * Safety rules:
 *  - A reply that already contains FILE: output blocks is an ANSWER, never a
 *    bare request — auto-fulfilling it would fight with the apply pipeline and
 *    can loop. Only a file-less reply counts as a request.
 *  - Paths go through [ZaiFileExtractor.sanitizeZaiPath]; anything absolute,
 *    traversal-shaped, or otherwise unsafe is dropped (and reported).
 *  - At most [MAX_REQUESTED_FILES] paths are honoured per reply; the rest are
 *    reported as overflow so the round budget stays bounded.
 */
object ZaiNeedFilesParser {

    const val MAX_REQUESTED_FILES = 10

    private val NEED_LINE = Regex(
        """^\s*(?:[-*>\s]*)(?:\*\*)?NEED_FILES:(?:\*\*)?\s*(.+?)\s*$""",
        RegexOption.IGNORE_CASE,
    )

    data class NeedFilesResult(
        /** Sanitized, deduplicated paths the app should try to provide. */
        val requested: List<String>,
        /** True when a NEED_FILES line is present at all. */
        val markerPresent: Boolean,
        /** Marker paths rejected by sanitization. */
        val invalidPaths: List<String>,
        /** Paths beyond [MAX_REQUESTED_FILES] (ignored). */
        val overflowPaths: List<String>,
    ) {
        val hasRequest: Boolean get() = markerPresent && requested.isNotEmpty()
    }

    private val EMPTY = NeedFilesResult(emptyList(), false, emptyList(), emptyList())

    fun parse(replyText: String): NeedFilesResult {
        if (replyText.isBlank()) return EMPTY
        var markerPresent = false
        val invalid = ArrayList<String>()
        val seen = LinkedHashSet<String>()
        val overflow = ArrayList<String>()

        replyText.lineSequence().forEach { raw ->
            val match = NEED_LINE.matchEntire(raw.trimEnd('\r')) ?: return@forEach
            markerPresent = true
            match.groupValues[1]
                .split(',', ';', '\n')
                .map { it.trim().trim('`', '*', '"', '\'') }
                .filter(String::isNotEmpty)
                .forEach { candidate ->
                    val sanitized = ZaiFileExtractor.sanitizeZaiPath(candidate)
                    if (sanitized == null) {
                        invalid += candidate
                    } else if (sanitized in seen) {
                        // duplicate — already queued
                    } else if (seen.size >= MAX_REQUESTED_FILES) {
                        overflow += sanitized
                    } else {
                        seen += sanitized
                    }
                }
        }
        if (!markerPresent) return EMPTY
        return NeedFilesResult(
            requested = seen.toList(),
            markerPresent = true,
            invalidPaths = invalid.distinct(),
            overflowPaths = overflow.distinct(),
        )
    }

    /**
     * Whether the reply should trigger an automatic fulfilment round:
     * a NEED_FILES request that does NOT also contain extracted FILE: blocks
     * (that would be an answer with files — treated as final), and paths
     * actually survived sanitization.
     */
    fun isAutoFulfillable(replyText: String): Boolean {
        val result = parse(replyText)
        if (!result.hasRequest) return false
        if (ZaiFileExtractor.parse(replyText).hasFiles) return false
        return true
    }
}
