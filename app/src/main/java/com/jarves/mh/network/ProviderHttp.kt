package com.jarves.mh.network

import com.jarves.mh.BuildConfig

/**
 * Shared HTTP transport helpers for every provider-facing request the app
 * itself makes.
 *
 * Android's [java.net.HttpURLConnection] sends a Dalvik User-Agent by
 * default, and some provider edges (ollama.com's Google Frontend, for
 * example) answer that User-Agent with HTTP 403 before the request ever
 * reaches authentication — the key is never evaluated. Every provider
 * request therefore sets an explicit app User-Agent, pasted keys are
 * normalized before they are sent, and 403 answers are classified by body
 * shape so an edge block is not misreported as a key rejection.
 */
object ProviderHttp {

    /** App-wide User-Agent for provider requests (never the Dalvik default). */
    val APP_USER_AGENT: String = "MobileHarness/${BuildConfig.VERSION_NAME} (Android)"

    /**
     * The User-Agent for [endpoint]. OpenCode Zen expects requests to
     * identify the OpenCode client, so its endpoints keep the dedicated
     * OpenCode UA; every other endpoint uses the app UA.
     */
    fun userAgentFor(endpoint: String): String =
        if (endpoint.startsWith("https://opencode.ai/zen/")) "opencode/1.18.20" else APP_USER_AGENT

    /**
     * True when a response body is JSON-shaped. Provider auth layers answer
     * with a JSON error object; edge/CDN client blocks answer with an HTML
     * page (or nothing).
     */
    fun looksLikeJson(body: String): Boolean {
        val trimmed = body.trim()
        return (trimmed.startsWith("{") && trimmed.endsWith("}")) ||
            (trimmed.startsWith("[") && trimmed.endsWith("]"))
    }

    /**
     * True when an HTTP 403 is an edge/CDN client block (non-JSON body)
     * rather than the provider's auth layer rejecting the key. A blocked
     * request was refused BEFORE the key was evaluated, so it must not be
     * reported as a key rejection. Anthropic's genuine 403s carry a JSON
     * auth-error body and stay on the "rejected key" path.
     */
    fun isEdgeBlock(code: Int, body: String): Boolean = code == 403 && !looksLikeJson(body)
}
