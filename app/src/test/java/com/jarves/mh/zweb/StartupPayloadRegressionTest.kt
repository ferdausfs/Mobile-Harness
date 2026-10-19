package com.jarves.mh.zweb

import com.jarves.mh.data.AppPreferencesBridge
import com.jarves.mh.data.ProviderUsageTracker
import com.jarves.mh.data.ProjectStoreJson
import com.jarves.mh.model.ProjectKind
import com.jarves.mh.model.ProviderKind
import com.jarves.mh.zweb.ZaiTaskQueue.Status
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * v1.0.31 startup-crash regression suite: REAL payloads saved by earlier
 * releases (v1.0.27-era model cache, v1.0.28-era usage/tokens metadata,
 * v1.0.29-era task queue) plus corrupt/hand-written variants are loaded into
 * the CURRENT loaders. Every load must (a) never throw and (b) fall back to
 * sane defaults — the mandate from the "app never opens after updating to
 * v1.0.30" report, where no logcat existed and every persisted loader had to
 * be provably unable to take the process down at startup.
 */
class StartupPayloadRegressionTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // ── v1.0.29-era task queue ──

    @Test
    fun `v1_0_29-era queue file with a RUNNING entry reconciles to a clean report`() {
        // Exact shape v1.0.29 wrote (toJson): id/projectId/chatId/title/prompt/
        // continueChat/status/enqueuedAt/attempts + optional startedAt/finishedAt/lastError.
        val v1_0_29_payload = """
            [
              {"id":"zq-1760000000000-1","projectId":"p1","chatId":"main","title":"Fix login",
               "prompt":"Fix the login page","continueChat":true,"status":"RUNNING",
               "enqueuedAt":1760000000000,"startedAt":1760000001000,"attempts":1},
              {"id":"zq-1760000000000-2","projectId":"p1","chatId":"main","title":"Add tests",
               "prompt":"Add tests","continueChat":false,"status":"PENDING","enqueuedAt":1760000002000,"attempts":0}
            ]
        """.trimIndent()
        val entries = ZaiTaskQueue.fromJson(v1_0_29_payload)
        assertEquals(2, entries.size)
        assertEquals(Status.RUNNING, entries[0].status)
        assertEquals(Status.PENDING, entries[1].status)

        // Startup reconcile (what setupZaiEngineForeground does with it): the
        // RUNNING entry reports a clean kill, the PENDING one (40s old, inside
        // the 10-minute window, sign-in unknown) stays KEEP_PENDING — never a throw.
        val result = ZaiTaskQueue.reconcile(entries, nowMillis = 1760000060000, signedIn = false)
        assertEquals(Status.FAILED, result.entries[0].status)
        assertEquals(1, result.reported.size)
        assertEquals(0, result.toResume.size)
        assertEquals(Status.PENDING, result.entries[1].status)
    }

    @Test
    fun `queue json with unknown status, missing fields and garbage never throws`() {
        val hostile = """
            [
              {"id":"zq-1","prompt":"p","status":"NOT_A_REAL_STATUS"},
              {"id":"","prompt":"p"},
              {"id":"zq-2","prompt":""},
              {"nope":true},
              "a string element",
              42
            ]
        """.trimIndent()
        val entries = ZaiTaskQueue.fromJson(hostile)
        // Only the first entry survives (id+prompt present); unknown status → PENDING.
        assertEquals(1, entries.size)
        assertEquals(Status.PENDING, entries[0].status)
        assertEquals("zq-1", entries[0].id)
        // Tolerant reads: every unreadable shape degrades to an empty queue.
        assertEquals(emptyList<ZaiTaskQueue.Entry>(), ZaiTaskQueue.fromJson("not json at all"))
        assertEquals(emptyList<ZaiTaskQueue.Entry>(), ZaiTaskQueue.fromJson(""))
        assertEquals(emptyList<ZaiTaskQueue.Entry>(), ZaiTaskQueue.fromJson(null))
        assertEquals(emptyList<ZaiTaskQueue.Entry>(), ZaiTaskQueue.fromJson("{\"not\":\"an array\"}"))
        assertEquals(emptyList<ZaiTaskQueue.Entry>(), ZaiTaskQueue.fromJson("[null]"))
    }

    @Test
    fun `queue store survives a corrupt file and round-trips`() {
        val file = File(tmp.newFolder(), "task-queue.json")
        val store = ZaiTaskQueueStore(file)
        // Corrupt on-disk state (interrupted write / old / garbage): empty load, no throw.
        file.writeText("{\"broken\":")
        assertTrue(store.load().isEmpty())
        file.writeText("null")
        assertTrue(store.load().isEmpty())
        // Round-trip.
        store.save(listOf(ZaiTaskQueue.Entry("zq-1", "p", "c", "t", "prompt", true, Status.PENDING, 5L)))
        val loaded = store.load()
        assertEquals(1, loaded.size)
        assertEquals("zq-1", loaded[0].id)
        assertTrue(loaded[0].continueChat)
    }

    // ── v1.0.27-era model catalog cache ──

    @Test
    fun `v1_0_27-era cached model list parses and junk falls back to empty`() {
        val cached = """[{"name":"GLM-5.3-Flash","description":"Lightweight flagship"},
                          {"name":"GLM-5.3","description":"Flagship model"}]"""
        val models = ZaiModelCatalog.fromJson(cached)
        assertEquals(listOf("GLM-5.3-Flash", "GLM-5.3"), models.map { it.name })
        // Hostile variants the pref could hold after an interrupted write.
        assertEquals(emptyList<ZaiModelCatalog.ModelOption>(), ZaiModelCatalog.fromJson("{"))
        assertEquals(emptyList<ZaiModelCatalog.ModelOption>(), ZaiModelCatalog.fromJson("[]"))
        assertEquals(emptyList<ZaiModelCatalog.ModelOption>(), ZaiModelCatalog.fromJson(""))
        assertEquals(emptyList<ZaiModelCatalog.ModelOption>(), ZaiModelCatalog.fromJson(null))
        assertEquals(emptyList<ZaiModelCatalog.ModelOption>(), ZaiModelCatalog.fromJson("""[{"noName":1}]"""))
        // v1.0.27 also persisted quoted-string eval results in some paths.
        assertTrue(ZaiModelCatalog.parse("\"[]\"").isEmpty())
    }

    // ── v1.0.30-era think controls probe result ──

    @Test
    fun `think-controls probe garbage hides the section instead of crashing`() {
        assertNull(ZaiThinkControls.parse(null))
        assertNull(ZaiThinkControls.parse(""))
        assertNull(ZaiThinkControls.parse("not json"))
        assertNull(ZaiThinkControls.parse("""{"found":false}"""))
        assertNull(ZaiThinkControls.parse("""{"found":true}"""))
        assertNull(ZaiThinkControls.parse("""{"found":true,"items":[]}"""))
        // A real signed-in read (bundle-verified shape) maps to controls.
        val controls = ZaiThinkControls.parse(
            """{"found":true,"url":"https://chat.z.ai","items":[{"text":"Deep Think","checked":"true"},{"text":"Low","checked":""},{"text":"High","checked":"true"}]}""",
        )
        assertTrue(controls?.hasAnything == true)
        assertTrue(controls?.autoThink == true)
    }

    // ── selector config (v3-era cache vs v4 bundled) ──

    @Test
    fun `v3-era cached selector config without v4 groups still parses`() {
        // v4 groups (thinkMenuButton/thinkMenuContent/thinkMenuItem) are OPTIONAL;
        // an old remote cache (or an old bundled asset) must keep working.
        val v3 = """
            {"version":3,"remoteUpdateUrl":"https://example.com/selectors.json",
             "page":{"startUrl":"https://chat.z.ai/"},
             "selectors":{
               "input":["#chat-input"],"sendButton":["#send-message-button"],
               "stopIndicator":["div[aria-label='Stop']"],"assistantMessage":[".chat-assistant"],
               "codeBlock":["pre code"],"loginMarker":["#login-marker"],
               "loginFormMarker":["#login-form"],"sidebarToggle":["#sidebar-toggle-button"],
               "signInButton":["button:has-text('Sign in')"],"captchaMarker":["#aliyunCaptcha-window-popup"]},
             "timing":{"pageLoadTimeoutMs":45000}}
        """.trimIndent()
        val config = ZaiWebConfig.fromJson(v3)
        assertEquals(3, config.version)
        assertTrue(config.selectors.containsKey("input"))
        assertFalse(config.selectors.containsKey("thinkMenuButton"))
    }

    @Test
    fun `selector config missing required groups throws the typed exception (loader catches it)`() {
        val broken = """{"version":9,"page":{"startUrl":"https://chat.z.ai/"},"selectors":{}}"""
        try {
            ZaiWebConfig.fromJson(broken)
            throw AssertionError("expected ZaiConfigException")
        } catch (expected: ZaiConfigException) {
            assertTrue(expected.message.orEmpty().contains("required groups"))
        }
        // And garbage of every shape the cache file could hold never escapes
        // as anything but the typed exception (ZaiConfigLoader catches Exception).
        try {
            ZaiWebConfig.fromJson("}}}not json")
            // org.json tolerates some garbage as literals — the contract the
            // loader relies on is: any failure is an Exception subtype.
        } catch (expected: Exception) {
            assertTrue(expected is ZaiConfigException || expected is org.json.JSONException)
        }
    }

    // ── v1.0.28-era provider usage payload ──

    @Test
    fun `v1_0_28-era usage json loads with sane defaults for new fields`() {
        val legacy = """
            {"day":"2026-10-18","providers":{"ANTHROPIC":{
               "dayRequests":3,"dayTasks":1,"dayIn":100,"dayOut":200,
               "totalRequests":30,"totalTasks":10,"totalIn":1000,"totalOut":2000,
               "state":"ACTIVE","lastMessage":"OK","lastUpdateAt":1760000000000}}}
        """.trimIndent()
        var saved: String? = legacy
        val tracker = ProviderUsageTracker(object : AppPreferencesBridge {
            override fun saveProviderUsage(json: String) { saved = json }
            override fun loadProviderUsage(): String? = saved
        })
        // init loaded the legacy payload without throwing; the snapshot exists.
        val field = ProviderUsageTracker::class.java.getDeclaredField("snapshots")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val snapshots = field.get(tracker) as Map<ProviderKind, Any>
        assertTrue(snapshots.containsKey(ProviderKind.ANTHROPIC))
        // Corrupt JSON → silently empty, never a throw.
        saved = "{not json"
        val tracker2 = ProviderUsageTracker(object : AppPreferencesBridge {
            override fun saveProviderUsage(json: String) {}
            override fun loadProviderUsage(): String? = saved
        })
        val field2 = ProviderUsageTracker::class.java.getDeclaredField("snapshots")
        field2.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val snapshots2 = field2.get(tracker2) as Map<ProviderKind, Any>
        assertTrue(snapshots2.isEmpty())
    }

    // ── project list (per-entry tolerance, v1.0.31 fix) ──

    @Test
    fun `one malformed project entry no longer erases the whole list`() {
        val payload = """
            [
              {"id":"a1","name":"Good","updatedAtMillis":1760000000000,"kind":"PROJECT"},
              {"id":"bad"},
              {"name":"No id","updatedAtMillis":1},
              {"id":"a2","name":"Also good","updatedAtMillis":1760000000000,"kind":"PROJECT"}
            ]
        """.trimIndent()
        val parsed = ProjectStoreJson.parse(payload)
        assertEquals(listOf("a1", "a2"), parsed.projects.map { it.id })
        assertTrue(parsed.needsSave)
    }

    @Test
    fun `v1_0_28-era project json without kind or slug is normalized`() {
        val payload = """[{"id":"p1","name":"My Project","updatedAtMillis":1760000000000}]"""
        val parsed = ProjectStoreJson.parse(payload)
        assertEquals(1, parsed.projects.size)
        assertEquals(ProjectKind.PROJECT, parsed.projects[0].kind)
        assertEquals("my-project", parsed.projects[0].slug)
        assertTrue(parsed.needsSave)
    }

    @Test
    fun `legacy QUICK_CHAT kind maps to QUICK_PROJECT`() {
        val payload = """[{"id":"q1","name":"Quick","updatedAtMillis":1760000000000,"kind":"QUICK_CHAT"}]"""
        val parsed = ProjectStoreJson.parse(payload)
        assertEquals(ProjectKind.QUICK_PROJECT, parsed.projects[0].kind)
    }

    @Test
    fun `project list garbage and hostile values fall back safely`() {
        assertTrue(ProjectStoreJson.parse(null).projects.isEmpty())
        assertTrue(ProjectStoreJson.parse("").projects.isEmpty())
        assertTrue(ProjectStoreJson.parse("not json").projects.isEmpty())
        assertTrue(ProjectStoreJson.parse("{\"object\":\"not array\"}").projects.isEmpty())
        // rootPath traversal is dropped, not fatal.
        val hostile = """[{"id":"h1","name":"H","updatedAtMillis":1760000000000,"rootPath":"../escape"}]"""
        assertEquals("", ProjectStoreJson.parse(hostile).projects[0].rootPath)
        // Missing timestamp backfills from the workspace probe.
        val backfilled = ProjectStoreJson.parse("""[{"id":"t1","name":"T"}]""") { 1234L }
        assertEquals(1234L, backfilled.projects[0].updatedAtMillis)
        assertTrue(backfilled.needsSave)
    }

    @Test
    fun `project store round-trips`() {
        val payload = """
            [{"id":"r1","name":"Round","description":"d","language":"kotlin","slug":"round",
              "rootPath":"workspaces/round","updatedAtMillis":1760000000000,"kind":"PROJECT"}]
        """.trimIndent()
        val parsed = ProjectStoreJson.parse(payload)
        assertEquals(1, parsed.projects.size)
        assertFalse(parsed.needsSave)
        val again = ProjectStoreJson.parse(ProjectStoreJson.toJson(parsed.projects))
        assertEquals(parsed.projects, again.projects)
        assertFalse(again.needsSave)
    }
}
