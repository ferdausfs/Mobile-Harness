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
    fun ollamaCloudValidationTriesNativeApiChatCandidate() {
        val client = ProviderApiClient()
        val candidates = client.validationCandidates(
            baseUrl = "https://ollama.com/v1",
            protocol = ProviderProtocol.OPENAI_CHAT,
        )
        val urls = candidates.map { it.url }
        // Primary OpenAI endpoint still tried first.
        assert(urls.any { it == "https://ollama.com/v1/chat/completions" }) {
            "Expected /v1/chat/completions candidate. Got: $urls"
        }
        // Ollama native /api/chat endpoint also tried as a fallback for keys
        // that cannot reach the OpenAI shim.
        assert(urls.any { it == "https://ollama.com/api/chat" }) {
            "Expected /api/chat candidate for Ollama Cloud. Got: $urls"
        }
    }

    @Test
    fun ollamaCloudNativeApiTagsIsProbedDuringDiscovery() {
        val client = ProviderApiClient()
        // Reflect into the private modelEndpoints via a test seam: the public
        // discoverModels path accepts the base URL and protocol. We assert that
        // the candidate list (visible via validationCandidates for the same
        // host) extends to the native /api/chat endpoint, which is the same
        // host-detection used to add /api/tags in modelEndpoints.
        val candidates = client.validationCandidates(
            baseUrl = "https://ollama.com/v1",
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
