package com.jarves.mh.zweb

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Builds the copyable Diagnostics text for the Z.ai tab.
 *
 * Every input is secret-free by construction: cookie NAMES (never values),
 * presence booleans, role strings, outcome labels, URLs and user agents.
 * This is the tool that turns "the card says Not signed in" into a report
 * that shows exactly which branch of the connection check fired and what
 * each WebView saw — see AGENT_LOG.md v1.0.21 for the investigation that
 * motivated it.
 */
object ZaiDiagnostics {

    data class Data(
        val generatedAtMillis: Long,
        val lastCheckAtMillis: Long?,
        val lastCheckOutcome: String?,
        val lastProbeSummary: String?,
        val signedIn: Boolean?,
        val engineCreated: Boolean,
        val engineLastUrl: String?,
        val engineUserAgent: String?,
        val sheetLastUrl: String?,
        val sheetUserAgent: String?,
        val sheetProbeSummary: String?,
        val sheetVerifiedAtMillis: Long?,
        val configVersion: Int,
        /** Pre-rendered "domain: name, name" lines — names only, never values. */
        val cookieLines: List<String>,
        val previousRunOutcome: String?,
        val previousRunSignedIn: Boolean?,
    )

    fun build(data: Data, nowMillis: Long): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        val sb = StringBuilder()
        sb.append("Z.ai diagnostics — ${fmt.format(Date(data.generatedAtMillis))}\n")
        sb.append("card shows: ${when (data.signedIn) {
            true -> "Connected"
            false -> "Not signed in"
            null -> "unknown (no successful check)"
        }}\n")
        sb.append("\n[last connection check]\n")
        sb.append("outcome: ${data.lastCheckOutcome ?: "not run yet"}\n")
        sb.append("at: ${data.lastCheckAtMillis?.let { fmt.format(Date(it)) } ?: "-"}\n")
        sb.append("probe: ${data.lastProbeSummary ?: "-"}\n")

        sb.append("\n[hidden engine WebView]\n")
        sb.append("created: ${if (data.engineCreated) "yes" else "no (created on first check)"}\n")
        sb.append("last URL: ${data.engineLastUrl ?: "-"}\n")
        sb.append("user agent: ${data.engineUserAgent ?: "-"}\n")

        sb.append("\n[sign-in sheet WebView]\n")
        if (data.sheetLastUrl == null && data.sheetUserAgent == null && data.sheetProbeSummary == null) {
            sb.append("not opened this session\n")
        } else {
            sb.append("last URL: ${data.sheetLastUrl ?: "-"}\n")
            sb.append("user agent: ${data.sheetUserAgent ?: "-"}\n")
            sb.append("probe: ${data.sheetProbeSummary ?: "-"}\n")
        }
        sb.append(
            "sheet saw a signed-in session: " + (data.sheetVerifiedAtMillis?.let {
                "yes at ${fmt.format(Date(it))}"
            } ?: "no") + "\n",
        )

        sb.append("\n[cookies for chat.z.ai / z.ai — NAMES only, values are never shown]\n")
        if (data.cookieLines.isEmpty()) {
            sb.append("(unavailable)\n")
        } else {
            data.cookieLines.forEach { sb.append("$it\n") }
        }
        sb.append(
            "note: the server sets the session token cookie as a SESSION cookie " +
                "(no Expires/Max-Age, live-verified); the Android WebView API does not " +
                "expose expiry attributes per cookie, so session-vs-persistent cannot be " +
                "listed here. The site keeps its session token in localStorage.\n",
        )

        sb.append("\n[previous app run]\n")
        sb.append(
            "check outcome: ${data.previousRunOutcome ?: "no record"}\n" +
                "signed in: ${when (data.previousRunSignedIn) {
                    true -> "yes"
                    false -> "no"
                    null -> "unknown"
                }}\n",
        )
        sb.append("selector config version: v${data.configVersion}\n")
        return sb.toString()
    }
}
