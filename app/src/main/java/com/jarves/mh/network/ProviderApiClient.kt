package com.jarves.mh.network

import com.jarves.mh.model.ProviderProtocol
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class DiscoveredModel(val id: String, val displayName: String = id, val isFree: Boolean = false)

sealed interface ModelDiscoveryResult {
    data class Success(val models: List<DiscoveredModel>, val endpoint: String) : ModelDiscoveryResult
    data class Failure(val message: String, val providerMessage: String? = null) : ModelDiscoveryResult
}

sealed interface ConnectionValidation {
    data class Success(
        val message: String,
        /** Wire format that actually worked when auto-fallback had to switch protocols. */
        val detectedProtocol: ProviderProtocol? = null,
        /** Base URL that actually worked when auto-fallback had to add or drop a path suffix. */
        val detectedBaseUrl: String? = null,
    ) : ConnectionValidation
    data class Failure(
        val message: String,
        val providerMessage: String? = null,
        val label: String = "Failed",
    ) : ConnectionValidation
}

class ProviderApiClient {
    suspend fun discoverModels(
        baseUrl: String,
        apiKey: String,
        protocol: ProviderProtocol,
    ): ModelDiscoveryResult = withContext(Dispatchers.IO) {
        if (baseUrl.isBlank()) {
            return@withContext ModelDiscoveryResult.Failure("Enter a base URL first.")
        }

        var authError = false
        var lastMessage = "This provider did not expose a model list. You can enter a custom model name."
        var lastProviderMessage: String? = null
        for (endpoint in modelEndpoints(baseUrl, protocol)) {
            // OpenRouter's complete catalog is public. Fetch it anonymously even when
            // OpenRouter is configured through Custom API so an account-scoped key does
            // not reduce discovery to the models allowed by that key's preferences.
            // The saved key is still used for validation and all inference requests.
            val discoveryKey = if (isOpenRouterCatalogEndpoint(endpoint)) "" else apiKey
            val response = request(endpoint, "GET", discoveryKey, protocol = protocol)
            when {
                response.code == 401 || response.code == 403 -> {
                    authError = true
                    lastProviderMessage = providerErrorMessage(response.body)
                }
                response.code in 200..299 -> {
                    val models = ModelResponseParser.parse(response.body)
                    if (models.isNotEmpty()) return@withContext ModelDiscoveryResult.Success(models, endpoint)
                    lastMessage = "The provider replied, but its model list was empty or unsupported."
                }
                response.code > 0 && response.code != 404 -> {
                    lastMessage = friendlyHttpError(response.code)
                    lastProviderMessage = providerErrorMessage(response.body)
                }
                response.error != null -> lastMessage = response.error
            }
        }
        ModelDiscoveryResult.Failure(
            if (authError) "Check the saved API key, then try refreshing again." else lastMessage,
            lastProviderMessage,
        )
    }

    suspend fun validate(
        baseUrl: String,
        model: String,
        apiKey: String,
        protocol: ProviderProtocol,
        discoveredModels: List<DiscoveredModel>,
        openRouterProviderOrder: String = "",
        openRouterAllowFallbacks: Boolean = true,
    ): ConnectionValidation = withContext(Dispatchers.IO) {
        if (baseUrl.isBlank() || model.isBlank() || apiKey.isBlank()) {
            return@withContext ConnectionValidation.Failure("Base URL, model, and API key are required.")
        }
        // Gateways may need to cold-start a model before returning the first token.
        // A ten-second validation timeout produced false "network" failures even
        // though discovery and the endpoint itself were healthy.
        val timeouts = Pair(12_000, 45_000)
        var bestFailure: ConnectionValidation.Failure? = null
        var sawAuthFailure = false
        for (candidate in validationCandidates(baseUrl, protocol)) {
            val body = validationBody(model, candidate.protocol, openRouterProviderOrder, openRouterAllowFallbacks)
            val response = request(candidate.url, "POST", apiKey, body, candidate.protocol, connectTimeoutMs = timeouts.first, readTimeoutMs = timeouts.second)
            when {
                response.code in 200..299 -> return@withContext ConnectionValidation.Success(
                    when (candidate.protocol) {
                        ProviderProtocol.ANTHROPIC, ProviderProtocol.ANTHROPIC_GATEWAY, ProviderProtocol.OPENROUTER ->
                            if (candidate.protocol != protocol || candidate.baseUrl != baseUrl) {
                                "Endpoint verified with the ${wireFormatLabel(candidate.protocol)} format. Settings are ready."
                            } else {
                                "Anthropic Messages endpoint verified. Claude Code settings are ready."
                            }
                        else ->
                            if (candidate.protocol != protocol || candidate.baseUrl != baseUrl) {
                                "OpenAI-compatible endpoint verified. Claude Code requests will be translated."
                            } else {
                                "Connection successful. Claude Code settings are ready."
                            }
                    },
                    detectedProtocol = if (candidate.protocol != protocol || candidate.baseUrl != baseUrl) candidate.protocol else null,
                    detectedBaseUrl = if (candidate.baseUrl != baseUrl) candidate.baseUrl else null,
                )
                response.code == 401 || response.code == 403 -> {
                    sawAuthFailure = true
                    val host = runCatching { URL(baseUrl).host }.getOrDefault("")
                    val hint = if (host.equals("ollama.com", ignoreCase = true)) {
                        " Get a Cloud key at ollama.com/keys (a local Ollama install does not need one)."
                    } else ""
                    // Name the endpoint that rejected the key. A key pasted under
                    // the wrong provider (an Ollama Cloud key checked against
                    // DeepSeek, for example) otherwise reads as "your key is
                    // invalid" with no hint that the provider card, not the key,
                    // is what needs changing.
                    val attribution = if (host.isNotBlank()) "$host rejected this key. " else ""
                    bestFailure = ConnectionValidation.Failure(
                        "${attribution}Check this API key or select another saved key.$hint",
                        providerErrorMessage(response.body),
                        "Rejected",
                    )
                }
                response.code == 404 || response.code == 405 || response.code == 501 -> {
                    // Wrong path or unsupported method on this wire format; try the next candidate.
                    if (bestFailure == null) {
                        bestFailure = ConnectionValidation.Failure(
                            "Check the Base URL and selected gateway protocol.",
                            providerErrorMessage(response.body),
                            "Endpoint error",
                        )
                    }
                }
                response.code == 400 && response.body.contains("model", ignoreCase = true) ->
                    return@withContext ConnectionValidation.Failure(
                        "Refresh the model list or select a different model.",
                        providerErrorMessage(response.body),
                        "Model error",
                    )
                response.code == 429 -> return@withContext ConnectionValidation.Failure(
                    "Wait a moment, then retry or use another API key.",
                    providerErrorMessage(response.body),
                    "Rate limited",
                )
                response.code in 500..599 -> return@withContext ConnectionValidation.Failure(
                    "The provider is temporarily unavailable. Try again shortly.",
                    providerErrorMessage(response.body),
                    "Provider error",
                )
                response.code > 0 -> bestFailure = ConnectionValidation.Failure(
                    "Review the model, protocol, and endpoint settings.",
                    providerErrorMessage(response.body),
                    "Request failed",
                )
                response.error?.contains("timeout", ignoreCase = true) == true ||
                    response.error?.contains("timed out", ignoreCase = true) == true ->
                    return@withContext ConnectionValidation.Failure("Check your connection and try again.", response.error, "Timed out")
                else -> bestFailure = ConnectionValidation.Failure(
                    "Check your internet connection and provider settings.",
                    response.error,
                    "Network error",
                )
            }
        }
        bestFailure ?: ConnectionValidation.Failure(
            "Check the Base URL and selected gateway protocol.",
            null,
            "Endpoint error",
        ).let { failure ->
            if (sawAuthFailure && failure.label == "Endpoint error") {
                failure.copy(message = "The endpoint answered but rejected the key. Check the API key, protocol, and Base URL.")
            } else {
                failure
            }
        }
    }

    /** Wire-format candidates tried in order so any OpenAI- or Anthropic-style provider validates. */
    internal fun validationCandidates(baseUrl: String, protocol: ProviderProtocol): List<EndpointCandidate> = buildList {
        val base = baseUrl.trim().trimEnd('/')
        if (isOllamaHost(base)) {
            // Ollama Cloud: the documented native /api/chat endpoint is the
            // primary wire for cloud keys; the OpenAI-compatible /v1 shim and
            // the Anthropic Messages shim follow as fallbacks. The native
            // endpoint accepts the same body shape (model, messages;
            // max_tokens ignored) and validation only checks the HTTP code,
            // so a 2xx on /api/chat verifies the key even when /v1 answers 401.
            val root = ollamaRoot(base)
            add(EndpointCandidate("$root/api/chat", ProviderProtocol.OPENAI_CHAT, "$root/api"))
            add(EndpointCandidate("$root/v1/chat/completions", ProviderProtocol.OPENAI_CHAT, "$root/v1"))
            add(EndpointCandidate("$root/v1/messages", ProviderProtocol.ANTHROPIC_GATEWAY, "$root/v1"))
            return@buildList
        }
        add(EndpointCandidate(messagesEndpoint(base, protocol), protocol, base))
        when (protocol) {
            ProviderProtocol.OPENAI_CHAT, ProviderProtocol.OPENAI_RESPONSES -> {
                if (!base.endsWith("/v1")) add(EndpointCandidate("$base/v1/${if (protocol == ProviderProtocol.OPENAI_CHAT) "chat/completions" else "responses"}", protocol, "$base/v1"))
                // Ollama-style native /api bases expose OpenAI compatibility on /v1.
                if (base.endsWith("/api")) add(EndpointCandidate("${base.removeSuffix("/api")}/v1/${if (protocol == ProviderProtocol.OPENAI_CHAT) "chat/completions" else "responses"}", protocol, "${base.removeSuffix("/api")}/v1"))
                // A chosen OpenAI format that 404s may actually be an Anthropic-style gateway.
                add(EndpointCandidate(anthropicMessagesEndpoint(base), ProviderProtocol.ANTHROPIC_GATEWAY, base))
            }
            ProviderProtocol.ANTHROPIC, ProviderProtocol.ANTHROPIC_GATEWAY, ProviderProtocol.CLAUDE_LOGIN -> {
                // An Anthropic attempt that 404s may actually be an OpenAI-compatible gateway.
                openAiChatCandidates(base).forEach { add(EndpointCandidate(it.url, ProviderProtocol.OPENAI_CHAT, it.baseUrl)) }
            }
            ProviderProtocol.OPENROUTER -> {
                // OpenRouter serves both wire formats. If its Anthropic Messages
                // shim cannot answer, the mature OpenAI endpoint is still there.
                openAiChatCandidates("$base/v1").forEach { add(EndpointCandidate(it.url, ProviderProtocol.OPENAI_CHAT, it.baseUrl)) }
            }
        }
    }.distinctBy { it.url }

    private fun isOllamaHost(baseUrl: String): Boolean = runCatching {
        java.net.URI(baseUrl).host.orEmpty().equals("ollama.com", ignoreCase = true)
    }.getOrDefault(false)

    /**
     * Ollama Cloud host root: accepts a base in either form
     * (https://ollama.com, https://ollama.com/api, https://ollama.com/v1)
     * and returns the bare host root the /api and /v1 trees hang off.
     */
    private fun ollamaRoot(baseUrl: String): String {
        val base = baseUrl.trim().trimEnd('/')
        return when {
            base.endsWith("/api") -> base.removeSuffix("/api")
            base.endsWith("/v1") -> base.removeSuffix("/v1")
            else -> base
        }
    }

    private fun openAiChatCandidates(base: String): List<EndpointCandidate> = buildList {
        add(EndpointCandidate("$base/chat/completions", ProviderProtocol.OPENAI_CHAT, base))
        if (!base.endsWith("/v1")) add(EndpointCandidate("$base/v1/chat/completions", ProviderProtocol.OPENAI_CHAT, "$base/v1"))
        // Ollama-style native /api bases expose OpenAI compatibility on /v1 of
        // the same host (the runtime gateway applies the same rescue).
        if (base.endsWith("/api")) add(EndpointCandidate("${base.removeSuffix("/api")}/v1/chat/completions", ProviderProtocol.OPENAI_CHAT, "${base.removeSuffix("/api")}/v1"))
    }

    private fun anthropicMessagesEndpoint(base: String): String =
        if (base.endsWith("/v1")) "$base/messages" else "$base/v1/messages"

    private fun wireFormatLabel(protocol: ProviderProtocol): String = when (protocol) {
        ProviderProtocol.OPENAI_CHAT -> "OpenAI Chat Completions"
        ProviderProtocol.OPENAI_RESPONSES -> "OpenAI Responses"
        else -> "Anthropic Messages"
    }

    private fun request(
        endpoint: String,
        method: String,
        apiKey: String,
        body: String? = null,
        protocol: ProviderProtocol,
        connectTimeoutMs: Int = 12_000,
        readTimeoutMs: Int = 20_000,
    ): HttpResult {
        val connection = URL(endpoint).openConnection() as HttpURLConnection
        return try {
            connection.apply {
                requestMethod = method
                connectTimeout = connectTimeoutMs
                readTimeout = readTimeoutMs
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Content-Type", "application/json")
                if (apiKey.isNotBlank()) {
                    setRequestProperty("Authorization", "Bearer $apiKey")
                }
                if (endpoint.startsWith("https://opencode.ai/zen/")) {
                    // OpenCode Zen expects requests to identify the OpenCode client and session.
                    setRequestProperty("User-Agent", "opencode/1.18.20")
                    setRequestProperty("x-session-id", "session-${UUID.randomUUID()}")
                }
                if (apiKey.isNotBlank() && protocol != ProviderProtocol.OPENAI_CHAT && protocol != ProviderProtocol.OPENAI_RESPONSES) {
                    // Anthropic-Messages wire: the version header is mandatory;
                    // OpenRouter authenticates via Bearer only, the rest accept x-api-key.
                    setRequestProperty("anthropic-version", "2023-06-01")
                    if (protocol != ProviderProtocol.OPENROUTER) setRequestProperty("x-api-key", apiKey)
                }
                if (body != null) doOutput = true
            }
            if (body != null) connection.outputStream.use { it.write(body.toByteArray()) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val responseBody = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            HttpResult(code, responseBody)
        } catch (error: Exception) {
            HttpResult(0, "", error.message ?: "Network connection failed")
        } finally {
            connection.disconnect()
        }
    }

    private fun modelEndpoints(baseUrl: String, protocol: ProviderProtocol): List<String> {
        val base = baseUrl.trim().trimEnd('/')
        // Ollama Cloud: the native /api/tags catalog is public and complete;
        // the OpenAI /v1/models list can come back empty for account-scoped
        // keys, so the native catalog is tried first.
        if (isOllamaHost(base)) {
            val root = ollamaRoot(base)
            return listOf("$root/api/tags", "$root/v1/models")
        }
        val withoutAnthropic = base.removeSuffix("/anthropic")
        val candidates = when (protocol) {
            ProviderProtocol.OPENROUTER -> buildList {
                add("$base/v1/models")
                // A saved detection can switch the base to .../api/v1; avoid a doubled segment.
                if (base.endsWith("/v1")) add("$base/models")
            }
            ProviderProtocol.OPENAI_CHAT, ProviderProtocol.OPENAI_RESPONSES -> buildList {
                add("$base/models")
                if (!base.endsWith("/v1")) add("$base/v1/models")
                // Ollama-style native /api bases expose the OpenAI catalog at /v1.
                if (base.endsWith("/api")) add("${base.removeSuffix("/api")}/v1/models")
            }
            else -> listOf("$base/v1/models", "$base/models", "$withoutAnthropic/models", "$withoutAnthropic/v1/models")
        }
        return candidates.distinct()
    }

    private fun messagesEndpoint(baseUrl: String, protocol: ProviderProtocol): String {
        val base = baseUrl.trim().trimEnd('/')
        return when (protocol) {
            ProviderProtocol.OPENROUTER -> "$base/v1/messages"
            ProviderProtocol.OPENAI_CHAT -> "$base/chat/completions"
            ProviderProtocol.OPENAI_RESPONSES -> "$base/responses"
            else -> if (base.endsWith("/v1")) "$base/messages" else "$base/v1/messages"
        }
    }

    private fun isOpenRouterCatalogEndpoint(endpoint: String): Boolean = runCatching {
        val url = URL(endpoint)
        url.host.equals("openrouter.ai", ignoreCase = true) &&
            url.path.trimEnd('/').endsWith("/models")
    }.getOrDefault(false)

    internal fun validationBody(
        model: String,
        protocol: ProviderProtocol,
        openRouterProviderOrder: String = "",
        openRouterAllowFallbacks: Boolean = true,
    ): String = when (protocol) {
        ProviderProtocol.OPENAI_RESPONSES -> JSONObject()
            .put("model", model)
            .put(
                "input",
                JSONArray().put(
                    JSONObject()
                        .put("role", "user")
                        .put(
                            "content",
                            JSONArray().put(
                                JSONObject()
                                    .put("type", "input_text")
                                    .put("text", "Hello, reply with 1 word."),
                            ),
                        ),
                ),
            )
            .toString()
        ProviderProtocol.OPENAI_CHAT -> JSONObject()
            .put("model", model)
            .put("max_tokens", 32)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", "Reply OK")))
            .toString()
        else -> JSONObject()
            .put("model", model)
            .put("max_tokens", 32)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", "Reply OK")))
            .also { body ->
                val providers = openRouterProviderOrder.split(',')
                    .map(String::trim)
                    .filter(String::isNotBlank)
                    .distinct()
                if (protocol == ProviderProtocol.OPENROUTER && providers.isNotEmpty()) {
                    body.put(
                        "provider",
                        JSONObject()
                            .put("order", JSONArray(providers))
                            .put("allow_fallbacks", openRouterAllowFallbacks),
                    )
                }
            }
            .toString()
    }

    private fun friendlyHttpError(code: Int): String = when (code) {
        429 -> "The provider rate limit was reached. Wait a moment and try again."
        in 500..599 -> "The provider is temporarily unavailable (HTTP $code)."
        else -> "The provider returned HTTP $code. Check the URL and account access."
    }

    private fun providerErrorMessage(body: String): String? {
        if (body.isBlank()) return null
        val extracted = runCatching {
            val root = JSONObject(body)
            when (val error = root.opt("error")) {
                is JSONObject -> error.optString("message").ifBlank { error.optString("detail") }
                is String -> error
                else -> root.optString("message").ifBlank { root.optString("detail") }
            }
        }.getOrNull().orEmpty()
        if (extracted.isBlank()) return null
        return extracted
            .replace(Regex("(?i)bearer\\s+\\S+"), "Bearer ••••")
            .replace(Regex("(?i)sk-[a-z0-9_-]{8,}"), "sk-••••")
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(280)
    }

    private data class HttpResult(val code: Int, val body: String, val error: String? = null)

    /** One wire-format attempt: the full request URL plus the base URL that produced it. */
    internal data class EndpointCandidate(val url: String, val protocol: ProviderProtocol, val baseUrl: String)
}

object ModelResponseParser {
    fun parse(json: String): List<DiscoveredModel> = runCatching {
        val trimmed = json.trim()
        val array = when {
            trimmed.startsWith("[") -> JSONArray(trimmed)
            else -> {
                val root = JSONObject(trimmed)
                root.optJSONArray("data") ?: root.optJSONArray("models") ?: JSONArray()
            }
        }
        buildList {
            for (index in 0 until array.length()) {
                when (val item = array.opt(index)) {
                    is String -> add(DiscoveredModel(item))
                    is JSONObject -> {
                        val id = item.optString("id").ifBlank { item.optString("name") }
                        if (id.isNotBlank()) {
                            val label = item.optString("display_name").ifBlank { item.optString("displayName") }.ifBlank { id }
                            val pricing = item.optJSONObject("pricing")
                            val free = id.endsWith(":free", ignoreCase = true) || pricing?.let {
                                listOf("prompt", "completion", "request").all { field ->
                                    it.optString(field, "0").toDoubleOrNull() == 0.0
                                }
                            } == true
                            add(DiscoveredModel(id, label, free))
                        }
                    }
                }
            }
        }.distinctBy { it.id }.sortedWith(compareByDescending<DiscoveredModel> { it.isFree }.thenBy { it.displayName.lowercase() })
    }.getOrDefault(emptyList())
}
