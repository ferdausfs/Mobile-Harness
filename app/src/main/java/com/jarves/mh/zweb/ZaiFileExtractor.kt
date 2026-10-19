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
 * v1.0.34 — parsing the reply the app ALREADY captured. The finish-flow text
 * is the rendered DOM's `innerText`, where the site's markdown renderer has
 * CONSUMED the fence lines (``` disappears; the code content survives inside
 * `<pre><code>` elements, which the extractor script ships separately). The
 * real-phone evidence (v1.0.33: reply shown in chat with FILE: blocks, sync
 * found 0 files) is exactly this case. [parseCaptured] therefore resolves each
 * marker through three tiers:
 *  1. a fenced block after the marker (the original grammar — untouched);
 *  2. a code block whose first+last lines appear between the marker and the
 *     next marker (the rendered-DOM case; whitespace-tolerant matching);
 *  3. plain content: the lines after the marker up to the next FILE: marker
 *     or the end of the reply (files the model pasted without any fence).
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

    /**
     * One fenced code block as the DOM extraction reports it (language label +
     * exact text). Used by [parseCaptured] to pair markers with their content
     * when the captured text no longer carries fence lines.
     */
    data class CodeBlock(val language: String, val text: String)

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

    /** Markdown fences may use 3 or more backticks/tildes (CommonMark). */
    private val FENCE_OPEN = Regex("""^\s*(`{3,}|~{3,})\s*([A-Za-z0-9_+#./-]*)\s*$""")
    private val FENCE_CLOSE = Regex("""^\s*(`{3,}|~{3,})\s*$""")
    private val FILE_MARKER = Regex("""^\s*(?:\*\*)?FILE:(\*\*)?\s*(.+?)\s*(?:\*\*)?\s*$""", RegexOption.IGNORE_CASE)

    /** The reply protocol's "end of files" terminator for plain-content mode. */
    private val SUMMARY_TERMINATOR = Regex("""^\s*(?:\*\*)?Summary\s*:""", RegexOption.IGNORE_CASE)

    /** Convenience for plain-text sources with no DOM code blocks (manual sync). */
    fun parse(replyText: String): ZaiParseResult = parseCaptured(replyText, emptyList())

    fun parseCaptured(replyText: String, codeBlocks: List<CodeBlock>): ZaiParseResult {
        val files = ArrayList<ZaiExtractedFile>()
        val unmatched = ArrayList<String>()
        val invalid = ArrayList<String>()
        val seenPaths = HashSet<String>()

        var pendingPath: String? = null
        var pendingLanguage = ""
        var inFence = false
        var fenceChar = ""
        val fenceLines = ArrayList<String>()

        fun accept(path: String, language: String, content: String) {
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
                files.add(ZaiExtractedFile(path = sanitized, language = language, content = content))
            }
        }

        fun closeFence() {
            val path = pendingPath
            val content = fenceLines.joinToString("\n")
            fenceLines.clear()
            if (path != null) {
                accept(path, pendingLanguage, content)
                pendingPath = null
                pendingLanguage = ""
            }
        }

        val lines = replyText.lineSequence().map { it.trimEnd('\r') }.toList()
        val markerLineIndexes = ArrayList<Int>()
        lines.forEachIndexed { index, line ->
            if (!inFence && FILE_MARKER.matchEntire(line) != null) markerLineIndexes.add(index)
        }

        lines.forEachIndexed { index, rawLine ->
            val line = rawLine
            if (!inFence) {
                val open = FENCE_OPEN.matchEntire(line)
                if (open != null) {
                    inFence = true
                    fenceChar = open.groupValues[1]
                    pendingLanguage = open.groupValues[2]
                    fenceLines.clear()
                    return@forEachIndexed
                }
                val marker = FILE_MARKER.matchEntire(line)
                if (marker != null) {
                    val rawPath = marker.groupValues[2].trim()
                        .removePrefix("`").removeSuffix("`").trim()
                    if (rawPath.isNotEmpty()) {
                        // A marker whose fence never came is reported as unmatched.
                        if (pendingPath != null) unmatched.add(pendingPath!!)
                        pendingPath = rawPath
                        pendingLanguage = ""
                    }
                }
                // Any other prose line: leave pending marker armed (the model may
                // add one sentence between the marker and the block).
            } else {
                if (FENCE_CLOSE.matchEntire(line) != null && line.trim().startsWith(fenceChar.take(1))) {
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

        // ── v1.0.34 fallback tiers for markers the fence scan left behind ──
        resolveUnmatched(lines, markerLineIndexes, codeBlocks)?.let { resolved ->
            resolved.files.forEach { (_, rawPath, language, content) ->
                accept(rawPath, language, content)
                unmatched.removeIf { it == rawPath }
            }
            resolved.invalid.forEach { path ->
                if (invalid.none { it == path }) invalid.add(path)
            }
        }

        return ZaiParseResult(
            files = files,
            unmatchedMarkers = unmatched,
            invalidPaths = invalid,
        )
    }

    /** One marker resolved by the fallback tiers. */
    private data class ResolvedMarker(
        val lineIndex: Int,
        val rawPath: String,
        val language: String,
        val content: String,
    )

    private data class Resolution(
        val files: List<ResolvedMarker>,
        val invalid: List<String>,
    )

    /**
     * The v1.0.34 fallback: for every FILE: marker line that the fence scan did
     * NOT turn into a file (no fence follows), recover the content from
     *  1. a [codeBlocks] entry whose first and last lines occur (whitespace-
     *     tolerant) between this marker and the next one — the rendered-DOM
     *     case, where fences were consumed but `<pre><code>` blocks survive;
     *  2. otherwise the plain lines after the marker up to the next FILE:
     *     marker / end of reply, trailing blank lines trimmed.
     * Pure and deterministic: same input → same files, never throws.
     */
    private fun resolveUnmatched(
        lines: List<String>,
        markerLineIndexes: List<Int>,
        codeBlocks: List<CodeBlock>,
    ): Resolution? {
        if (markerLineIndexes.isEmpty()) return null
        val resolved = ArrayList<ResolvedMarker>()
        val invalid = ArrayList<String>()
        val usedBlocks = HashSet<Int>()

        for ((pos, markerIndex) in markerLineIndexes.withIndex()) {
            val markerLine = lines[markerIndex]
            val match = FILE_MARKER.matchEntire(markerLine) ?: continue
            val rawPath = match.groupValues[2].trim()
                .removePrefix("`").removeSuffix("`").trim()
            if (rawPath.isEmpty()) continue
            val end = if (pos + 1 < markerLineIndexes.size) markerLineIndexes[pos + 1] else lines.size

            // Was this marker already satisfied by a fence (tier 1)? A fenced
            // file appears in the fence scan output; we cannot see it here, so
            // only markers with no fence between marker and next marker go on.
            val hasFenceBetween = (markerIndex + 1 until end).any { lines[it].matches(FENCE_OPEN) }
            if (hasFenceBetween) continue

            // Tier 2: pair with a code block by first/last line.
            val paired = findPairedBlock(lines, markerIndex, end, codeBlocks, usedBlocks)
            if (paired != null) {
                usedBlocks += paired.second
                val sanitized = sanitizeZaiPath(rawPath)
                if (sanitized == null) {
                    invalid.add(rawPath)
                } else {
                    resolved += ResolvedMarker(
                        lineIndex = markerIndex,
                        rawPath = rawPath,
                        language = paired.first.language,
                        content = paired.first.text,
                    )
                }
                continue
            }

            // Tier 3: plain content until the next marker, the protocol's own
            // "Summary:" terminator (ZaiChatPromptBuilder teaches the model to
            // end with one), or the end of the reply; trailing blank lines are
            // trimmed. CRLF already stripped by the line scan.
            val bodyLines = ArrayList<String>()
            for (i in markerIndex + 1 until end) {
                val line = lines[i]
                if (SUMMARY_TERMINATOR.containsMatchIn(line)) break
                bodyLines.add(line)
            }
            while (bodyLines.isNotEmpty() && bodyLines.last().isBlank()) bodyLines.removeAt(bodyLines.size - 1)
            val content = bodyLines.joinToString("\n")
            if (content.isBlank()) continue // prose-only gap; honestly unmatched
            val sanitized = sanitizeZaiPath(rawPath)
            if (sanitized == null) {
                invalid.add(rawPath)
            } else {
                resolved += ResolvedMarker(
                    lineIndex = markerIndex,
                    rawPath = rawPath,
                    language = "",
                    content = content,
                )
            }
        }
        if (resolved.isEmpty() && invalid.isEmpty()) return null
        return Resolution(resolved, invalid)
    }

    /**
     * Finds a code block whose first and last non-blank lines appear, in order,
     * within the line window after [markerIndex] (exclusive) and [endIndex]
     * (exclusive). Line comparison collapses inner whitespace and ignores
     * leading/trailing blanks so small innerText vs textContent differences
     * cannot break the pairing.
     */
    private fun findPairedBlock(
        lines: List<String>,
        markerIndex: Int,
        endIndex: Int,
        codeBlocks: List<CodeBlock>,
        usedBlocks: Set<Int>,
    ): Pair<CodeBlock, Int>? {
        if (codeBlocks.isEmpty()) return null
        val window = lines.subList(minOf(markerIndex + 1, lines.size), minOf(endIndex, lines.size))
            .map { normalizeLine(it) }
            .filter { it.isNotEmpty() }
        if (window.isEmpty()) return null
        for ((blockIndex, block) in codeBlocks.withIndex()) {
            if (blockIndex in usedBlocks) continue
            val blockLines = block.text.lineSequence()
                .map { normalizeLine(it) }
                .filter { it.isNotEmpty() }
                .toList()
            if (blockLines.isEmpty()) continue
            val first = blockLines.first()
            val last = blockLines.last()
            val firstPos = window.indexOfFirst { it == first }
            if (firstPos < 0) continue
            if (blockLines.size == 1) return block to blockIndex
            val lastPos = window.indexOfLast { it == last }
            if (lastPos >= firstPos) return block to blockIndex
        }
        return null
    }

    private fun normalizeLine(line: String): String =
        line.trim().replace(Regex("\\s+"), " ")

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
