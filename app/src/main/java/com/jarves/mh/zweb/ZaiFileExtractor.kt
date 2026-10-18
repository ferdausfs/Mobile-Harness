package com.jarves.mh.zweb

/**
 * Parses a chat.z.ai assistant reply into extracted files.
 *
 * The reply is expected (per [ZaiPromptBuilder]) to mark files with a
 * `FILE: relative/path` line immediately followed by a fenced code block holding
 * the complete file content. Parsing is a pure line scan over the flattened
 * markdown text of the last assistant message, so it works regardless of how the
 * web UI renders the reply.
 *
 * Grammar handled:
 *  - fence open:  a line whose first non-space characters are ``` (info string = language)
 *  - fence close: the next line that is only ``` (possibly padded with spaces)
 *  - marker:      `FILE:` + path, case-insensitive on the key, outside any fence,
 *                 and still pending when the next fence opens
 *
 * Files whose path fails [sanitizeZaiPath] are skipped (listed in [invalidPaths]
 * for user-visible feedback) rather than written to disk.
 */
object ZaiFileExtractor {

    /** One file announced by a `FILE:` marker and captured from the fence under it. */
    data class ZaiExtractedFile(
        val path: String,
        val language: String,
        val content: String,
    )

    data class ZaiParseResult(
        val files: List<ZaiExtractedFile>,
        /** Marker lines that named a file but whose fence was never found (assistant stopped early). */
        val unmatchedMarkers: List<String>,
        /** Marker paths rejected by sanitization (absolute, traversal, control chars). */
        val invalidPaths: List<String>,
    ) {
        val hasFiles: Boolean get() = files.isNotEmpty()
    }

    const val MAX_FILES = 50
    const val MAX_FILE_BYTES = 2 * 1024 * 1024

    private val FENCE_OPEN = Regex("""^\s*(```|~~~)\s*([A-Za-z0-9_+#./-]*)\s*$""")
    private val FENCE_CLOSE = Regex("""^\s*(```|~~~)\s*$""")
    private val FILE_MARKER = Regex("""^\s*(?:\*\*)?FILE:(\*\*)?\s*(.+?)\s*(?:\*\*)?\s*$""", RegexOption.IGNORE_CASE)

    fun parse(replyText: String): ZaiParseResult {
        val files = ArrayList<ZaiExtractedFile>()
        val unmatched = ArrayList<String>()
        val invalid = ArrayList<String>()
        val seenPaths = HashSet<String>()

        var pendingPath: String? = null
        var pendingLanguage = ""
        var inFence = false
        var fenceChar = ""
        val fenceLines = ArrayList<String>()

        fun closeFence() {
            val path = pendingPath
            val content = fenceLines.joinToString("\n")
            fenceLines.clear()
            if (path != null) {
                val sanitized = sanitizeZaiPath(path)
                if (sanitized == null) {
                    invalid.add(path)
                } else if (content.toByteArray(Charsets.UTF_8).size > MAX_FILE_BYTES) {
                    invalid.add(path)
                } else if (sanitized in seenPaths) {
                    // Same path announced twice: first complete block wins.
                } else if (files.size >= MAX_FILES) {
                    invalid.add(path)
                } else {
                    seenPaths.add(sanitized)
                    files.add(ZaiExtractedFile(path = sanitized, language = pendingLanguage, content = content))
                }
                pendingPath = null
                pendingLanguage = ""
            }
        }

        replyText.lineSequence().forEach { rawLine ->
            val line = rawLine.trimEnd('\r')
            if (!inFence) {
                val open = FENCE_OPEN.matchEntire(line)
                if (open != null) {
                    inFence = true
                    fenceChar = open.groupValues[1]
                    pendingLanguage = open.groupValues[2]
                    fenceLines.clear()
                    return@forEach
                }
                val marker = FILE_MARKER.matchEntire(line)
                if (marker != null) {
                    val rawPath = marker.groupValues[2].trim()
                        .removePrefix("`").removeSuffix("`").trim()
                    if (rawPath.isNotEmpty()) {
                        // A marker whose fence never came is reported as unmatched.
                        if (pendingPath != null) unmatched.add(pendingPath!!)
                        pendingPath = rawPath
                    }
                }
                // Any other prose line: leave pending marker armed (the model may
                // add one sentence between the marker and the block).
            } else {
                if (FENCE_CLOSE.matchEntire(line) != null && line.trim().startsWith(fenceChar)) {
                    inFence = false
                    closeFence()
                } else {
                    fenceLines.add(line)
                }
            }
        }
        // Unterminated fence at end of reply: keep content (assistants sometimes get cut off).
        if (inFence) closeFence()
        if (pendingPath != null) unmatched.add(pendingPath!!)

        return ZaiParseResult(
            files = files,
            unmatchedMarkers = unmatched,
            invalidPaths = invalid,
        )
    }

    /**
     * Normalizes a model-supplied path for safe writing under the app's private
     * storage. Returns null for anything risky: absolute paths, `..` traversal,
     * control characters, empty or over-long paths, Windows drive prefixes.
     */
    fun sanitizeZaiPath(raw: String): String? {
        val cleaned = raw
            .replace('\\', '/')
            .trim()
            .trimStart('/')
            .removePrefix("./")
        if (cleaned.isEmpty() || cleaned.length > 200) return null
        if (cleaned.any { it.code < 0x20 || it.code == 0x7F }) return null
        if (Regex("^[A-Za-z]:").containsMatchIn(cleaned)) return null
        val segments = cleaned.split('/')
        if (segments.any { it == ".." }) return null
        val normalized = segments.filter { it.isNotEmpty() && it != "." }.joinToString("/")
        if (normalized.isEmpty() || normalized == "/" || normalized.endsWith("/")) return null
        if (normalized.startsWith("/")) return null
        return normalized
    }
}
