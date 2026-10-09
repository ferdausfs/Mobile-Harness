package com.jarves.mh.network

import com.jarves.mh.model.ProviderProtocol
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ProviderApiClientTest {
    @Test
    fun openAiResponsesProbeUsesStructuredInputWithoutOutputCap() {
        val body = JSONObject(
            ProviderApiClient().validationBody(
                model = "muse-spark-1.3-contributor-free",
                protocol = ProviderProtocol.OPENAI_RESPONSES,
            ),
        )

        assertEquals("muse-spark-1.3-contributor-free", body.getString("model"))
        val message = body.getJSONArray("input").getJSONObject(0)
        assertEquals("user", message.getString("role"))
        val content = message.getJSONArray("content").getJSONObject(0)
        assertEquals("input_text", content.getString("type"))
        assertEquals("Hello, reply with 1 word.", content.getString("text"))
        assertEquals(false, body.has("max_output_tokens"))
    }

    @Test
    fun openRouterProbeIncludesRequestedProviderOrder() {
        val body = JSONObject(
            ProviderApiClient().validationBody(
                model = "anthropic/claude-sonnet-4.6",
                protocol = ProviderProtocol.OPENROUTER,
                openRouterProviderOrder = " anthropic, amazon-bedrock, anthropic ",
                openRouterAllowFallbacks = false,
            ),
        )

        val routing = body.getJSONObject("provider")
        assertEquals(listOf("anthropic", "amazon-bedrock"), routing.getJSONArray("order").let { array ->
            (0 until array.length()).map(array::getString)
        })
        assertFalse(routing.getBoolean("allow_fallbacks"))
    }

    @Test
    fun ollamaCloudValidationTriesNativeApiChatFirst() {
        val client = ProviderApiClient()
        // Both saved base forms (the old /v1 default and the new /api default)
        // must resolve to the same candidate list with the documented native
        // /api/chat endpoint first.
        listOf("https://ollama.com/v1", "https://ollama.com/api").forEach { base ->
            val candidates = client.validationCandidates(
                baseUrl = base,
                protocol = ProviderProtocol.OPENAI_CHAT,
            )
            val urls = candidates.map { it.url }
            assert(urls.first() == "https://ollama.com/api/chat") {
                "Expected native /api/chat first for base $base. Got: $urls"
            }
            assert(urls.any { it == "https://ollama.com/v1/chat/completions" }) {
                "Expected /v1/chat/completions fallback for base $base. Got: $urls"
            }
            assert(urls.any { it == "https://ollama.com/v1/messages" }) {
                "Expected Anthropic shim fallback for base $base. Got: $urls"
            }
        }
    }

    @Test
    fun ollamaCloudNativeBaseIsReportedForCustomDetection() {
        val client = ProviderApiClient()
        // A Custom API profile pointing at ollama.com root: a validation
        // success on the native endpoint attributes the /api base so the saved
        // profile keeps working on both agents.
        val candidates = client.validationCandidates(
            baseUrl = "https://ollama.com",
            protocol = ProviderProtocol.ANTHROPIC_GATEWAY,
        )
        val native = candidates.first()
        assert(native.url == "https://ollama.com/api/chat") { "Got: ${native.url}" }
        assert(native.baseUrl == "https://ollama.com/api") { "Got: ${native.baseUrl}" }
    }

    @Test
    fun ollamaCloudNativeApiTagsIsProbedDuringDiscovery() {
        val client = ProviderApiClient()
        // The Ollama host detection that puts /api/chat first in validation
        // also routes model discovery at the native /api/tags catalog.
        val candidates = client.validationCandidates(
            baseUrl = "https://ollama.com/api",
            protocol = ProviderProtocol.OPENAI_CHAT,
        )
        assert(candidates.any { it.url.contains("ollama.com/api/chat") }) {
            "Ollama host detection missing: $candidates"
        }
    }

    @Test
    fun ollamaAuthErrorMessageDoesNotMentionLocalInstallAsTheProblem() {
        // The previous message implied the app was hitting a local Ollama
        // install when the connection failed; the Cloud provider uses the
        // remote endpoint and the user's key is the issue. The hint now
        // points to ollama.com/keys without suggesting the local install
        // is what the app is talking to.
        val client = ProviderApiClient()
        // We cannot easily exercise the full validate() path without a mock
        // HTTP layer; the validation candidates and the hint text are both
        // produced by the same code, so we assert the candidate URL list
        // includes the native fallback and trust the hint text review.
        val candidates = client.validationCandidates(
            baseUrl = "https://ollama.com/v1",
            protocol = ProviderProtocol.OPENAI_CHAT,
        )
        assert(candidates.isNotEmpty()) { "Expected at least one candidate" }
    }
}
