package com.jarves.mh.zweb

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * v1.0.35 hotfix tests — the three real-phone problems:
 *  1. the nested-scroll crash (guarded separately in ComposeScrollGuardTest),
 *  2. file sync failing ("no chat id in URL", "reply could not be read",
 *     duplicate "Thinking..." lines),
 *  3. engine-independent tabs (contract already pinned by WorkspaceTabPolicy).
 *
 * This file covers the pure machinery: chat-id capture/persistence, the
 * reply-capture retry policy, capture diagnostics redaction, the honesty
 * failure reason, the thinking dedup, and the paste-import parse path.
 */
class ZaiChatIdTest {

    // ── extraction ──

    @Test
    fun `extracts the conversation id from page urls`() {
        assertEquals(
            "a7484009-782c-4eee-a6b7-9d37f2b80a53",
            ZaiChatId.fromUrl("https://chat.z.ai/c/a7484009-782c-4eee-a6b7-9d37f2b80a53"),
        )
        assertEquals("abc12345", ZaiChatId.fromUrl("https://chat.z.ai/c/abc12345?x=1"))
        assertNull(ZaiChatId.fromUrl("https://chat.z.ai/"))
        assertNull(ZaiChatId.fromUrl("https://chat.z.ai/auth"))
        assertNull(ZaiChatId.fromUrl(null))
        // too short → invalid
        assertNull(ZaiChatId.fromUrl("https://chat.z.ai/c/ab"))
    }

    @Test
    fun `extracts the conversation id from api resource urls`() {
        assertEquals(
            "a7484009-782c-4eee-a6b7-9d37f2b80a53",
            ZaiChatId.fromResourceUrl("https://chat.z.ai/api/v1/chats/a7484009-782c-4eee-a6b7-9d37f2b80a53"),
        )
        assertEquals(
            "a7484009-782c-4eee",
            ZaiChatId.fromResourceUrl("https://chat.z.ai/api/v1/chats/a7484009-782c-4eee/history?x=1"),
        )
        assertEquals("deadbeef-1234", ZaiChatId.fromResourceUrl("https://chat.z.ai/x?chat_id=deadbeef-1234"))
        assertNull(ZaiChatId.fromResourceUrl("https://chat.z.ai/api/v1/chats/new"))
        assertNull(ZaiChatId.fromResourceUrl("https://chat.z.ai/api/v1/models"))
    }

    @Test
    fun `pick prefers the first valid candidate`() {
        assertEquals("bbbbbbbb", ZaiChatId.pick(listOf(null, "", "bbbbbbbb", "cccccccc")))
        assertNull(ZaiChatId.pick(listOf(null, "", "  ")))
    }

    @Test
    fun `validity rejects junk`() {
        assertTrue(ZaiChatId.isValid("a7484009-782c-4eee"))
        assertFalse(ZaiChatId.isValid(null))
        assertFalse(ZaiChatId.isValid(""))
        assertFalse(ZaiChatId.isValid("short"))
        assertFalse(ZaiChatId.isValid("has space in it"))
        assertFalse(ZaiChatId.isValid("../../etc/passwd"))
    }

    // ── per-project map policy ──

    @Test
    fun `upsert overwrites the same app chat and prunes to the cap`() {
        var map = emptyList<ZaiChatId.Entry>()
        map = ZaiChatId.MapPolicy.upsert(map, "app-1", "zai-aaaaaaaa", 1000, "url")
        map = ZaiChatId.MapPolicy.upsert(map, "app-1", "zai-bbbbbbbb", 2000, "finish")
        assertEquals("zai-bbbbbbbb", ZaiChatId.MapPolicy.get(map, "app-1"))
        assertEquals(1, map.size)
        repeat(ZaiChatId.MapPolicy.MAX_ENTRIES + 5) { i ->
            map = ZaiChatId.MapPolicy.upsert(map, "app-$i", "zai-$i-12345678", 3000L + i, "probe")
        }
        assertEquals(ZaiChatId.MapPolicy.MAX_ENTRIES, map.size)
        // newest kept, oldest pruned (timestamps rise with i)
        assertNull(ZaiChatId.MapPolicy.get(map, "app-0"))
        assertNull(ZaiChatId.MapPolicy.get(map, "app-1"))
        assertEquals("zai-54-12345678", ZaiChatId.MapPolicy.get(map, "app-54"))
    }

    @Test
    fun `upsert ignores invalid ids and blank app chats`() {
        var map = emptyList<ZaiChatId.Entry>()
        map = ZaiChatId.MapPolicy.upsert(map, "app-1", "bad id with spaces", 1, "url")
        assertTrue(map.isEmpty())
        map = ZaiChatId.MapPolicy.upsert(map, "  ", "zai-aaaaaaaa", 1, "url")
        assertTrue(map.isEmpty())
    }

    // ── JSON round-trip + tolerance ──

    @Test
    fun `store json round-trips and tolerates garbage`() {
        val entries = listOf(
            ZaiChatId.Entry("app-1", "zai-aaaaaaaa", 1000, "url"),
            ZaiChatId.Entry("app-2", "zai-bbbbbbbb", 2000, "probe"),
        )
        val json = ZaiChatId.toJson(entries)
        assertEquals(entries, ZaiChatId.fromJson(json))
        // tolerant: garbage → empty, malformed rows skipped
        assertTrue(ZaiChatId.fromJson(null).isEmpty())
        assertTrue(ZaiChatId.fromJson("not json").isEmpty())
        assertTrue(ZaiChatId.fromJson("""{"entries":[{"appChatId":"a","zaiChatId":"has space"}]}""").isEmpty())
        val partial = ZaiChatId.fromJson(
            """{"entries":[{"appChatId":"ok","zaiChatId":"zai-ok-1234","updatedAtMillis":"1500"},
                {"appChatId":"","zaiChatId":"zai-x-123456"},{"nope":1}]}""",
        )
        assertEquals(1, partial.size)
        assertEquals("zai-ok-1234", partial[0].zaiChatId)
        assertEquals(1500L, partial[0].updatedAtMillis)
    }

    @Rule @JvmField
    val tmp = TemporaryFolder()

    @Test
    fun `store persists atomically and survives a fresh instance`() {
        val file = java.io.File(tmp.newFolder(), "zweb/chat-ids.json")
        val store = ZaiChatIdStore(file)
        store.upsert("app-1", "zai-aaaaaaaa", 1000, "url")
        val second = ZaiChatIdStore(file)
        assertEquals("zai-aaaaaaaa", second.get("app-1"))
        assertEquals(emptyList<ZaiChatId.Entry>(), ZaiChatIdStore(java.io.File(tmp.root, "missing.json")).load())
        // garbage on disk → empty, never throws
        file.writeText("{{{garbage")
        assertTrue(store.load().isEmpty())
    }
}

class ZaiReplyCapturePolicyTest {

    @Test
    fun `attempts stay inside the ~30s window`() {
        val attempts = ZaiReplyCapturePolicy.maxAttempts()
        assertTrue("expected several attempts, got $attempts", attempts in 3..8)
        var cumulative = 0L
        for (i in 0 until attempts) cumulative += ZaiReplyCapturePolicy.delayBeforeAttempt(i)
        assertTrue("total idle $cumulative exceeds the 30s window", cumulative <= ZaiReplyCapturePolicy.WINDOW_MILLIS + 5_000)
        assertEquals(0L, ZaiReplyCapturePolicy.delayBeforeAttempt(0))
        assertTrue(ZaiReplyCapturePolicy.delayBeforeAttempt(1) > 0)
        // out-of-range attempt → no delay (engine stops by maxAttempts anyway)
        assertEquals(0L, ZaiReplyCapturePolicy.delayBeforeAttempt(99))
    }

    @Test
    fun `final attempt flag is exact`() {
        val attempts = ZaiReplyCapturePolicy.maxAttempts()
        assertFalse(ZaiReplyCapturePolicy.isFinalAttempt(0))
        assertTrue(ZaiReplyCapturePolicy.isFinalAttempt(attempts - 1))
        assertTrue(ZaiReplyCapturePolicy.isFinalAttempt(attempts))
    }

    @Test
    fun `capture diag summary is redacted and informative`() {
        val ok = ZaiReplyCapturePolicy.CaptureDiag(
            attempt = 1, ok = true, assistantSelectorHit = 0, assistantElementCount = 4,
            stopPresent = false, textLength = 1234, urlPath = "/c/abc", why = null,
        )
        val s = ok.summary()
        assertTrue(s.contains("attempt 2"))
        assertTrue(s.contains("ok"))
        assertTrue(s.contains("1234"))
        assertTrue(s.contains("/c/abc"))
        // never carries reply content — only lengths/counts
        val failed = ZaiReplyCapturePolicy.CaptureDiag(
            attempt = 0, ok = false, assistantSelectorHit = null, assistantElementCount = 0,
            stopPresent = true, textLength = 0, urlPath = "/", why = "no-assistant",
        )
        val f = failed.summary()
        assertTrue(f.contains("failed"))
        assertTrue(f.contains("none"))
        assertTrue(f.contains("still visible"))
        assertTrue(f.contains("no-assistant"))
    }
}

class ZaiLiveThinkingDedupTest {

    @Test
    fun `label-only lines and adjacent duplicates collapse to one live thinking block`() {
        val raw = "Thinking…\nThinking…\nLooking at the workspace files…\nLooking at the workspace files…\nReading test/index.html"
        assertEquals(
            "Looking at the workspace files…\nReading test/index.html",
            ZaiLiveStatus.normalizeThinking(raw),
        )
    }

    @Test
    fun `real reasoning survives verbatim`() {
        val raw = "Step 1: read the file\nStep 2: write the file"
        assertEquals(raw, ZaiLiveStatus.normalizeThinking(raw))
    }

    @Test
    fun `parse drops a thinking payload that is only the label`() {
        val json = JSONObject().apply {
            put("thinkingText", "Thinking…")
            put("answerText", "Here is the file")
            put("stepText", "")
            put("stopPresent", true)
            put("chatId", "a7484009-782c-4eee")
        }.toString()
        val live = ZaiLiveStatus.parse(json)!!
        assertNull(live.thinking)
        assertEquals("Here is the file", live.answer)
    }

    @Test
    fun `parse keeps one deduplicated thinking body`() {
        val json = JSONObject().apply {
            put("thinkingText", "Thinking…\nReading the workspace…\nReading the workspace…")
            put("answerText", "")
            put("stopPresent", true)
        }.toString()
        val live = ZaiLiveStatus.parse(json)!!
        assertTrue(live.thinking!!.contains("Reading the workspace…"))
        assertFalse(live.thinking!!.contains("Thinking…"))
    }

    @Test
    fun `live status carries the conversation id when valid`() {
        val json = JSONObject().apply {
            put("thinkingText", "")
            put("stopPresent", false)
            put("chatId", "a7484009-782c-4eee-a6b7-9d37f2b80a53")
        }.toString()
        assertEquals("a7484009-782c-4eee-a6b7-9d37f2b80a53", ZaiLiveStatus.parseChatId(json))
        val junk = JSONObject().apply { put("chatId", "no spaces allowed here!!") }.toString()
        assertNull(ZaiLiveStatus.parseChatId(junk))
        assertNull(ZaiLiveStatus.parseChatId(null))
        assertNull(ZaiLiveStatus.parseChatId("{}"))
    }
}

class ZaiHonestyReasonTest {

    @Test
    fun `probe failure reason names the exact cause`() {
        // the probe's own note is the exact reason (verbatim, redacted by design)
        val noId = ZaiFileSync.emptyPayload().copy(
            note = "no chat id in URL, none stored",
            chatIdSource = "none",
        )
        assertEquals("no chat id in URL, none stored", ZaiFileSync.probeFailureReason(noId))
        // when the probe says nothing at all and no id exists → the honest rewrite
        val silentNoId = ZaiFileSync.emptyPayload().copy(chatIdSource = "none")
        assertTrue(ZaiFileSync.probeFailureReason(silentNoId)!!.startsWith("no conversation id"))
        // probe note passthrough (turn matching failed)
        val noTurn = ZaiFileSync.emptyPayload().copy(
            chatFound = true,
            note = "no assistant message found for this turn",
            turnSource = "chain",
        )
        assertEquals("no assistant message found for this turn", ZaiFileSync.probeFailureReason(noTurn))
        // generic unreadable
        assertEquals(
            "the site's file data could not be read right now",
            ZaiFileSync.probeFailureReason(ZaiFileSync.emptyPayload()),
        )
        // the probe found the chat (and said nothing) → no reason needed
        assertNull(ZaiFileSync.probeFailureReason(ZaiFileSync.emptyPayload().copy(chatFound = true)))
        // site activity counts as success
        val activity = ZaiFileSync.emptyPayload().copy(
            writes = listOf(ZaiFileSync.SiteWrite("/home/z/my-project/download/a.txt", "hi")),
        )
        assertNull(ZaiFileSync.probeFailureReason(activity))
    }

    @Test
    fun `parseProbe carries the chat id diagnostics`() {
        val json = """{"chatFound":true,"chatIdSource":"stored","chatId":"a7484009","writes":[],"edits":[]}"""
        val payload = ZaiFileSync.parseProbe(json)
        assertEquals("stored", payload.chatIdSource)
        assertEquals("a7484009", payload.chatId)
        assertNull(ZaiFileSync.parseProbe("""{"chatFound":true}""").chatIdSource)
    }
}

class ZaiProbeScriptContractV1035Test {

    private val config: ZaiWebConfig = ZaiWebConfig.fromJson(
        """
        {"version":99,
         "remoteUpdateUrl":"https://raw.githubusercontent.com/x/y/main/z.json",
         "page":{"startUrl":"https://chat.z.ai/","authProbePath":"/api/v1/auths/"},
         "selectors":{
           "input":["#chat-input","textarea[placeholder='Send a Message']"],
           "sendButton":["#send-message-button"],
           "stopIndicator":["div[aria-label='Stop']"],
           "assistantMessage":[".chat-assistant"],
           "codeBlock":[".chat-assistant pre code"],
           "loginMarker":["url-contains:/auth","text:=Welcome to Z.ai"],
           "loginFormMarker":["text:=Welcome to Z.ai"],
           "sidebarToggle":["#sidebar-toggle-button"],
           "signInButton":["text-button:=sign in"],
           "captchaMarker":["#aliyunCaptcha-window-popup.window-show"],
           "newChatButton":["#new-chat-button"]},
         "timing":{}}
        """.trimIndent(),
    )

    @Test
    fun `extract script returns redacted diagnostics on both outcomes`() {
        val js = ZaiProbeScripts.buildExtractScript(config)
        assertTrue(js.contains("diag"))
        assertTrue(js.contains("assistantSelectorHit"))
        assertTrue(js.contains("assistantCount"))
        assertTrue(js.contains("stopPresent"))
        assertTrue(js.contains("textLen"))
        assertTrue(js.contains("no-assistant"))
        // finish-time chat-id capture rides the same script
        assertTrue(js.contains("chatIdNow"))
        assertTrue(js.contains("getEntriesByType('resource')"))
        // synchronous IIFE (store-and-poll rule: no Promise completion value)
        assertFalse(js.contains("new Promise"))
    }

    @Test
    fun `live status script strips the summary label and captures the chat id`() {
        val js = ZaiProbeScripts.buildLiveStatusScript(config)
        // summary removal (duplicate-"Thinking" fix at the source)
        assertTrue(js.contains("cloneNode"))
        assertTrue(js.contains("removeChild"))
        // chat-id channel: URL match + the site's own chat-detail fetches
        assertTrue(js.contains("chatId"))
        assertTrue(js.contains("location.pathname.match"))
        assertTrue(js.contains("getEntriesByType('resource')"))
        assertFalse(js.contains("new Promise"))
    }

    @Test
    fun `file sync probe uses the stored id first and records its source`() {
        val withId = ZaiProbeScripts.buildFileSyncProbeScript("a7484009-782c-4eee")
        assertTrue(withId.contains("\"a7484009-782c-4eee\""))
        assertTrue(withId.contains("idSource='stored'"))
        val without = ZaiProbeScripts.buildFileSyncProbeScript(null)
        assertTrue(without.contains("var explicit=null"))
        assertTrue(without.contains("idSource='url'"))
        assertTrue(without.contains("idSource='perf'"))
        assertTrue(without.contains("no chat id in URL, none stored"))
        assertTrue(without.contains("chatIdSource"))
        // dangerous ids are JSON-quoted, never raw JS
        val quoted = ZaiProbeScripts.buildFileSyncProbeScript("x\"};alert(1);{\"")
        assertTrue(quoted.contains("\"x\\\"};alert(1);{\\\"\""))
    }
}

class ZaiPasteImportParseTest {

    /**
     * The paste-import path parses the SAME grammar as automatic capture.
     * This is the user-facing guarantee: what the app cannot read can be
     * pasted, and the exact same sanitizer/review pipeline applies.
     */
    @Test
    fun `pasted reply with FILE blocks parses all three files`() {
        val pasted = """
            ঠিক আছে! তিনটি ফাইল তৈরি হয়েছে।

            FILE: test/hello.txt
            ```text
            hello from zai
            ```

            FILE: test/index.html
            ```html
            <!DOCTYPE html><html><body><h1>Hi</h1></body></html>
            ```

            FILE: test/style.css
            ```css
            body { color: red; }
            ```

            Summary: done
        """.trimIndent()
        val parse = ZaiFileExtractor.parseCaptured(pasted, emptyList())
        assertEquals(listOf("test/hello.txt", "test/index.html", "test/style.css"), parse.files.map { it.path })
        assertEquals("hello from zai", parse.files[0].content.trim())
        assertTrue(parse.files[1].content.contains("<!DOCTYPE html>"))
        assertTrue(parse.files[2].content.contains("body { color: red; }"))
        assertTrue(parse.hasFiles)
    }

    @Test
    fun `pasted reply without usable markers yields an honest empty parse`() {
        val parse = ZaiFileExtractor.parseCaptured("I created your files successfully!", emptyList())
        assertFalse(parse.hasFiles)
        assertTrue(parse.files.isEmpty())
    }
}
