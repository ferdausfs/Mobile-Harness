package com.jarves.mh.zweb

/**
 * User-agent override for the VISIBLE sign-in sheet only (the hidden engine
 * WebView keeps Android's default WebView UA).
 *
 * Why: Google rejects OAuth sign-in from embedded browsers whose UA carries
 * the WebView markers (`; wv`, `Version/4.0`) with "disallowed_useragent".
 * Live probe (2026-10-18, headless Chromium, mobile viewport): the Google
 * account chooser loaded fine under both the WebView UA and a Chrome UA, but
 * the Chrome-like UA is the safest bet on real devices where Google's risk
 * engine may differ. Stripping only those two markers turns the WebView UA
 * into the Chrome mobile UA for the same Chrome build — nothing else changes,
 * so chat.z.ai still sees a coherent Android Chrome profile.
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
