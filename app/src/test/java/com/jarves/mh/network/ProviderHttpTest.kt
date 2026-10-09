package com.jarves.mh.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderHttpTest {

    @Test
    fun everyEndpointGetsTheAppUserAgentExceptOpenCodeZen() {
        assertEquals(ProviderHttp.APP_USER_AGENT, ProviderHttp.userAgentFor("https://ollama.com/api/chat"))
        assertEquals(ProviderHttp.APP_USER_AGENT, ProviderHttp.userAgentFor("https://ollama.com/v1/chat/completions"))
        assertEquals(ProviderHttp.APP_USER_AGENT, ProviderHttp.userAgentFor("https://api.anthropic.com/v1/messages"))
        assertEquals(ProviderHttp.APP_USER_AGENT, ProviderHttp.userAgentFor("https://api.deepseek.com/chat/completions"))
        assertEquals(ProviderHttp.APP_USER_AGENT, ProviderHttp.userAgentFor("https://openrouter.ai/api/v1/chat/completions"))
        // OpenCode Zen expects requests to identify the OpenCode client - its
        // dedicated UA must stay untouched.
        assertEquals("opencode/1.18.20", ProviderHttp.userAgentFor("https://opencode.ai/zen/v1/responses"))
    }

    @Test
    fun appUserAgentIsNeverTheDalvikDefault() {
        // ollama.com's edge blocks the Dalvik User-Agent with a 403 before the
        // key is ever checked - the app UA must never fall back to it.
        assertFalse(ProviderHttp.APP_USER_AGENT.contains("Dalvik"))
        assertTrue(ProviderHttp.APP_USER_AGENT.startsWith("MobileHarness/"))
    }

    @Test
    fun sanitizeApiKeyStripsWhitespaceAndBearerPrefixes() {
        assertEquals("sk-abc", ProviderHttp.sanitizeApiKey("sk-abc"))
        assertEquals("sk-abc", ProviderHttp.sanitizeApiKey(" sk-abc "))
        assertEquals("sk-abc", ProviderHttp.sanitizeApiKey("\nsk-abc\t"))
        assertEquals("sk-abc", ProviderHttp.sanitizeApiKey("Bearer sk-abc"))
        assertEquals("sk-abc", ProviderHttp.sanitizeApiKey("bearer sk-abc"))
        assertEquals("sk-abc", ProviderHttp.sanitizeApiKey("BEARER   sk-abc"))
        assertEquals("sk-abc", ProviderHttp.sanitizeApiKey(" Bearer bearer sk-abc "))
        // "Bearer" without a separator can be part of a literal key - untouched.
        assertEquals("BearerABC123", ProviderHttp.sanitizeApiKey("BearerABC123"))
        assertEquals("", ProviderHttp.sanitizeApiKey("   Bearer  "))
    }

    @Test
    fun edgeBlockClassificationSplitsByBodyShape() {
        // ollama.com's edge 403 answers with a tiny HTML page (server: Google Frontend).
        assertTrue(ProviderHttp.isEdgeBlock(403, "<html><head><title>403</title></head><body>403 Forbidden</body></html>"))
        // A blank 403 body carries no auth verdict either - treated as a block.
        assertTrue(ProviderHttp.isEdgeBlock(403, ""))
        // A JSON auth-error body on 403 IS the provider's auth layer (Anthropic's
        // genuine 403s) - must stay on the key-rejection path.
        assertFalse(ProviderHttp.isEdgeBlock(403, """{"type":"error","error":{"type":"authentication_error","message":"invalid x-api-key"}}"""))
        assertFalse(ProviderHttp.isEdgeBlock(403, """{"error":"Unauthorized"}"""))
        // 401 is always the auth layer's verdict, whatever the body shape.
        assertFalse(ProviderHttp.isEdgeBlock(401, "<html>blocked</html>"))
        assertFalse(ProviderHttp.isEdgeBlock(401, """{"error":"Unauthorized"}"""))
        assertFalse(ProviderHttp.isEdgeBlock(200, "<html>ok</html>"))
    }
}
