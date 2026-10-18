package com.jarves.mh.zweb

import org.json.JSONArray
import org.json.JSONObject

/**
 * Pure, JSON-backed selector configuration for the Z.ai Web agent.
 *
 * Hard rule: no chat.z.ai selector may ever be hardcoded in Kotlin. Every DOM
 * lookup the engine performs goes through one of the fallback lists carried by
 * this config. The config ships bundled in assets (`assets/zweb/selectors.json`)
 * and can be hot-updated from [remoteUpdateUrl] without an app release, so a
 * chat.z.ai UI change is fixable by editing JSON only.
 *
 * Selector entry syntax (same syntax everywhere, interpreted by the engine's
 * probe script):
 *  - plain string                  → CSS selector (document.querySelector)
 *  - "url-contains:<fragment>"     → true when the current URL contains <fragment>
 *  - "text:=<literal>"             → true when document.body.innerText contains <literal>
 */
data class ZaiWebConfig(
    val version: Int,
    val remoteUpdateUrl: String,
    val startUrl: String,
    val authProbePath: String,
    val selectors: Map<String, List<String>>,
    val timing: ZaiTimingConfig,
) {
    /** Fallback list for a named selector group; empty list means the group is missing. */
    fun selectorsFor(group: String): List<String> = selectors[group].orEmpty()

    fun toJsonString(): String {
        val root = JSONObject()
        root.put(JSON_VERSION, version)
        root.put(JSON_REMOTE_URL, remoteUpdateUrl)
        val page = JSONObject()
        page.put(JSON_START_URL, startUrl)
        page.put(JSON_AUTH_PROBE_PATH, authProbePath)
        root.put(JSON_PAGE, page)
        val sel = JSONObject()
        selectors.forEach { (group, list) -> sel.put(group, JSONArray(list)) }
        root.put(JSON_SELECTORS, sel)
        val timingJson = JSONObject()
        timingJson.put(JSON_PAGE_LOAD_TIMEOUT, timing.pageLoadTimeoutMs)
        timingJson.put(JSON_PAGE_SETTLE, timing.pageSettleMs)
        timingJson.put(JSON_SEND_SETTLE, timing.sendSettleMs)
        timingJson.put(JSON_POLL_INTERVAL, timing.pollIntervalMs)
        timingJson.put(JSON_STABLE_WINDOW, timing.stableWindowMs)
        timingJson.put(JSON_GENERATION_TIMEOUT, timing.generationTimeoutMs)
        root.put(JSON_TIMING, timingJson)
        return root.toString(2)
    }

    companion object {
        const val JSON_VERSION = "version"
        const val JSON_REMOTE_URL = "remoteUpdateUrl"
        const val JSON_PAGE = "page"
        const val JSON_START_URL = "startUrl"
        const val JSON_AUTH_PROBE_PATH = "authProbePath"
        const val JSON_SELECTORS = "selectors"
        const val JSON_TIMING = "timing"
        const val JSON_PAGE_LOAD_TIMEOUT = "pageLoadTimeoutMs"
        const val JSON_PAGE_SETTLE = "pageSettleMs"
        const val JSON_SEND_SETTLE = "sendSettleMs"
        const val JSON_POLL_INTERVAL = "pollIntervalMs"
        const val JSON_STABLE_WINDOW = "stableWindowMs"
        const val JSON_GENERATION_TIMEOUT = "generationTimeoutMs"

        /**
         * Selector groups the engine requires to be present and non-empty.
         *
         * `sidebarToggle` / `signInButton` / `loginFormMarker` drive the visible
         * sign-in sheet's auto-navigation (guest home → open sidebar → tap Sign in)
         * added in config v2 after the live probe showed the auth route can fail to
         * render its form when the page is loaded inside an Android WebView.
         */
        val REQUIRED_GROUPS = listOf(
            "input",
            "sendButton",
            "stopIndicator",
            "assistantMessage",
            "codeBlock",
            "loginMarker",
            "loginFormMarker",
            "sidebarToggle",
            "signInButton",
            "captchaMarker",
        )

        /** Absolute bounds applied after parsing so a corrupt remote config cannot stall the engine. */
        const val MIN_POLL_INTERVAL_MS = 400L
        const val MAX_POLL_INTERVAL_MS = 5_000L
        const val MIN_STABLE_WINDOW_MS = 1_000L
        const val MAX_STABLE_WINDOW_MS = 30_000L
        const val MIN_GENERATION_TIMEOUT_MS = 15_000L
        const val MAX_GENERATION_TIMEOUT_MS = 30 * 60_000L
        const val MIN_PAGE_LOAD_TIMEOUT_MS = 5_000L
        const val MAX_PAGE_LOAD_TIMEOUT_MS = 120_000L

        fun fromJson(text: String): ZaiWebConfig {
            val root = JSONObject(text)
            val version = root.optInt(JSON_VERSION, 0)
            val page = root.optJSONObject(JSON_PAGE) ?: JSONObject()
            val timingObj = root.optJSONObject(JSON_TIMING) ?: JSONObject()
            val selectorsObj = root.optJSONObject(JSON_SELECTORS) ?: JSONObject()
            val selectors = LinkedHashMap<String, List<String>>()
            selectorsObj.keys().forEach { group ->
                val arr = selectorsObj.optJSONArray(group) ?: JSONArray()
                val list = ArrayList<String>(arr.length())
                for (i in 0 until arr.length()) {
                    val entry = arr.optString(i).trim()
                    if (entry.isNotEmpty()) list.add(entry)
                }
                if (list.isNotEmpty()) selectors[group] = list
            }
            val missing = REQUIRED_GROUPS.filter { it !in selectors }
            if (missing.isNotEmpty()) {
                throw ZaiConfigException("Selector config is missing required groups: $missing")
            }
            val remote = root.optString(JSON_REMOTE_URL).trim()
            val startUrl = page.optString(JSON_START_URL).trim()
            if (!startUrl.startsWith("https://")) {
                throw ZaiConfigException("Selector config startUrl must be an https:// URL")
            }
            if (remote.isNotEmpty() && !remote.startsWith("https://")) {
                throw ZaiConfigException("Selector config remoteUpdateUrl must be an https:// URL")
            }
            return ZaiWebConfig(
                version = version,
                remoteUpdateUrl = remote,
                startUrl = startUrl,
                authProbePath = page.optString(JSON_AUTH_PROBE_PATH, "/api/v1/auths/"),
                selectors = selectors,
                timing = ZaiTimingConfig(
                    pageLoadTimeoutMs = clamp(
                        timingObj.optLong(JSON_PAGE_LOAD_TIMEOUT, 25_000L),
                        MIN_PAGE_LOAD_TIMEOUT_MS, MAX_PAGE_LOAD_TIMEOUT_MS,
                    ),
                    pageSettleMs = clamp(timingObj.optLong(JSON_PAGE_SETTLE, 1_500L), 0L, 10_000L),
                    sendSettleMs = clamp(timingObj.optLong(JSON_SEND_SETTLE, 2_500L), 0L, 10_000L),
                    pollIntervalMs = clamp(
                        timingObj.optLong(JSON_POLL_INTERVAL, 900L),
                        MIN_POLL_INTERVAL_MS, MAX_POLL_INTERVAL_MS,
                    ),
                    stableWindowMs = clamp(
                        timingObj.optLong(JSON_STABLE_WINDOW, 4_000L),
                        MIN_STABLE_WINDOW_MS, MAX_STABLE_WINDOW_MS,
                    ),
                    generationTimeoutMs = clamp(
                        timingObj.optLong(JSON_GENERATION_TIMEOUT, 300_000L),
                        MIN_GENERATION_TIMEOUT_MS, MAX_GENERATION_TIMEOUT_MS,
                    ),
                ),
            )
        }

        private fun clamp(value: Long, min: Long, max: Long): Long = value.coerceIn(min, max)
    }
}

data class ZaiTimingConfig(
    val pageLoadTimeoutMs: Long,
    val pageSettleMs: Long,
    val sendSettleMs: Long,
    val pollIntervalMs: Long,
    val stableWindowMs: Long,
    val generationTimeoutMs: Long,
)

/** Raised when a selector config cannot be parsed or fails validation. */
class ZaiConfigException(message: String) : Exception(message)
