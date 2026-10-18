package com.jarves.mh.zweb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ZaiProbeScriptsTest {

    private val config: ZaiWebConfig = ZaiWebConfig.fromJson(
        """
        {"version":1,
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
    fun `state probe carries the configured selectors`() {
        val js = ZaiProbeScripts.buildStateProbe(config)
        assertTrue(js.contains("#chat-input"))
        assertTrue(js.contains("#send-message-button"))
        assertTrue(js.contains("div[aria-label='Stop']"))
        assertTrue(js.contains(".chat-assistant"))
        assertTrue(js.contains("url-contains:"))
        assertTrue(js.contains("text:="))
        // probe reports every signal the detector needs
        assertTrue(js.contains("stopPresent"))
        assertTrue(js.contains("assistantCount"))
        assertTrue(js.contains("normalizedText"))
        assertTrue(js.contains("loginMatched"))
        assertTrue(js.contains("captchaMatched"))
    }

    @Test
    fun `state probe reuses the previous sample via window state`() {
        val js = ZaiProbeScripts.buildStateProbe(config)
        assertTrue(js.contains("__mhProbePrev"))
    }

    @Test
    fun `auth probe targets the configured auth path`() {
        val js = ZaiProbeScripts.buildAuthProbe(config)
        assertTrue(js.contains("/api/v1/auths/"))
        assertTrue(js.contains("guest"))
    }

    @Test
    fun `inject script uses the native value setter and json-escaped text`() {
        val js = ZaiProbeScripts.buildInjectScript(config, "hello \"world\"\nline2")
        assertTrue(js.contains("HTMLTextAreaElement.prototype"))
        assertTrue(js.contains("getOwnPropertyDescriptor"))
        assertTrue(js.contains("hello \\\"world\\\"\\nline2"))
        assertTrue(js.contains("dispatchEvent"))
    }

    @Test
    fun `click and fallback scripts guard on disabled and missing elements`() {
        assertTrue(ZaiProbeScripts.buildClickSendScript(config).contains("send-disabled"))
        assertTrue(ZaiProbeScripts.buildEnterFallbackScript(config).contains("Enter"))
        assertTrue(ZaiProbeScripts.buildClickStopScript(config).contains("querySelector('button')"))
        assertTrue(ZaiProbeScripts.buildClickNewChatScript(config).contains("#new-chat-button"))
    }

    @Test
    fun `extract script reads text and code blocks`() {
        val js = ZaiProbeScripts.buildExtractScript(config)
        assertTrue(js.contains(".chat-assistant"))
        assertTrue(js.contains(".chat-assistant pre code"))
        assertTrue(js.contains("innerText"))
        assertTrue(js.contains("textContent"))
        assertTrue(js.contains("language-"))
    }

    @Test
    fun `no unknown selector is silently dropped from the state probe`() {
        // Every selector group the probe consumes must appear in the generated JS.
        val js = ZaiProbeScripts.buildStateProbe(config)
        assertFalse(js.contains("fileCard"))
        assertFalse(js.contains("userMessage"))
    }

    // ── Sign-in sheet automation scripts ──

    private val sheetJson = """
        {"loginFormMarker":["text:=Welcome to Z.ai","text:=Continue with Email"],
         "captchaMarker":["#aliyunCaptcha-window-popup.window-show"],
         "sidebarToggle":["#sidebar-toggle-button","button[aria-label='Toggle Sidebar']"],
         "signInButton":["text-button:=sign in","button[aria-label='Sign in']"],
         "authProbePath":"/api/v1/auths/"}
    """.trimIndent()

    @Test
    fun `sheet selectors parse from the engine payload and reject junk`() {
        val selectors = ZaiProbeScripts.parseSheetSelectors(sheetJson)
        assertNotNull(selectors)
        assertEquals(listOf("#sidebar-toggle-button", "button[aria-label='Toggle Sidebar']"), selectors!!.sidebarToggle)
        assertEquals("/api/v1/auths/", selectors.authProbePath)
        assertEquals(listOf("text:=Welcome to Z.ai"), selectors.loginFormMarker.take(1))
        // junk payloads fall back to null so the sheet can degrade to hints only
        assertNull(ZaiProbeScripts.parseSheetSelectors(null))
        assertNull(ZaiProbeScripts.parseSheetSelectors(""))
        assertNull(ZaiProbeScripts.parseSheetSelectors("not json {"))
    }

    @Test
    fun `sheet probe carries selector groups and reports the login form without url markers`() {
        val js = ZaiProbeScripts.buildSheetProbeScript(ZaiProbeScripts.parseSheetSelectors(sheetJson)!!)
        assertTrue(js.contains("#sidebar-toggle-button"))
        assertTrue(js.contains("text-button:=sign in"))
        assertTrue(js.contains("#aliyunCaptcha-window-popup.window-show"))
        assertTrue(js.contains("loginFormPresent"))
        assertTrue(js.contains("captchaPresent"))
        assertTrue(js.contains("sidebarFound"))
        assertTrue(js.contains("signInFound"))
    }

    @Test
    fun `click visible script scans fallbacks and skips url markers`() {
        val selectors = ZaiProbeScripts.parseSheetSelectors(sheetJson)!!
        val sidebar = ZaiProbeScripts.buildClickVisibleScript(selectors, "sidebarToggle")
        assertTrue(sidebar.contains("#sidebar-toggle-button"))
        assertTrue(sidebar.contains("getClientRects"))
        assertTrue(sidebar.contains("group:'sidebarToggle'"))
        // url-contains entries must never be clicked — they are not elements
        assertFalse(sidebar.contains("url-contains:/auth") && sidebar.contains("click"))
    }

    @Test
    fun `click visible script rejects non-clickable groups`() {
        val selectors = ZaiProbeScripts.parseSheetSelectors(sheetJson)!!
        try {
            ZaiProbeScripts.buildClickVisibleScript(selectors, "captchaMarker")
            fail("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("sidebarToggle"))
        }
    }

    @Test
    fun `text-button matcher is part of the shared picker helpers`() {
        // The state probe (engine) and the sheet probe share HELPERS_JS; the
        // text-button:= syntax must therefore be available everywhere.
        val js = ZaiProbeScripts.buildStateProbe(config)
        assertTrue(js.contains("text-button:="))
    }

    @Test
    fun `auth probe for a raw path keeps the guest detection`() {
        val js = ZaiProbeScripts.buildAuthProbeForPath("/api/v1/auths/")
        assertTrue(js.contains("/api/v1/auths/"))
        assertTrue(js.contains("guest"))
    }

    @Test
    fun `auth probe sends the localStorage token as Bearer plus a cookie-only fetch`() {
        // Mirrors the site's own session check (prod bundle): Bearer from
        // localStorage.token when present, cookie-only fetch as the fallback.
        val js = ZaiProbeScripts.buildAuthProbeForPath("/api/v1/auths/")
        assertTrue(js.contains("localStorage.getItem('token')"))
        assertTrue(js.contains("'Bearer '+ls"))
        assertTrue(js.contains("credentials:'include'"))
        assertTrue(js.contains("Promise.all"))
    }

    @Test
    fun `auth probe stores its result instead of returning a promise to evaluateJavascript`() {
        // THE v1.0.23 FIX. evaluateJavascript hands its callback the script's
        // synchronous completion value; a Promise completion value serializes
        // to "{}" and is never awaited on a real Android WebView (on-device
        // Diagnostics 2026-10-18). The injector must therefore store the result
        // in window.__mhAuthResult and return nothing.
        val js = ZaiProbeScripts.buildAuthProbeForPath("/api/v1/auths/")
        assertTrue(js.contains("window.__mhAuthResult=null"))
        assertTrue(js.contains("window.__mhAuthResult=JSON.stringify"))
        // No promise may be the completion value: the IIFE must not return the
        // Promise.all chain (the old broken pattern). The fetch inside the
        // call() helper is fine — its value flows into Promise.all, which only
        // STORES the result.
        assertFalse(Regex("return\\s*Promise").containsMatchIn(js))
    }

    @Test
    fun `auth probe is single-flight per document without clearing an in-flight result`() {
        // While a probe is settling, later injectors skip WITHOUT clearing, so
        // a slow fetch's result still lands and the next reader picks it up.
        val js = ZaiProbeScripts.buildAuthProbeForPath("/api/v1/auths/")
        assertTrue(js.contains("__mhAuthProbeInFlight"))
        assertTrue(js.contains("__mhAuthProbeAt"))
        assertTrue(js.contains("15000"))
    }

    @Test
    fun `auth result reader is a synchronous expression`() {
        val js = ZaiProbeScripts.buildAuthResultReaderScript()
        assertTrue(js.contains("window.__mhAuthResult||null"))
        assertFalse(js.contains("fetch"))
        assertFalse(js.contains("Promise"))
        assertFalse(js.contains(".then("))
    }

    @Test
    fun `no other probe script returns a promise or calls fetch`() {
        // The auth injector is the ONLY script allowed to fetch; every other
        // builder must stay fully synchronous so its JSON result reaches the
        // evaluateJavascript callback.
        val syncScripts = listOf(
            "stateProbe" to ZaiProbeScripts.buildStateProbe(config),
            "inject" to ZaiProbeScripts.buildInjectScript(config, "x"),
            "clickSend" to ZaiProbeScripts.buildClickSendScript(config),
            "enterFallback" to ZaiProbeScripts.buildEnterFallbackScript(config),
            "clickStop" to ZaiProbeScripts.buildClickStopScript(config),
            "clickNewChat" to ZaiProbeScripts.buildClickNewChatScript(config),
            "extract" to ZaiProbeScripts.buildExtractScript(config),
            "sheetProbe" to ZaiProbeScripts.buildSheetProbeScript(
                ZaiProbeScripts.parseSheetSelectors(sheetJson)!!,
            ),
            "sheetClickSidebar" to ZaiProbeScripts.buildClickVisibleScript(
                ZaiProbeScripts.parseSheetSelectors(sheetJson)!!, "sidebarToggle",
            ),
            "sheetClickSignIn" to ZaiProbeScripts.buildClickVisibleScript(
                ZaiProbeScripts.parseSheetSelectors(sheetJson)!!, "signInButton",
            ),
        )
        syncScripts.forEach { (name, js) ->
            assertFalse("$name must not fetch", js.contains("fetch("))
            assertFalse("$name must not use Promise", js.contains("Promise"))
            assertFalse("$name must not chain .then", js.contains(".then("))
        }
    }

    @Test
    fun `auth probe never exposes the token or email value`() {
        val js = ZaiProbeScripts.buildAuthProbeForPath("/api/v1/auths/")
        // The token value only ever flows into the Authorization header — the
        // payload carries only its presence boolean; email becomes a boolean too.
        assertTrue(js.contains("emailPresent:!!email"))
        assertTrue(js.contains("hasLocalToken:!!ls"))
        assertFalse(js.contains("token:ls"))
        assertFalse(js.contains("email:guest"))
    }

    @Test
    fun `auth probe payload keeps a compat signedIn boolean for older callers`() {
        val js = ZaiProbeScripts.buildAuthProbeForPath("/api/v1/auths/")
        assertTrue(js.contains("signedIn:bu||cu"))
    }

    @Test
    fun `auth probe strips detail bodies and truncates error text`() {
        val js = ZaiProbeScripts.buildAuthProbeForPath("/api/v1/auths/")
        assertTrue(js.contains("shape:'detail'"))
        assertTrue(js.contains("slice(0,160)"))
    }
}
