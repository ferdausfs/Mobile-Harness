package com.jarves.mh.zweb

import com.jarves.mh.zweb.ZaiFileExtractor.ZaiExtractedFile
import org.json.JSONArray
import org.json.JSONObject

/**
 * v1.0.33 file sync: turns what Z.ai wrote on chat.z.ai into project-ready
 * files.
 *
 * WHY THIS EXISTS (real-phone evidence, v1.0.32): Z.ai on chat.z.ai now works
 * in its OWN sandbox. A task like "create hello.txt with hi inside" produced a
 * tool trace ("Explored 1 files", "Wrote 1 files") and the file landed at
 * `/home/z/my-project/download/hello.txt` inside Z.ai's sandbox — the app saw
 * "Task finished · 0 rounds, 0 commands" and NOTHING reached the project,
 * because the old pipeline only parsed `FILE:` markers out of the reply text.
 *
 * WHERE THE FILES COME FROM (live probe 2026-10-19, prod bundle prod-fe-1.1.107):
 *  - `GET /api/v1/chats/{chatId}` — the chat's messages carry structured
 *    blocks; `tool_calls` blocks hold every Write/Edit/MultiEdit call with its
 *    arguments (`Write` → `file_path` + full `content`). VERIFIED live for the
 *    chat-detail endpoint (guest probe returned the message map); the tool-call
 *    block shape is bundle-verified (display config maps Write to
 *    `$.file_path` + `$.content`).
 *  - `GET /api/v1/storage/versions/{messageId}/files?prefix=download&max_keys=`
 *    — the site's own "download results" listing (bundle-verified call sites,
 *    response shape `{success, data:{files:[{key,last_modified}]}}`). Each file
 *    downloads from `GET /api/v1/storage/versions/{messageId}/file?path={key}`
 *    (text-ish content types arrive as text, binaries as blobs — bundle-
 *    verified `fM`). GUESSED on the guest session only insofar as the endpoint
 *    answered 403 "share_id is required for guest access", which proves the
 *    route + auth shape; real signed-in verification happens on the phone.
 *
 * This object is PURE: the engine runs the store-and-poll probe script, hands
 * the stored JSON string here, and gets the merged candidate files plus an
 * honest report (what Z.ai wrote vs what could actually be read).
 */
object ZaiFileSync {

    // ── Probe payload (parsed from the page-stored JSON) ──

    data class SiteWrite(val rawPath: String, val content: String?)

    data class SiteEdit(val rawPath: String)

    data class SiteVersionFile(val rawKey: String, val lastModified: String?)

    data class ProbePayload(
        val chatFound: Boolean,
        /** The last assistant message id the versions listing was keyed on (null = none). */
        val messageId: String?,
        val note: String?,
        /** How the turn was matched: "chain" (the site's parent walk) or "fallback". */
        val turnSource: String?,
        val writes: List<SiteWrite>,
        val edits: List<SiteEdit>,
        val versionFiles: List<SiteVersionFile>,
        /** Downloaded TEXT content keyed by the RAW version key (not the project path). */
        val versionContents: Map<String, String>,
        val binaryKeys: List<String>,
        val failedKeys: List<String>,
    ) {
        val sawSiteActivity: Boolean
            get() = writes.isNotEmpty() || edits.isNotEmpty() || versionFiles.isNotEmpty()
    }

    fun emptyPayload(): ProbePayload = ProbePayload(
        chatFound = false, messageId = null, note = null, turnSource = null,
        writes = emptyList(), edits = emptyList(), versionFiles = emptyList(),
        versionContents = emptyMap(), binaryKeys = emptyList(), failedKeys = emptyList(),
    )

    /**
     * Tolerant parse of the probe's stored JSON. Anything missing/malformed
     * degrades to an empty field — a probe that read half the data still
     * contributes what it has. Never throws.
     */
    fun parseProbe(raw: String?): ProbePayload {
        if (raw.isNullOrBlank()) return emptyPayload()
        return runCatching {
            val root = JSONObject(raw.unquoteEval())
            val writes = ArrayList<SiteWrite>()
            root.optJSONArray("writes")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val w = arr.optJSONObject(i) ?: continue
                    val path = w.optString("path").trim()
                    if (path.isEmpty()) continue
                    val content = if (w.isNull("content")) null else w.optString("content")
                    writes += SiteWrite(path, content)
                }
            }
            val edits = ArrayList<SiteEdit>()
            root.optJSONArray("edits")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val e = arr.optJSONObject(i) ?: continue
                    val path = e.optString("path").trim()
                    if (path.isNotEmpty()) edits += SiteEdit(path)
                }
            }
            val versionFiles = ArrayList<SiteVersionFile>()
            root.optJSONArray("versionFiles")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val v = arr.optJSONObject(i) ?: continue
                    val key = v.optString("key").trim()
                    if (key.isEmpty()) continue
                    versionFiles += SiteVersionFile(key, v.optString("lastModified"))
                }
            }
            val versionContents = LinkedHashMap<String, String>()
            root.optJSONObject("versionContents")?.let { obj ->
                obj.keys().forEach { key ->
                    val value = obj.optString(key)
                    if (value.isNotEmpty()) versionContents[key] = value
                }
            }
            val binary = stringList(root, "binaryKeys")
            val failed = stringList(root, "failedKeys")
            ProbePayload(
                chatFound = root.optBoolean("chatFound"),
                messageId = root.optString("messageId").takeIf { it.isNotEmpty() },
                note = root.optString("note").takeIf { it.isNotEmpty() },
                turnSource = root.optString("turnSource").takeIf { it.isNotEmpty() },
                writes = writes,
                edits = edits,
                versionFiles = versionFiles,
                versionContents = versionContents,
                binaryKeys = binary,
                failedKeys = failed,
            )
        }.getOrDefault(emptyPayload())
    }

    private fun stringList(root: JSONObject, key: String): List<String> {
        val arr = root.optJSONArray(key) ?: return emptyList()
        val out = ArrayList<String>(arr.length())
        for (i in 0 until arr.length()) {
            val s = arr.optString(i)
            if (s.isNotEmpty()) out += s
        }
        return out
    }

    // ── Merge + honesty report ──

    enum class Source { VERSIONS, TOOL_WRITE, TEXT_MARKER }

    /**
     * The user-facing truth after a sync attempt. [wroteButNoneReadable] drives
     * the amber warning (a task that says "wrote files" must never render a
     * green "synced" state when nothing actually arrived).
     */
    data class SyncReport(
        val wroteCount: Int,
        val readablePaths: List<String>,
        val unreadablePaths: List<String>,
        val binarySkipped: List<String>,
        val sourceNotes: List<String>,
        val probeOk: Boolean,
        val attempts: Int,
    ) {
        /** Z.ai wrote files but NONE could be read — amber, never green. */
        val wroteButNoneReadable: Boolean
            get() = wroteCount > 0 && readablePaths.isEmpty()
    }

    data class Merged(
        val files: List<ZaiExtractedFile>,
        val report: SyncReport,
    )

    // ── v1.0.34: honesty verdict for the final report card ──

    /**
     * Whether the reply itself claims files: any FILE: marker the parser turned
     * into a file, OR named without content (unmatched), OR rejected as unsafe.
     * Also true when the site probe saw sandbox writes/edits/version files —
     * positive evidence Z.ai produced files somewhere.
     */
    fun replyClaimsFiles(
        parse: ZaiFileExtractor.ZaiParseResult,
        report: SyncReport?,
    ): Boolean {
        val markerClaims = parse.files.isNotEmpty() ||
            parse.unmatchedMarkers.isNotEmpty() ||
            parse.invalidPaths.isNotEmpty()
        val probeClaims = report != null && report.wroteCount > 0
        return markerClaims || probeClaims
    }

    /**
     * The amber rule for the final report card (DO: "never green Task finished
     * when the reply claims files were created but 0 were synced").
     *
     * True only when Z.ai (or the reply) claimed files AND nothing is on its way
     * into the project: nothing applied, nothing waiting in the review card.
     * A pending review card is NOT a failure — the files are one tap away — so
     * the card stays green while that choice is open.
     */
    fun filesNotSyncedWarning(
        claimsFiles: Boolean,
        appliedCount: Int,
        pendingReviewCount: Int,
    ): Boolean = claimsFiles && appliedCount <= 0 && pendingReviewCount <= 0

    // ── v1.0.34: verified per-file results (read back from disk) ──

    /** One successfully synced file, verified by reading the bytes back. */
    data class SyncedFileDetail(val path: String, val bytes: Long, val verified: Boolean)

    /**
     * Reads every applied path back from the workspace and reports its byte
     * size (the DO: "verify by reading back from disk (path + byte count)").
     * A missing/unreadable file is [SyncedFileDetail.verified] = false — never
     * silently presented as synced. Pure over the [readBytes] lambda so the JVM
     * can test the verdicts without a filesystem.
     */
    fun verifyOnDisk(paths: List<String>, readBytes: (String) -> Long?): List<SyncedFileDetail> =
        paths.map { path ->
            val size = readBytes(path)
            SyncedFileDetail(path = path, bytes = size ?: 0L, verified = size != null)
        }

    /** "12 B", "4.0 KB", "1.2 MB" — for the per-file result rows. */
    fun formatBytes(bytes: Long): String = when {
        bytes < 0 -> "?"
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> String.format(java.util.Locale.US, "%.1f KB", bytes / 1024.0)
        else -> String.format(java.util.Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
    }

    fun merge(payload: ProbePayload, textMarkers: List<ZaiExtractedFile>): Merged {
        // path -> (source, content); insertion order = discovery order.
        val files = LinkedHashMap<String, Pair<Source, String>>()
        val unreadable = LinkedHashSet<String>()
        val binarySkipped = LinkedHashSet<String>()
        val notes = ArrayList<String>()
        if (payload.note != null) notes += payload.note

        fun put(path: String, source: Source, content: String) {
            if (files.containsKey(path)) return
            if (content.toByteArray(Charsets.UTF_8).size > MAX_FILE_BYTES) {
                unreadable += path
                notes += "file too large to sync: $path"
                return
            }
            if (isBinarySample(content)) {
                binarySkipped += path
                return
            }
            files[path] = source to content
        }

        // 1. The site's own version storage is the FINAL sandbox state — wins.
        payload.versionFiles.forEach { vf ->
            val path = projectRelativePath(vf.rawKey, stripDeliverablesDir = true)
            if (path == null) {
                notes += "skipped unsafe path: ${vf.rawKey.take(80)}"
                return@forEach
            }
            val content = payload.versionContents[vf.rawKey]
            when {
                content != null -> put(path, Source.VERSIONS, content)
                payload.binaryKeys.contains(vf.rawKey) -> binarySkipped += path
                else -> unreadable += path // failed or over-budget download
            }
        }

        // 2. Write tool calls carry the full content inline.
        payload.writes.forEach { w ->
            val path = projectRelativePath(w.rawPath, stripDeliverablesDir = true)
            if (path == null) {
                notes += "skipped unsafe path: ${w.rawPath.take(80)}"
                return@forEach
            }
            val content = w.content
            when {
                files.containsKey(path) -> return@forEach
                content == null -> unreadable += path
                else -> put(path, Source.TOOL_WRITE, content)
            }
        }

        // 3. Edits change files without carrying the full content: if the
        //    versions store did not hand us the file, it is honestly unreadable.
        payload.edits.forEach { e ->
            val path = projectRelativePath(e.rawPath, stripDeliverablesDir = true) ?: return@forEach
            if (!files.containsKey(path)) unreadable += path
        }

        // 4. FILE: text markers stay the lowest-priority source (still fully
        //    supported — the model may paste files it did NOT sandbox).
        textMarkers.forEach { marker ->
            if (!files.containsKey(marker.path)) {
                put(marker.path, Source.TEXT_MARKER, marker.content)
            }
        }

        val wrote = LinkedHashSet<String>()
        wrote += files.keys
        wrote += unreadable
        wrote += binarySkipped
        val report = SyncReport(
            wroteCount = wrote.size,
            readablePaths = files.keys.toList(),
            unreadablePaths = unreadable.toList(),
            binarySkipped = binarySkipped.toList(),
            sourceNotes = notes.take(6),
            probeOk = payload.chatFound || textMarkers.isNotEmpty(),
            attempts = payload.writes.size + payload.versionFiles.size,
        )
        return Merged(
            files = files.map { (path, pair) -> ZaiExtractedFile(path, "", pair.second) },
            report = report,
        )
    }

    // ── Paths ──

    const val MAX_FILE_BYTES = ZaiFileExtractor.MAX_FILE_BYTES

    /** Sandbox-root prefixes Z.ai's own tools write under (live evidence). */
    private val SANDBOX_PREFIXES = listOf("/home/z/my-project/", "/home/z/")

    /**
     * Turns a site-side path into a safe project-relative path.
     *
     *  - `/home/z/my-project/download/hello.txt` → `hello.txt`
     *  - `/home/z/notes/x.md` → `notes/x.md`
     *  - `download/report.md` → `report.md` (the site's deliverables folder —
     *    bundle-verified `prefix:"download"` listing; the phone evidence saved
     *    hello.txt under it) — only when [stripDeliverablesDir] is set, which
     *    is true for tool/versions paths. An explicit `FILE:` marker keeps its
     *    path verbatim: the model chose it deliberately.
     *  - any OTHER absolute path is REJECTED (null) per the safety rule
     *    (only the known sandbox roots are stripped — never `/tmp`, `/etc`, …).
     *  - relative paths go through [ZaiFileExtractor.sanitizeZaiPath]
     *    (traversal, control chars, Windows drives, length caps).
     */
    fun projectRelativePath(raw: String, stripDeliverablesDir: Boolean): String? {
        var p = raw.trim().replace('\\', '/')
        if (p.startsWith("/")) {
            val stripped = SANDBOX_PREFIXES.firstOrNull { p.startsWith(it) }?.let { p.substring(it.length) }
                ?: return null
            p = stripped
        }
        if (stripDeliverablesDir) p = p.removePrefix("download/")
        return ZaiFileExtractor.sanitizeZaiPath(p)
    }

    /**
     * Cheap binary sniff for text that arrived over a text channel: a NUL byte
     * or a run of exotic control characters means the "text" is really binary
     * (or a corrupt decode) — skip it with a clear message instead of writing
     * garbage into the project.
     */
    fun isBinarySample(sample: String): Boolean {
        if (sample.isEmpty()) return false
        if (sample.indexOf('\u0000') >= 0) return true
        val n = minOf(sample.length, 4096)
        var exotic = 0
        for (i in 0 until n) {
            val code = sample[i].code
            if (code in 1..8 || code in 14..31) exotic++
        }
        return exotic * 20 > n
    }

    /**
     * "Keep both" target for a conflicting path: `src/App.tsx` conflicts →
     * `src/App-zai-1.tsx` (first free number). [isTaken] answers whether a
     * candidate path already exists (on disk or among the other planned files).
     */
    fun keepBothPath(path: String, isTaken: (String) -> Boolean): String {
        val dot = path.lastIndexOf('.')
        val base = if (dot > 0) path.substring(0, dot) else path
        val ext = if (dot > 0) path.substring(dot) else ""
        for (n in 1..999) {
            val candidate = "$base-zai-$n$ext"
            if (!isTaken(candidate)) return candidate
        }
        return "$base-zai-${System.currentTimeMillis()}$ext"
    }

    private fun String.unquoteEval(): String {
        val trimmed = trim()
        if (trimmed.length >= 2 && trimmed.first() == '"' && trimmed.last() == '"') {
            return runCatching {
                JSONArray("[$trimmed]").getString(0)
            }.getOrDefault(trimmed)
        }
        return trimmed
    }
}
