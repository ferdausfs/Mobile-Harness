package com.jarves.mh.zweb

import org.json.JSONArray
import org.json.JSONObject

/**
 * Pure model of the site's own reasoning/thinking controls (the "Deep Think"
 * menu), probed through the hidden WebView.
 *
 * Provenance (2026-10-19): the CONTROL STRINGS are bundle-verified
 * (prod-fe-1.1.107 contains "Deep Think", "Reasoning Effort", effort values
 * low/high/max and the autoThink/reasoning_effort props); the exact DOM shape
 * is NOT live-verifiable from a guest session — the control only renders for
 * signed-in users on models whose capabilities expose reasoning_effort. The
 * read script is therefore defensive over several shapes, and the UI renders
 * ONLY what a read actually returned: nothing found → the section is hidden.
 * No fake controls, ever.
 */
object ZaiThinkControls {

    /** One menu row as read from the open menu. */
    data class MenuItem(
        val text: String,
        /** aria-checked / data-state when the element exposes one. */
        val checked: String?,
    )

    /** What the site currently offers. */
    data class Controls(
        val autoThink: Boolean?,
        val effort: String?,
        val effortOptions: List<String>,
    ) {
        val hasAnything: Boolean get() = autoThink != null || effortOptions.isNotEmpty()
    }

    const val MAX_ITEMS = 24

    /** Known effort values on chat.z.ai (bundle-verified: low/high/max). */
    val KNOWN_EFFORTS = listOf("low", "high", "max")

    /**
     * Parses the menu-read probe JSON ({items:[{text,checked}],url}) into
     * [Controls]. Returns null when the menu was not found or empty — the UI
     * hides the section in that case.
     */
    fun parse(json: String?): Controls? {
        if (json.isNullOrBlank()) return null
        val obj = runCatching { JSONObject(cleanEvalResult(json)) }.getOrNull() ?: return null
        if (!obj.optBoolean("found", false)) return null
        val itemsJson = obj.optJSONArray("items") ?: return null
        val items = ArrayList<MenuItem>()
        for (i in 0 until itemsJson.length().coerceAtMost(MAX_ITEMS)) {
            val o = itemsJson.optJSONObject(i) ?: continue
            val text = o.optString("text", "").trim()
            if (text.isEmpty()) continue
            items.add(MenuItem(text.take(120), o.optString("checked", "").ifBlank { null }))
        }
        // A menu that produced no readable rows is "nothing usable" — the UI
        // hides the section exactly as if the menu had not been found.
        if (items.isEmpty()) return null
        return fromItems(items)
    }

    /** Pure mapping from menu rows to the control state. */
    fun fromItems(items: List<MenuItem>): Controls {
        // The toggle row is the one labelled like the bundle's "Deep Think" /
        // "Reasoning Effort" entry; its checked state is the on/off switch.
        val toggleRow = items.firstOrNull {
            it.checked != null && (it.text.contains("deep think", ignoreCase = true) ||
                it.text.contains("thinking", ignoreCase = true) ||
                it.text.contains("reasoning", ignoreCase = true))
        }
        val autoThink = toggleRow?.checked?.let(::parseChecked)
        // Effort options are SHORT rows that are just an effort word (menu
        // rows can carry checkmark glyphs or stray whitespace around them).
        fun effortWord(text: String): String? {
            val cleaned = text.trim().lowercase().filter { it.isLetter() }
            return if (cleaned in KNOWN_EFFORTS && text.trim().length <= 12) cleaned else null
        }
        val effortOptions = items.mapNotNull { effortWord(it.text) }.distinct()
        val effort = items.firstOrNull { item ->
            parseChecked(item.checked ?: "") == true && effortWord(item.text) != null
        }?.let { effortWord(it.text) }
        return Controls(
            autoThink = autoThink,
            effort = effort?.takeIf { it in effortOptions || effortOptions.isEmpty() },
            effortOptions = effortOptions,
        )
    }

    /** Interprets aria-checked/data-state values. */
    fun parseChecked(raw: String): Boolean? = when (raw.lowercase()) {
        "true", "checked", "on" -> true
        "false", "unchecked", "off" -> false
        else -> null
    }

    /** Human caption for the card, mirroring the Antigravity effort caption style. */
    fun caption(controls: Controls): String = when {
        controls.autoThink == false -> "Thinking off · direct answers"
        controls.autoThink == true && controls.effort != null -> "Thinking on · ${controls.effort} effort"
        controls.autoThink == true -> "Thinking on"
        controls.effort != null -> "${controls.effort} effort"
        else -> "Site defaults"
    }

    /** True when a re-read after driving matches what was requested. */
    fun verify(after: Controls, autoThink: Boolean?, effort: String?): Boolean {
        if (autoThink != null && after.autoThink != null && after.autoThink != autoThink) return false
        if (effort != null && after.effort != null && !after.effort.equals(effort, ignoreCase = true)) return false
        return autoThink != null || effort != null
    }

    private fun cleanEvalResult(raw: String): String {
        val t = raw.trim()
        if (t.length >= 2 && t.first() == '"' && t.last() == '"') {
            return runCatching { JSONArray("[$t]").getString(0) }.getOrDefault(t)
        }
        return t
    }
}

/**
 * Result of driving the site's thinking controls. [success] means a re-read
 * of the menu verified the change; otherwise [message] explains what the UI
 * shows instead (the last READ state, never a guess).
 */
data class ZaiThinkDriveOutcome(
    val success: Boolean,
    val message: String?,
)
