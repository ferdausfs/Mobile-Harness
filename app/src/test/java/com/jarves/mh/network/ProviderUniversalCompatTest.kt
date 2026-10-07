package com.jarves.mh.network

import com.jarves.mh.model.AgentKind
import com.jarves.mh.model.ProviderKind
import com.jarves.mh.model.ProviderProfile
import com.jarves.mh.model.ProviderProtocol
import com.jarves.mh.model.inferredDshApiForUrl
import com.jarves.mh.model.providerProtocolForAgent
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Custom providers must accept every common wire format: OpenAI Chat Completions,
 * OpenAI Responses, and Anthropic Messages, with or without a /v1 base path.
 */
class ProviderUniversalCompatTest {
    private val api = ProviderApiClient()

    @Test
    fun customOpenAiUrlInfersCompletionsForClaudeCode() {
        val profile = ProviderProfile(
            kind = ProviderKind.CUSTOM,
            baseUrl = "https://api.example.com/v1",
            model = "test-model",
            dshApi = "",
        )
        assertEquals(ProviderProtocol.OPENAI_CHAT, providerProtocolForAgent(profile, AgentKind.CLAUDE_CODE))
        assertEquals(ProviderProtocol.OPENAI_CHAT, providerProtocolForAgent(profile, AgentKind.DEEPSEEK_HARNESS))
    }

    @Test
    fun customAnthropicUrlKeepsAnthropicForClaudeCode() {
        val profile = ProviderProfile(
            kind = ProviderKind.CUSTOM,
            baseUrl = "https://api.example.com/anthropic",
            model = "test-model",
            dshApi = "",
        )
        assertEquals(ProviderProtocol.ANTHROPIC_GATEWAY, providerProtocolForAgent(profile, AgentKind.CLAUDE_CODE))
    }

    @Test
    fun customExplicitResponsesSelectionWinsOverUrlGuess() {
        val profile = ProviderProfile(
            kind = ProviderKind.CUSTOM,
            baseUrl = "https://api.example.com/v1",
            model = "test-model",
            dshApi = "openai-responses",
        )
        assertEquals(ProviderProtocol.OPENAI_RESPONSES, providerProtocolForAgent(profile, AgentKind.CLAUDE_CODE))
    }

    @Test
    fun knownOpenAiOnlyHostsInferCompletions() {
        assertEquals("openai-completions", inferredDshApiForUrl("https://api.groq.com/openai/v1"))
        assertEquals("openai-completions", inferredDshApiForUrl("https://api.groq.com"))
        assertEquals("openai-completions", inferredDshApiForUrl("https://generativelanguage.googleapis.com/v1beta/openai"))
        assertEquals("openai-completions", inferredDshApiForUrl("https://api.deepseek.com/v1"))
        assertEquals("anthropic-messages", inferredDshApiForUrl("https://example.com/anthropic"))
    }

    @Test
    fun anthropicValidationFallsBackToOpenAiCandidates() {
        val candidates = api.validationCandidates("https://gateway.example.com", ProviderProtocol.ANTHROPIC_GATEWAY)
        assertEquals("https://gateway.example.com/v1/messages", candidates.first().url)
        assertTrue(candidates.any { it.url == "https://gateway.example.com/chat/completions" && it.protocol == ProviderProtocol.OPENAI_CHAT })
        assertTrue(candidates.any { it.url == "https://gateway.example.com/v1/chat/completions" })
    }

    @Test
    fun openAiValidationKeepsVersionedPathFirstAndNoDoubleV1() {
        val withV1 = api.validationCandidates("https://api.example.com/v1", ProviderProtocol.OPENAI_CHAT)
        assertEquals("https://api.example.com/v1/chat/completions", withV1.first().url)
        assertTrue(withV1.none { it.url.contains("/v1/v1") })

        val bare = api.validationCandidates("https://api.example.com", ProviderProtocol.OPENAI_CHAT)
        assertEquals("https://api.example.com/chat/completions", bare[0].url)
        assertEquals("https://api.example.com/v1/chat/completions", bare[1].url)
    }

    @Test
    fun validationBodyNeverUsesRejectedMaxTokens() {
        val body = JSONObject(
            api.validationBody(model = "m", protocol = ProviderProtocol.OPENAI_CHAT),
        )
        assertTrue(body.getInt("max_tokens") > 2)
        val anthropicBody = JSONObject(api.validationBody(model = "m", protocol = ProviderProtocol.ANTHROPIC_GATEWAY))
        assertTrue(anthropicBody.getInt("max_tokens") > 2)
    }
}
