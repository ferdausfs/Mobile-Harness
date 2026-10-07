package com.jarves.mh.network

import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

/**
 * Best-effort client for Ollama Cloud account endpoints.
 *
 * `GET https://ollama.com/api/usage` and `POST https://ollama.com/api/me`
 * both answer 401 with a Bearer key that is missing or invalid and 200 with
 * an account-scoped payload when the key is accepted. The response schema is
 * not publicly documented, so [summarize] walks whatever JSON comes back and
 * surfaces usage/limit/credit-shaped fields for the live status card.
 */
object OllamaUsageClient {

    private const val BASE = "https://ollama.com/api"

    data class Result(
        val ok: Boolean,
        /** Combined payload: {"usage": <body>, "me": <body>} when both endpoints answered. */
        val body: String,
        val httpCode: Int,
        val error: String? = null,
    )

    fun fetch(apiKey: String): Result {
        if (apiKey.isBlank()) {
            return Result(ok = false, body = "", httpCode = 0, error = "No Ollama API key saved.")
        }
        val usage = request("$BASE/usage", "GET", apiKey, body = null)
        if (!usage.first.let { it in 200..299 }) {
            val message = when (usage.first) {
                401, 403 -> "Ollama rejected this API key."
                else -> "Ollama usage endpoint returned HTTP ${usage.first}."
            }
            return Result(ok = false, body = usage.second, httpCode = usage.first, error = message)
        }
        val combined = JSONObject()
        runCatching { combined.put("usage", JSONObject(usage.second)) }
        val me = request("$BASE/me", "POST", apiKey, body = "{}")
        if (me.first in 200..299) {
            runCatching { combined.put("me", JSONObject(me.second)) }
        }
        return Result(ok = true, body = combined.toString(), httpCode = usage.first)
    }

    /**
     * Extracts human-readable "label: value" lines from an undocumented JSON
     * payload by walking every object and keeping keys that look like usage,
     * limit, credit or quota fields. Falls back to the raw JSON when nothing
     * recognizable is found.
     */
    fun summarize(json: String): List<String> {
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return emptyList()
        val lines = mutableListOf<String>()
        walk(root, depth = 0, prefix = "", seen = mutableSetOf(), out = lines)
        if (lines.isEmpty()) {
            val raw = json.replace(Regex("\\s+"), " ").trim()
            if (raw.isNotBlank()) lines.add(raw.take(400))
        }
        return lines.distinct().take(12)
    }

    private val interesting = listOf(
        "credit", "balance", "remain", "limit", "used", "usage", "quota",
        "request", "token", "spent", "total", "plan", "tier", "subscription", "username", "email",
    )

    private fun walk(
        node: Any?,
        depth: Int,
        prefix: String,
        seen: MutableSet<Any>,
        out: MutableList<String>,
    ) {
        if (depth > 4 || out.size >= 24) return
        when (node) {
            is JSONObject -> {
                if (!seen.add(node)) return
                for (key in node.keys()) {
                    val value = node.opt(key) ?: continue
                    val path = if (prefix.isBlank()) key else "$prefix.$key"
                    if (value is JSONObject || value is org.json.JSONArray) {
                        walk(value, depth + 1, path, seen, out)
                    } else if (interesting.any { key.lowercase().contains(it) }) {
                        out.add("${prettify(path)}: ${compact(value)}")
                    }
                }
            }
            is org.json.JSONArray -> {
                for (index in 0 until node.length()) walk(node.opt(index), depth + 1, prefix, seen, out)
            }
        }
    }

    private fun prettify(path: String): String = path
        .split('.')
        .joinToString(" · ") { segment ->
            segment.replace(Regex("([a-z])([A-Z])"), "$1 $2").replace('_', ' ').lowercase()
        }

    private fun compact(value: Any?): String = when (value) {
        null, JSONObject.NULL -> "—"
        is String -> value.take(60)
        is Double -> if (value == value.toLong().toDouble()) value.toLong().toString() else String.format("%.4g", value)
        else -> value.toString().take(60)
    }

    private fun request(endpoint: String, method: String, apiKey: String, body: String?): Pair<Int, String> {
        val connection = URL(endpoint).openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = method
            connection.connectTimeout = 15_000
            connection.readTimeout = 20_000
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Authorization", "Bearer $apiKey")
            if (body != null) {
                connection.setRequestProperty("Content-Type", "application/json")
                connection.doOutput = true
                connection.outputStream.use { it.write(body.toByteArray()) }
            }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            code to (stream?.bufferedReader()?.use { it.readText() }.orEmpty())
        } catch (error: Exception) {
            0 to (error.message ?: "network error")
        } finally {
            connection.disconnect()
        }
    }
}
