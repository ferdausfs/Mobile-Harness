package com.jarves.mh.zweb

import org.json.JSONArray
import org.json.JSONObject

/**
 * Result of driving the site's dropdown to a model. [success] means the
 * label was read back and matches; otherwise [message] explains what the
 * user sees instead (the read-only label fallback).
 */
data class ZaiModelPickOutcome(
    val success: Boolean,
    /** The label the page showed after the attempt (may be non-null even on failure). */
    val model: String?,
    /** Human-readable failure reason; null on success. */
    val message: String?,
)

/**
 * Pure logic behind the Phase 1 model picker: parse the model list read from
 * chat.z.ai's own dropdown, decide which entry matches the current label,
 * and verify a selection by reading the label back after the click.
 *
 * The catalog is a CACHE of what the site itself offered — the app never
 * invents model names. Everything here is JVM-testable; the DOM mechanics
 * live in [ZaiProbeScripts].
 */
object ZaiModelCatalog {

    const val MAX_MODELS = 40

    /** One selectable entry of the site's model dropdown. */
    data class ModelOption(
        /** The site's own display name, e.g. "GLM-5.3-Flash" — exactly what the label shows. */
        val name: String,
        /** Optional marketing description from the dropdown row (may be blank). */
        val description: String,
    )

    /**
     * Parses the JSON array produced by [ZaiProbeScripts.buildModelOptionsScript].
     * Deduplicates by name (first wins), drops blanks/control-char entries,
     * caps the list. Returns an empty list for anything unreadable.
     */
    fun parse(json: String?): List<ModelOption> {
        if (json.isNullOrBlank()) return emptyList()
        val array = runCatching {
            val cleaned = json.trim().let { t ->
                if (t.length >= 2 && t.first() == '"' && t.last() == '"') {
                    runCatching { JSONArray("[$t]").getString(0) }.getOrDefault(t)
                } else {
                    t
                }
            }
            JSONArray(cleaned)
        }.getOrNull() ?: return emptyList()
        val seen = LinkedHashSet<String>()
        val out = ArrayList<ModelOption>()
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val name = obj.optString("name", "").trim()
            if (name.isEmpty() || name.contains('\n') || seen.contains(name.lowercase())) continue
            seen.add(name.lowercase())
            out.add(
                ModelOption(
                    name = name.take(80),
                    description = obj.optString("description", "").trim().take(160),
                ),
            )
            if (out.size >= MAX_MODELS) break
        }
        return out
    }

    /** Reads the current model name out of the label probe JSON; null when unreadable. */
    fun parseLabel(json: String?): String? {
        if (json.isNullOrBlank()) return null
        val obj = runCatching {
            val cleaned = json.trim().let { t ->
                if (t.length >= 2 && t.first() == '"' && t.last() == '"') {
                    runCatching { JSONArray("[$t]").getString(0) }.getOrDefault(t)
                } else {
                    t
                }
            }
            JSONObject(cleaned)
        }.getOrNull() ?: return null
        if (!obj.optBoolean("ok", false)) return null
        return obj.optString("model", "").trim().takeIf(String::isNotEmpty)?.take(80)
    }

    /** True when the label read back after a pick matches the requested model. */
    fun labelMatches(label: String?, requested: String): Boolean {
        if (label.isNullOrBlank()) return false
        return label.trim().equals(requested.trim(), ignoreCase = true)
    }

    // ── Persistence helpers (app preferences cache of the site's dropdown) ──

    /** Serializes a cached catalog for [com.jarves.mh.data.AppPreferences]. */
    fun toJson(models: List<ModelOption>): String {
        val array = JSONArray()
        models.forEach { model ->
            array.put(JSONObject().put("name", model.name).put("description", model.description))
        }
        return array.toString()
    }

    /** Reads a catalog persisted by [toJson]; unreadable input returns emptyList. */
    fun fromJson(json: String?): List<ModelOption> {
        if (json.isNullOrBlank()) return emptyList()
        val array = runCatching { JSONArray(json) }.getOrNull() ?: return emptyList()
        val rebuilt = JSONArray()
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            rebuilt.put(
                JSONObject()
                    .put("name", obj.optString("name", ""))
                    .put("description", obj.optString("description", "")),
            )
        }
        return parse(rebuilt.toString())
    }

    /**
     * Picks the option to click for a requested model name: exact match first
     * (case-insensitive), then unique prefix match ("5.3-F" → "GLM-5.3-Flash"),
     * then unique substring match ("5.2" → "GLM-5.2"). Ambiguous matches never
     * click blind — the caller must show "model not offered / ambiguous"
     * instead. Returns null when nothing matches uniquely.
     */
    fun resolve(models: List<ModelOption>, requested: String): ModelOption? {
        val needle = requested.trim()
        if (needle.isEmpty()) return null
        models.firstOrNull { it.name.equals(needle, ignoreCase = true) }?.let { return it }
        val prefix = models.filter { it.name.startsWith(needle, ignoreCase = true) }
        if (prefix.size == 1) return prefix.single()
        val contains = models.filter { it.name.contains(needle, ignoreCase = true) }
        if (contains.size == 1) return contains.single()
        return null
    }
}
