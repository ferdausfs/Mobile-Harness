package com.jarves.mh.zweb

import com.jarves.mh.zweb.ZaiFileExtractor.ZaiExtractedFile
import com.jarves.mh.zweb.ZaiFileSync.ProbePayload
import com.jarves.mh.zweb.ZaiFileSync.SiteEdit
import com.jarves.mh.zweb.ZaiFileSync.SiteVersionFile
import com.jarves.mh.zweb.ZaiFileSync.SiteWrite
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.0.33: the pure file-sync core — probe payload parsing, merge priority,
 * sandbox-path sanitization, binary sniffing, keep-both naming, and the
 * honesty rules the report card renders.
 */
class ZaiFileSyncTest {

    // ── parseProbe ──

    @Test
    fun `parseProbe reads a full payload`() {
        val json = """
            {"chatFound":true,"messageId":"m-1","writes":[{"path":"download/hello.txt","content":"hi"}],
             "edits":[{"path":"/home/z/my-project/src/App.tsx"}],
             "versionFiles":[{"key":"download/report.md","lastModified":"2026-10-19"}],
             "versionContents":{"download/report.md":"# report"},
             "binaryKeys":["download/logo.png"],"failedKeys":["download/big.zip"]}
        """.trimIndent()
        val payload = ZaiFileSync.parseProbe(json)
        assertTrue(payload.chatFound)
        assertEquals("m-1", payload.messageId)
        assertEquals(1, payload.writes.size)
        assertEquals("download/hello.txt", payload.writes[0].rawPath)
        assertEquals("hi", payload.writes[0].content)
        assertEquals(1, payload.edits.size)
        assertEquals(1, payload.versionFiles.size)
        assertEquals("# report", payload.versionContents["download/report.md"])
        assertEquals(listOf("download/logo.png"), payload.binaryKeys)
        assertEquals(listOf("download/big.zip"), payload.failedKeys)
        assertTrue(payload.sawSiteActivity)
    }

    @Test
    fun `parseProbe tolerates garbage and blank input`() {
        assertEquals(ZaiFileSync.emptyPayload().chatFound, ZaiFileSync.parseProbe(null).chatFound)
        assertFalse(ZaiFileSync.parseProbe("").chatFound)
        assertFalse(ZaiFileSync.parseProbe("not json at all").chatFound)
        assertFalse(ZaiFileSync.parseProbe("{broken").chatFound)
        // A quoted eval result unwraps fine.
        val payload = ZaiFileSync.parseProbe("\"{\\\"chatFound\\\":true}\"")
        assertTrue(payload.chatFound)
    }

    @Test
    fun `parseProbe keeps writes without content as null content`() {
        val payload = ZaiFileSync.parseProbe("""{"writes":[{"path":"a.txt"}]}""")
        assertEquals(1, payload.writes.size)
        assertNull(payload.writes[0].content)
    }

    // ── projectRelativePath ──

    @Test
    fun `sandbox prefixes are stripped to project-relative paths`() {
        assertEquals("hello.txt", ZaiFileSync.projectRelativePath("/home/z/my-project/download/hello.txt", true))
        assertEquals("notes/x.md", ZaiFileSync.projectRelativePath("/home/z/notes/x.md", true))
        assertEquals("src/App.tsx", ZaiFileSync.projectRelativePath("/home/z/my-project/src/App.tsx", true))
        // Windows separators normalize first.
        assertEquals("src/a.ts", ZaiFileSync.projectRelativePath("\\home\\z\\src\\a.ts", true))
    }

    @Test
    fun `deliverables download folder is stripped only for site-side paths`() {
        assertEquals("report.md", ZaiFileSync.projectRelativePath("download/report.md", true))
        assertEquals("report.md", ZaiFileSync.projectRelativePath("/home/z/my-project/download/report.md", true))
        // An explicit FILE: marker keeps its path verbatim.
        assertEquals("download/report.md", ZaiFileSync.projectRelativePath("download/report.md", false))
    }

    @Test
    fun `foreign absolute paths are rejected`() {
        assertNull(ZaiFileSync.projectRelativePath("/tmp/evil.txt", true))
        assertNull(ZaiFileSync.projectRelativePath("/etc/passwd", true))
        assertNull(ZaiFileSync.projectRelativePath("/workspace/escape.md", true))
        assertNull(ZaiFileSync.projectRelativePath("C:/Users/x.txt", true))
    }

    @Test
    fun `traversal and empty paths are rejected`() {
        assertNull(ZaiFileSync.projectRelativePath("../secret", true))
        assertNull(ZaiFileSync.projectRelativePath("a/../../b", true))
        assertNull(ZaiFileSync.projectRelativePath("download/", true))
        assertNull(ZaiFileSync.projectRelativePath("", true))
        assertNull(ZaiFileSync.projectRelativePath("/home/z/", true))
    }

    // ── merge priority + honesty ──

    private fun payload(
        writes: List<SiteWrite> = emptyList(),
        edits: List<SiteEdit> = emptyList(),
        versionFiles: List<SiteVersionFile> = emptyList(),
        versionContents: Map<String, String> = emptyMap(),
        binaryKeys: List<String> = emptyList(),
        failedKeys: List<String> = emptyList(),
        chatFound: Boolean = true,
    ) = ProbePayload(
        chatFound = chatFound, messageId = "m", note = null, turnSource = "chain",
        chatIdSource = "url", chatId = "test-chat-id",
        writes = writes, edits = edits, versionFiles = versionFiles,
        versionContents = versionContents, binaryKeys = binaryKeys, failedKeys = failedKeys,
    )

    @Test
    fun `versions content wins over tool write for the same path`() {
        val merged = ZaiFileSync.merge(
            payload(
                writes = listOf(SiteWrite("download/a.txt", "from tool")),
                versionFiles = listOf(SiteVersionFile("download/a.txt", "")),
                versionContents = mapOf("download/a.txt" to "from versions"),
            ),
            emptyList(),
        )
        assertEquals(listOf("a.txt"), merged.files.map { it.path })
        assertEquals("from versions", merged.files[0].content)
    }

    @Test
    fun `tool write fills paths the versions store does not have`() {
        val merged = ZaiFileSync.merge(
            payload(
                writes = listOf(SiteWrite("/home/z/my-project/download/hello.txt", "hi")),
            ),
            emptyList(),
        )
        assertEquals(listOf("hello.txt"), merged.files.map { it.path })
        assertEquals("hi", merged.files[0].content)
        assertEquals(1, merged.report.wroteCount)
        assertTrue(merged.report.readablePaths.contains("hello.txt"))
        assertFalse(merged.report.wroteButNoneReadable)
    }

    @Test
    fun `reply text FILE markers win over site files and the site fills the gaps`() {
        // v1.0.35 source order (user DO): 1) captured reply text, 2) site data.
        val merged = ZaiFileSync.merge(
            payload(writes = listOf(SiteWrite("a.txt", "site version"))),
            listOf(ZaiExtractedFile("a.txt", "", "text version"), ZaiExtractedFile("b.txt", "", "only in text")),
        )
        assertEquals(2, merged.files.size)
        assertEquals("text version", merged.files.first { it.path == "a.txt" }.content)
        assertEquals("only in text", merged.files.first { it.path == "b.txt" }.content)
    }

    @Test
    fun `site data still fills paths the reply text never mentioned`() {
        val merged = ZaiFileSync.merge(
            payload(
                writes = listOf(SiteWrite("download/a.txt", "from tool")),
                versionFiles = listOf(SiteVersionFile("download/a.txt", "")),
                versionContents = mapOf("download/a.txt" to "from versions"),
            ),
            listOf(ZaiExtractedFile("other.txt", "", "from reply")),
        )
        assertEquals("from reply", merged.files.first { it.path == "other.txt" }.content)
        assertEquals("from versions", merged.files.first { it.path == "a.txt" }.content)
    }

    @Test
    fun `edits without any content source are honestly unreadable`() {
        val merged = ZaiFileSync.merge(
            payload(edits = listOf(SiteEdit("/home/z/my-project/src/App.tsx"))),
            emptyList(),
        )
        assertTrue(merged.files.isEmpty())
        assertEquals(listOf("src/App.tsx"), merged.report.unreadablePaths)
        assertEquals(1, merged.report.wroteCount)
        assertTrue(merged.report.wroteButNoneReadable)
    }

    @Test
    fun `failed and binary version downloads land in the right buckets`() {
        val merged = ZaiFileSync.merge(
            payload(
                versionFiles = listOf(
                    SiteVersionFile("download/logo.png", ""),
                    SiteVersionFile("download/big.zip", ""),
                    SiteVersionFile("download/ok.txt", ""),
                ),
                versionContents = mapOf("download/ok.txt" to "fine"),
                binaryKeys = listOf("download/logo.png"),
                failedKeys = listOf("download/big.zip"),
            ),
            emptyList(),
        )
        assertEquals(listOf("ok.txt"), merged.files.map { it.path })
        assertEquals(listOf("big.zip"), merged.report.unreadablePaths)
        assertEquals(listOf("logo.png"), merged.report.binarySkipped)
    }

    @Test
    fun `binary-looking write content is skipped with a message`() {
        val binary = "PK\u0000\u0000\u0003\u0004\u0001\u0000" + "\u0001\u0002\u0003".repeat(8)
        val merged = ZaiFileSync.merge(
            payload(writes = listOf(SiteWrite("archive.zip", binary))),
            emptyList(),
        )
        assertTrue(merged.files.isEmpty())
        assertEquals(listOf("archive.zip"), merged.report.binarySkipped)
    }

    @Test
    fun `oversized write content is unreadable not silently dropped`() {
        val big = "x".repeat(ZaiFileSync.MAX_FILE_BYTES + 1)
        val merged = ZaiFileSync.merge(
            payload(writes = listOf(SiteWrite("big.txt", big))),
            emptyList(),
        )
        assertTrue(merged.files.isEmpty())
        assertEquals(listOf("big.txt"), merged.report.unreadablePaths)
        assertTrue(merged.report.sourceNotes.any { it.contains("too large") })
    }

    @Test
    fun `wroteCount counts everything the site touched exactly once`() {
        val merged = ZaiFileSync.merge(
            payload(
                writes = listOf(SiteWrite("a.txt", "x"), SiteWrite("b.txt", "y")),
                edits = listOf(SiteEdit("a.txt")), // duplicate path
                versionFiles = listOf(SiteVersionFile("download/c.bin", "")),
                binaryKeys = listOf("download/c.bin"),
            ),
            emptyList(),
        )
        assertEquals(3, merged.report.wroteCount)
        assertEquals(2, merged.report.readablePaths.size)
    }

    @Test
    fun `probe timeout with no site data and no markers is honest`() {
        val merged = ZaiFileSync.merge(ZaiFileSync.emptyPayload(), emptyList())
        assertEquals(0, merged.report.wroteCount)
        assertFalse(merged.report.wroteButNoneReadable)
        assertFalse(merged.report.probeOk)
        assertTrue(merged.files.isEmpty())
    }

    @Test
    fun `amber rule fires only when something was written and nothing was read`() {
        val wroteNothing = ZaiFileSync.merge(payload(chatFound = true), emptyList()).report
        assertFalse(wroteNothing.wroteButNoneReadable)

        val wroteAndRead = ZaiFileSync.merge(
            payload(writes = listOf(SiteWrite("a.txt", "x"))),
            emptyList(),
        ).report
        assertFalse(wroteAndRead.wroteButNoneReadable)

        val wroteButLost = ZaiFileSync.merge(
            payload(
                versionFiles = listOf(SiteVersionFile("download/a.txt", "")),
                failedKeys = listOf("download/a.txt"),
            ),
            emptyList(),
        ).report
        assertTrue(wroteButLost.wroteButNoneReadable)
    }

    // ── isBinarySample ──

    @Test
    fun `binary sniff catches NUL and control-char runs`() {
        assertTrue(ZaiFileSync.isBinarySample("PK\u0000\u0000zip"))
        assertTrue(ZaiFileSync.isBinarySample("\u0001\u0002\u0003\u0004\u0005\u0006\u0007"))
        assertFalse(ZaiFileSync.isBinarySample("hello world\nsecond line\ttab"))
        assertFalse(ZaiFileSync.isBinarySample(""))
        // Bengali / unicode text is not binary.
        assertFalse(ZaiFileSync.isBinarySample("ফাইল বানাও hi"))
    }

    // ── keepBothPath ──

    @Test
    fun `keep-both derives the first free sibling name`() {
        assertEquals("src/App-zai-1.tsx", ZaiFileSync.keepBothPath("src/App.tsx") { false })
        var n = 0
        assertEquals(
            "src/App-zai-3.tsx",
            ZaiFileSync.keepBothPath("src/App.tsx") { candidate ->
                n++
                n < 3 // App-zai-1 and App-zai-2 are taken
            },
        )
        assertEquals("Makefile-zai-1", ZaiFileSync.keepBothPath("Makefile") { it == "Makefile-zai-1" && false })
    }

    @Test
    fun `keep-both never returns a taken path`() {
        val taken = setOf("a-zai-1.txt", "a-zai-2.txt", "a-zai-3.txt")
        val result = ZaiFileSync.keepBothPath("a.txt") { it in taken }
        assertEquals("a-zai-4.txt", result)
    }

    // ── engine script contract (static) ──

    @Test
    fun `file sync probe follows the store-and-poll contract`() {
        val script = ZaiProbeScripts.buildFileSyncProbeScript()
        // Never returns a Promise completion value: an IIFE whose value is a
        // statement result, storing into window.__mhFileSync.
        assertTrue(script.contains("window.__mhFileSync"))
        assertTrue(script.contains("__mhFileSyncInFlight"))
        assertFalse(script.trimEnd().endsWith("};") && script.contains("return new Promise"))
        assertTrue(script.contains("/api/v1/chats/"))
        assertTrue(script.contains("/api/v1/storage/versions/"))
        assertTrue(script.contains("prefix=download"))
        // Authorization only via the same bearer pattern the auth probe uses.
        assertTrue(script.contains("Authorization"))
        val reader = ZaiProbeScripts.buildFileSyncResultReaderScript()
        assertEquals("window.__mhFileSync||null", reader.trim())
    }

    @Test
    fun `file sync probe handles the no-chat-id case synchronously`() {
        val script = ZaiProbeScripts.buildFileSyncProbeScript()
        assertTrue(script.contains("no chat id in URL"))
        assertNotNull(script)
    }

    // ── v1.0.34: tolerant turn matching + honesty verdicts + verified results ──

    @Test
    fun `probe payload carries the turn source for diagnostics`() {
        val json = """{"chatFound":true,"messageId":"m-2","turnSource":"chain","writes":[],"edits":[]}"""
        assertEquals("chain", ZaiFileSync.parseProbe(json).turnSource)
        assertNull(ZaiFileSync.parseProbe("""{"chatFound":true}""").turnSource)
        assertNull(ZaiFileSync.emptyPayload().turnSource)
    }

    @Test
    fun `file sync probe walks the site's parent chain and unwraps wrapped responses`() {
        val script = ZaiProbeScripts.buildFileSyncProbeScript()
        // The site's own model (bundle-verified): history.currentId leaf +
        // parentId chains — the primary turn matcher now walks the same chain.
        assertTrue(script.contains("history.currentId"))
        assertTrue(script.contains("parentId"))
        // Wrapped API responses ({data:{…}} / {chat:{…}}) are unwrapped.
        assertTrue(script.contains("c.data"))
        assertTrue(script.contains("c.chat"))
        // Timestamps may be numbers, numeric strings, or ISO strings.
        assertTrue(script.contains("function tsNum"))
        assertTrue(script.contains("Date.parse"))
        // A final fallback keeps trailing assistant messages in store order.
        assertTrue(script.contains("trailing"))
    }

    @Test
    fun `reply claims files from markers even when content was unreadable`() {
        // Unmatched markers (named but no content) still count as claims.
        val unmatchedOnly = ZaiFileExtractor.parseCaptured("FILE: missing.txt\n", emptyList())
        assertTrue(ZaiFileSync.replyClaimsFiles(unmatchedOnly, null))
        // Invalid paths count too (the model named them).
        val invalid = ZaiFileExtractor.parseCaptured("FILE: ../bad\ncontent", emptyList())
        assertTrue(ZaiFileSync.replyClaimsFiles(invalid, null))
        // A probe that saw site activity is a claim on its own.
        val plain = ZaiFileExtractor.parseCaptured("Just chatting.", emptyList())
        val report = ZaiFileSync.SyncReport(
            wroteCount = 2, readablePaths = emptyList(), unreadablePaths = listOf("a", "b"),
            binarySkipped = emptyList(), sourceNotes = emptyList(), probeOk = true, attempts = 2,
        )
        assertTrue(ZaiFileSync.replyClaimsFiles(plain, report))
        // No markers + no probe activity = no claim.
        assertFalse(ZaiFileSync.replyClaimsFiles(plain, null))
    }

    @Test
    fun `amber files-not-synced rule fires only when files were claimed and nothing arrived`() {
        // Claimed + nothing applied + nothing pending → amber (the v1.0.33 bug).
        assertTrue(ZaiFileSync.filesNotSyncedWarning(claimsFiles = true, appliedCount = 0, pendingReviewCount = 0))
        // Files waiting in the review card are one tap away — not a failure.
        assertFalse(ZaiFileSync.filesNotSyncedWarning(claimsFiles = true, appliedCount = 0, pendingReviewCount = 3))
        // Applied files mean the claim was fulfilled.
        assertFalse(ZaiFileSync.filesNotSyncedWarning(claimsFiles = true, appliedCount = 2, pendingReviewCount = 0))
        // No claim → never amber, green stays honest.
        assertFalse(ZaiFileSync.filesNotSyncedWarning(claimsFiles = false, appliedCount = 0, pendingReviewCount = 0))
    }

    @Test
    fun `verified results read back path and byte count from disk`() {
        val details = ZaiFileSync.verifyOnDisk(listOf("a.txt", "gone.txt")) { path ->
            when (path) {
                "a.txt" -> 15L
                else -> null
            }
        }
        assertEquals(2, details.size)
        assertEquals("a.txt", details[0].path)
        assertEquals(15L, details[0].bytes)
        assertTrue(details[0].verified)
        assertEquals(0L, details[1].bytes)
        assertFalse(details[1].verified)
    }

    @Test
    fun `byte sizes render for humans`() {
        assertEquals("15 B", ZaiFileSync.formatBytes(15))
        assertEquals("1.0 KB", ZaiFileSync.formatBytes(1024))
        assertEquals("1.2 MB", ZaiFileSync.formatBytes((1.2 * 1024 * 1024).toLong()))
        assertEquals("?", ZaiFileSync.formatBytes(-1))
    }
}
