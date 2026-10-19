package com.jarves.mh.tokens

import org.json.JSONObject

/**
 * Pure classifier for the "Test token" button (Phase 2). Turns a read-only
 * probe response into a human sentence. Never throws, never leaks the token:
 * the token value itself can only appear in an echoed 401 body, which is
 * reduced to a bounded, masked fragment.
 */
object TokenTestClassifier {

    /** Body fragments are bounded before they reach the UI. */
    const val MAX_BODY_FRAGMENT = 160

    data class Outcome(
        val valid: Boolean,
        /** One human sentence for the UI row. Secret-free by construction. */
        val human: String,
    )

    fun classify(preset: TokenPreset, httpStatus: Int?, body: String?, error: String?): Outcome {
        // 1. Network / client failure: no HTTP status at all.
        if (httpStatus == null) {
            val why = error?.take(MAX_BODY_FRAGMENT)?.ifBlank { null } ?: "unknown network error"
            return Outcome(
                valid = false,
                human = "Could not reach ${hostOf(preset)} to verify ($why). Check the connection and try again.",
            )
        }
        // 2. Auth failures.
        if (httpStatus == 401 || httpStatus == 403) {
            return Outcome(
                valid = false,
                human = "Token rejected by ${hostOf(preset)} (HTTP $httpStatus) — expired, revoked, or missing scope. Create a fresh one.",
            )
        }
        // 3. Success: parse the per-provider shape for a human detail.
        if (httpStatus in 200..299) {
            return validOutcome(preset, body)
        }
        // 4. Anything else: inconclusive but honest.
        val fragment = runCatching { SecretRedactor.redactPatterns(body.orEmpty().take(MAX_BODY_FRAGMENT)) }
            .getOrDefault("")
        return Outcome(
            valid = false,
            human = "Verification inconclusive (HTTP $httpStatus)${if (fragment.isBlank()) "" else ": $fragment"}",
        )
    }

    private fun validOutcome(preset: TokenPreset, body: String?): Outcome {
        val json = runCatching { JSONObject(body ?: "") }.getOrNull()
        val detail = when (preset.id) {
            "github" -> json?.optString("login", "")?.takeIf(String::isNotEmpty)?.let { " — signed in as $it" }
            "cloudflare" -> {
                if (json != null && !json.optBoolean("success", false)) {
                    return Outcome(valid = false, human = "Cloudflare says the token is not active (success:false). Create a fresh one.")
                }
                " — token is active"
            }
            "vercel" -> json?.optJSONObject("user")?.optString("username", "")?.takeIf(String::isNotEmpty)?.let { " — signed in as $it" }
            "npm" -> json?.optString("name", "")?.takeIf(String::isNotEmpty)?.let { " — signed in as $it" }
            "supabase" -> {
                val projects = runCatching { org.json.JSONArray(body ?: "") }.getOrNull()?.length() ?: -1
                if (projects >= 0) " — can see $projects project${if (projects == 1) "" else "s"}" else " — token accepted"
            }
            "firebase" -> " — token accepted by Firebase Hosting API"
            else -> " — token accepted"
        } ?: " — token accepted"
        return Outcome(valid = true, human = "Token works${detail}.")
    }

    private fun hostOf(preset: TokenPreset): String =
        runCatching { java.net.URI(preset.test!!.url).host }.getOrDefault(preset.title)
}
