package com.jarves.mh.zweb

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
}
