package com.jarves.mh.zweb

/**
 * User-agent override shared by BOTH chat.z.ai WebViews — the visible sign-in
 * sheet and the hidden engine (since v1.0.21 they present the same Chrome-like
 * UA; the engine captures it into the UI state for the Diagnostics report).
 *
 * Why: Google rejects OAuth sign-in from embedded browsers whose UA carries
 * the WebView markers (`; wv`, `Version/4.0`) with "disallowed_useragent".
 * Live probe (2026-10-18, headless Chromium, mobile viewport): the Google
 * account chooser loaded fine under both the WebView UA and a Chrome UA, but
 * the Chrome-like UA is the safest bet on real devices where Google's risk
 * engine may differ. Stripping only those two markers turns the WebView UA
 * into the Chrome mobile UA for the same Chrome build — nothing else changes,
 * so chat.z.ai still sees a coherent Android Chrome profile (and the same one
 * in both WebViews).
 */
object ZaiSheetUserAgent {

    /**
     * Transforms Android's default WebView UA into the equivalent Chrome mobile
     * UA. Returns the input unchanged when the result would be blank.
     */
    fun chromeLike(defaultUserAgent: String): String {
        if (defaultUserAgent.isBlank()) return defaultUserAgent
        return defaultUserAgent
            .replace("; wv", "")
            .replace("Version/4.0 ", "")
            .trim()
            .takeIf { it.isNotEmpty() }
            ?: defaultUserAgent
    }
}
