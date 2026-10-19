package com.jarves.mh.zweb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

class ZaiWebConfigTest {

    private fun bundledAssetFile(): File {
        // Gradle runs JVM unit tests with the working directory at the app module.
        val f = File("src/main/assets/zweb/selectors.json")
        if (!f.isFile) fail("Bundled selector asset not found at ${f.absolutePath} — tests must verify the real shipped config")
        return f
    }

    private fun parseBundled(): ZaiWebConfig = ZaiWebConfig.fromJson(bundledAssetFile().readText())

    @Test
    fun `bundled asset parses and carries all required selector groups`() {
        val config = parseBundled()
        ZaiWebConfig.REQUIRED_GROUPS.forEach { group ->
            assertTrue("group $group must exist in the bundled config", config.selectorsFor(group).isNotEmpty())
        }
        assertTrue(config.selectorsFor("newChatButton").isNotEmpty())
        assertTrue(config.selectorsFor("userMessage").isNotEmpty())
        assertTrue(config.selectorsFor("fileCard").isNotEmpty())
    }

    @Test
    fun `bundled sign-in groups match the live probe from 2026-10-18`() {
        val config = parseBundled()
        // Live probe: sidebar toggle button id + drawer "Sign in" buttons (text and aria).
        assertEquals("#sidebar-toggle-button", config.selectorsFor("sidebarToggle").first())
        assertEquals("text-button:=sign in", config.selectorsFor("signInButton").first())
        // loginFormMarker must be URL-independent: the auth route can keep the /auth URL
        // while rendering guest home content, so only text/CSS markers qualify.
        config.selectorsFor("loginFormMarker").forEach { marker ->
            assertTrue("loginFormMarker must not use url-contains: $marker", !marker.startsWith("url-contains:"))
        }
    }

    @Test
    fun `bundled asset has version, remote url and https start url`() {
        val config = parseBundled()
        assertTrue(config.version >= 1)
        assertTrue(config.remoteUpdateUrl.startsWith("https://raw.githubusercontent.com/"))
        assertTrue(config.startUrl == "https://chat.z.ai/")
        assertTrue(config.authProbePath.startsWith("/"))
    }

    @Test
    fun `bundled config v3 carries the phase 1 live-visibility and model groups`() {
        val config = parseBundled()
        // Probed live 2026-10-19 (headless Chromium, mobile viewport): the model
        // button is `button.modelSelectorButton` with aria-label "Select a model".
        assertTrue(config.version >= 3)
        assertEquals("button.modelSelectorButton", config.selectorsFor("modelLabel").first())
        assertEquals("button.modelSelectorButton", config.selectorsFor("modelMenuButton").first())
        assertTrue(config.selectorsFor("modelMenuButton").any { it.contains("aria-label") })
        // Dropdown rows read from the site's own popover (live-verified) plus
        // bits-ui role=option fallbacks (bundle-verified attribute).
        assertTrue(config.selectorsFor("modelOption").first().contains("popoverContainer"))
        assertTrue(config.selectorsFor("modelOption").any { it.contains("role='option'") })
        // Thinking/partial/step groups exist with several fallbacks (bundle-verified
        // class names; the live guest turn is captcha-gated, so these carry fallbacks).
        assertTrue(config.selectorsFor("thinkingBlock").any { it.contains("details") })
        assertTrue(config.selectorsFor("thinkingBlock").any { it.contains("thinking-block") })
        assertTrue(config.selectorsFor("partialAnswer").first() == ".chat-assistant")
        assertTrue(config.selectorsFor("stepStatus").any { it.contains("shimmer") })
    }

    @Test
    fun `bundled config v4 carries the tab-parity think-menu groups`() {
        val config = parseBundled()
        assertTrue(config.version >= 4)
        // Strings bundle-verified ("Deep Think"); DOM shapes probed defensively.
        assertTrue(config.selectorsFor("thinkMenuButton").any { it.contains("deep think") })
        assertTrue(config.selectorsFor("thinkMenuContent").any { it.contains("data-dropdown-menu-content") })
        assertTrue(config.selectorsFor("thinkMenuItem").any { it.contains("menuitem") })
    }

    @Test
    fun `older config without the phase 1 groups still parses`() {
        // A cached v2 remote config must keep working: the new groups are optional.
        val v2 = """
            {"version":2,
             "remoteUpdateUrl":"https://raw.githubusercontent.com/x/y/main/z.json",
             "page":{"startUrl":"https://chat.z.ai/","authProbePath":"/api/v1/auths/"},
             "selectors":{
               "input":["#chat-input"],"sendButton":["#send-message-button"],
               "stopIndicator":["div[aria-label='Stop']"],"assistantMessage":[".chat-assistant"],
               "codeBlock":["pre code"],"loginMarker":["url-contains:/auth"],
               "loginFormMarker":["text:=Welcome to Z.ai"],"sidebarToggle":["#sidebar-toggle-button"],
               "signInButton":["text-button:=sign in"],"captchaMarker":["#aliyunCaptcha-window-popup.window-show"]},
             "timing":{}}
        """.trimIndent()
        val config = ZaiWebConfig.fromJson(v2)
        assertEquals(2, config.version)
        assertTrue(config.selectorsFor("thinkingBlock").isEmpty())
        assertTrue(config.selectorsFor("modelLabel").isEmpty())
        assertTrue(config.selectorsFor("modelOption").isEmpty())
        assertTrue(config.selectorsFor("stepStatus").isEmpty())
        assertTrue(config.selectorsFor("partialAnswer").isEmpty())
    }

    @Test
    fun `bundled primary selectors match the live probe from 2026-10-18`() {
        val config = parseBundled()
        assertEquals("#chat-input", config.selectorsFor("input").first())
        assertEquals("#send-message-button", config.selectorsFor("sendButton").first())
        assertEquals("div[aria-label='Stop']", config.selectorsFor("stopIndicator").first())
        assertEquals(".chat-assistant", config.selectorsFor("assistantMessage").first())
        assertTrue(config.selectorsFor("loginMarker").contains("url-contains:/auth"))
        assertTrue(config.selectorsFor("captchaMarker").first().startsWith("#aliyunCaptcha"))
        assertEquals("#new-chat-button", config.selectorsFor("newChatButton").first())
    }

    @Test
    fun `bundled timing values are inside the clamp bounds`() {
        val config = parseBundled()
        assertTrue(config.timing.pollIntervalMs >= ZaiWebConfig.MIN_POLL_INTERVAL_MS)
        assertTrue(config.timing.stableWindowMs >= ZaiWebConfig.MIN_STABLE_WINDOW_MS)
        assertTrue(config.timing.generationTimeoutMs in ZaiWebConfig.MIN_GENERATION_TIMEOUT_MS..ZaiWebConfig.MAX_GENERATION_TIMEOUT_MS)
        assertTrue(config.timing.pageLoadTimeoutMs >= ZaiWebConfig.MIN_PAGE_LOAD_TIMEOUT_MS)
        // v1.0.33: progress-aware budgets exist and are sane.
        assertTrue(config.timing.noProgressTimeoutMs in ZaiWebConfig.MIN_NO_PROGRESS_TIMEOUT_MS..ZaiWebConfig.MAX_NO_PROGRESS_TIMEOUT_MS)
        assertTrue(config.timing.maxOverallMs in ZaiWebConfig.MIN_MAX_OVERALL_MS..ZaiWebConfig.MAX_MAX_OVERALL_MS)
    }

    @Test
    fun `v1_0_33 old configs without the new timing fields keep working`() {
        val config = ZaiWebConfig.fromJson(
            """
            {"version":4,"remoteUpdateUrl":"https://x/y.json",
             "page":{"startUrl":"https://chat.z.ai/"},
             "selectors":{"input":["#i"],"sendButton":["#s"],"stopIndicator":["#st"],
               "assistantMessage":[".a"],"codeBlock":["pre"],"loginMarker":["url-contains:/auth"],
               "loginFormMarker":["text:=x"],"sidebarToggle":["#sb"],"signInButton":["text-button:=in"],
               "captchaMarker":["#c"]},
             "timing":{"generationTimeoutMs":450000}}
            """.trimIndent(),
        )
        // The no-progress budget is seeded from the legacy generation timeout...
        assertEquals(450_000L, config.timing.noProgressTimeoutMs)
        // ...and the absolute cap defaults to 30 minutes.
        assertEquals(ZaiWebConfig.DEFAULT_MAX_OVERALL_MS, config.timing.maxOverallMs)
    }

    @Test
    fun `v1_0_33 new timing fields round-trip through toJsonString`() {
        val config = ZaiWebConfig.fromJson(
            """
            {"version":5,"remoteUpdateUrl":"https://x/y.json",
             "page":{"startUrl":"https://chat.z.ai/"},
             "selectors":{"input":["#i"],"sendButton":["#s"],"stopIndicator":["#st"],
               "assistantMessage":[".a"],"codeBlock":["pre"],"loginMarker":["url-contains:/auth"],
               "loginFormMarker":["text:=x"],"sidebarToggle":["#sb"],"signInButton":["text-button:=in"],
               "captchaMarker":["#c"]},
             "timing":{"noProgressTimeoutMs":240000,"maxOverallMs":1800000}}
            """.trimIndent(),
        )
        assertEquals(240_000L, config.timing.noProgressTimeoutMs)
        assertEquals(1_800_000L, config.timing.maxOverallMs)
        val roundTrip = ZaiWebConfig.fromJson(config.toJsonString())
        assertEquals(240_000L, roundTrip.timing.noProgressTimeoutMs)
        assertEquals(1_800_000L, roundTrip.timing.maxOverallMs)
    }

    @Test
    fun `toJsonString round-trips through fromJson`() {
        val config = parseBundled()
        val roundTrip = ZaiWebConfig.fromJson(config.toJsonString())
        assertEquals(config.version, roundTrip.version)
        assertEquals(config.startUrl, roundTrip.startUrl)
        assertEquals(config.remoteUpdateUrl, roundTrip.remoteUpdateUrl)
        assertEquals(config.authProbePath, roundTrip.authProbePath)
        ZaiWebConfig.REQUIRED_GROUPS.forEach { group ->
            assertEquals(config.selectorsFor(group), roundTrip.selectorsFor(group))
        }
        assertEquals(config.timing, roundTrip.timing)
    }

    @Test
    fun `missing required groups throws`() {
        val json = """
            {"version":9,"selectors":{"input":["#chat-input"]},
             "page":{"startUrl":"https://chat.z.ai/"},"timing":{}}
        """.trimIndent()
        try {
            ZaiWebConfig.fromJson(json)
            fail("expected ZaiConfigException")
        } catch (e: ZaiConfigException) {
            assertTrue(e.message!!.contains("missing required groups"))
        }
    }

    @Test
    fun `non-https start url is rejected`() {
        val json = """
            {"version":9,"selectors":{"input":["#i"],"sendButton":["#s"],"stopIndicator":["#x"],
              "assistantMessage":[".a"],"codeBlock":["pre"],"loginMarker":["url-contains:/auth"],
              "loginFormMarker":["text:=Welcome"],"sidebarToggle":["#st"],"signInButton":["text-button:=sign in"],
              "captchaMarker":["#cap"]},
             "page":{"startUrl":"http://chat.z.ai/"},"timing":{}}
        """.trimIndent()
        try {
            ZaiWebConfig.fromJson(json)
            fail("expected ZaiConfigException")
        } catch (e: ZaiConfigException) {
            assertTrue(e.message!!.contains("https"))
        }
    }

    @Test
    fun `out-of-range timing values are clamped`() {
        val json = """
            {"version":9,"selectors":{"input":["#i"],"sendButton":["#s"],"stopIndicator":["#x"],
              "assistantMessage":[".a"],"codeBlock":["pre"],"loginMarker":["url-contains:/auth"],
              "loginFormMarker":["text:=Welcome"],"sidebarToggle":["#st"],"signInButton":["text-button:=sign in"],
              "captchaMarker":["#cap"]},
             "page":{"startUrl":"https://chat.z.ai/"},
             "timing":{"pollIntervalMs":10,"stableWindowMs":600000,"generationTimeoutMs":999999999,"pageLoadTimeoutMs":1}}
        """.trimIndent()
        val config = ZaiWebConfig.fromJson(json)
        assertEquals(ZaiWebConfig.MIN_POLL_INTERVAL_MS, config.timing.pollIntervalMs)
        assertEquals(ZaiWebConfig.MAX_STABLE_WINDOW_MS, config.timing.stableWindowMs)
        assertEquals(ZaiWebConfig.MAX_GENERATION_TIMEOUT_MS, config.timing.generationTimeoutMs)
        assertEquals(ZaiWebConfig.MIN_PAGE_LOAD_TIMEOUT_MS, config.timing.pageLoadTimeoutMs)
    }

    @Test
    fun `empty fallback entries are dropped and defaults applied for missing timing`() {
        val json = """
            {"version":9,"selectors":{"input":["", "  ", "#chat-input"],"sendButton":["#s"],"stopIndicator":["#x"],
              "assistantMessage":[".a"],"codeBlock":["pre"],"loginMarker":["url-contains:/auth"],
              "loginFormMarker":["text:=Welcome"],"sidebarToggle":["#st"],"signInButton":["text-button:=sign in"],
              "captchaMarker":["#cap"]},
             "page":{"startUrl":"https://chat.z.ai/"}}
        """.trimIndent()
        val config = ZaiWebConfig.fromJson(json)
        assertEquals(listOf("#chat-input"), config.selectorsFor("input"))
        assertNotNull(config.timing)
        assertEquals(900L, config.timing.pollIntervalMs)
    }
}
