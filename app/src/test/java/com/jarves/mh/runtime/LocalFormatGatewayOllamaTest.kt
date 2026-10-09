package com.jarves.mh.runtime

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ollama Cloud native /api/chat support in the Claude Code format gateway.
 * The gateway now tries the documented native endpoint first and translates
 * its envelope to the OpenAI Chat Completions shape for the upstream parser.
 */
class LocalFormatGatewayOllamaTest {
    @Test
    fun nativeBodyForcesNonStreamingSingleEnvelope() {
        val openAiBody = JSONObject()
            .put("model", "gpt-oss:120b")
            .put("stream", true)
            .put("messages", org.json.JSONArray().put(JSONObject().put("role", "user").put("content", "hi")))

        val native = toOllamaNativeBody(openAiBody)

        assertEquals("gpt-oss:120b", native.getString("model"))
        assertFalse(native.getBoolean("stream"))
        assertEquals("user", native.getJSONArray("messages").getJSONObject(0).getString("role"))
        // The original OpenAI body must stay untouched.
        assertTrue(openAiBody.getBoolean("stream"))
    }

    @Test
    fun nativeResponseTranslatesToOpenAiChatShape() {
        val native = JSONObject()
            .put("message", JSONObject().put("role", "assistant").put("content", "The sky is blue."))
            .put("done", true)
            .put("prompt_eval_count", 12)
            .put("eval_count", 34)

        val translated = JSONObject(translateOllamaNativeToOpenAi(native.toString()))

        val choice = translated.getJSONArray("choices").getJSONObject(0)
        assertEquals("assistant", choice.getJSONObject("message").getString("role"))
        assertEquals("The sky is blue.", choice.getJSONObject("message").getString("content"))
        assertEquals("stop", choice.getString("finish_reason"))
        val usage = translated.getJSONObject("usage")
        assertEquals(12, usage.getInt("prompt_tokens"))
        assertEquals(34, usage.getInt("completion_tokens"))
        assertEquals(46, usage.getInt("total_tokens"))
    }

    @Test
    fun nonNativeOrUnparseableBodyPassesThroughUntouched() {
        val openAiError = "{\"error\":{\"message\":\"Unauthorized\",\"type\":\"api_error\"}}"
        assertEquals(openAiError, translateOllamaNativeToOpenAi(openAiError))
        val noMessage = "{\"models\":[]}"
        assertEquals(noMessage, translateOllamaNativeToOpenAi(noMessage))
    }
}
