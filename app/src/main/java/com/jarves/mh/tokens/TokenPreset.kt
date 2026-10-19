package com.jarves.mh.tokens

/**
 * Named token presets for the Tokens vault (Phase 2).
 *
 * A preset knows: which environment variable names the token value maps to
 * (the model only ever sees these NAMES — never the value), where the user
 * manages/rotates the token, and one READ-ONLY endpoint that proves the
 * token works. Test endpoints are deliberately cheap, documented GETs.
 */
data class TokenPreset(
    val id: String,
    val title: String,
    /** Environment variable names, primary first (e.g. GITHUB_TOKEN). */
    val envVars: List<String>,
    /** Provider page where the user creates/rotates/deletes tokens. */
    val managementUrl: String?,
    /** Read-only verification endpoint; null = no test (custom tokens). */
    val test: TokenTestSpec?,
    val hint: String,
)

data class TokenTestSpec(
    val url: String,
    /** Auth header style used for the test call. */
    val auth: AuthStyle,
)

enum class AuthStyle { BEARER, TOKEN }

object TokenPresets {

    const val CUSTOM_ID = "custom"

    val GITHUB = TokenPreset(
        id = "github",
        title = "GitHub",
        envVars = listOf("GITHUB_TOKEN"),
        managementUrl = "https://github.com/settings/tokens",
        test = TokenTestSpec("https://api.github.com/user", AuthStyle.BEARER),
        hint = "Classic (ghp_…) or fine-grained (github_pat_…) personal access token. Read-only test calls /user.",
    )

    val CLOUDFLARE = TokenPreset(
        id = "cloudflare",
        title = "Cloudflare",
        envVars = listOf("CLOUDFLARE_API_TOKEN", "CLOUDFLARE_ACCOUNT_ID"),
        managementUrl = "https://dash.cloudflare.com/profile/api-tokens",
        test = TokenTestSpec("https://api.cloudflare.com/client/v4/user/tokens/verify", AuthStyle.BEARER),
        hint = "API token (not the Global API Key). Account ID is optional; both are injected as env vars.",
    )

    val VERCEL = TokenPreset(
        id = "vercel",
        title = "Vercel",
        envVars = listOf("VERCEL_TOKEN"),
        managementUrl = "https://vercel.com/account/settings/tokens",
        test = TokenTestSpec("https://api.vercel.com/v2/user", AuthStyle.BEARER),
        hint = "Personal access token created on vercel.com. Read-only test calls /v2/user.",
    )

    val FIREBASE = TokenPreset(
        id = "firebase",
        title = "Firebase",
        envVars = listOf("FIREBASE_TOKEN"),
        managementUrl = "https://console.firebase.google.com/",
        test = TokenTestSpec("https://firebasehosting.googleapis.com/v1beta1/sites?pageSize=1", AuthStyle.BEARER),
        hint = "CI token from `firebase login:ci` (or a Google OAuth access token). Rotating = re-run login:ci.",
    )

    val SUPABASE = TokenPreset(
        id = "supabase",
        title = "Supabase",
        envVars = listOf("SUPABASE_ACCESS_TOKEN"),
        managementUrl = "https://supabase.com/dashboard/account/tokens",
        test = TokenTestSpec("https://api.supabase.com/v1/projects", AuthStyle.BEARER),
        hint = "Personal access token from the Supabase dashboard (management API).",
    )

    val NPM = TokenPreset(
        id = "npm",
        title = "npm",
        envVars = listOf("NPM_TOKEN"),
        managementUrl = "https://www.npmjs.com/settings/~/tokens",
        test = TokenTestSpec("https://registry.npmjs.org/-/user/me", AuthStyle.BEARER),
        hint = "Automation or publish token from npmjs.com. Read-only test calls the registry whoami endpoint.",
    )

    val CUSTOM = TokenPreset(
        id = CUSTOM_ID,
        title = "Custom",
        envVars = emptyList(),
        managementUrl = null,
        test = null,
        hint = "Any named secret with a custom environment variable name (e.g. HF_TOKEN, DO_API_TOKEN).",
    )

    val ALL = listOf(GITHUB, CLOUDFLARE, VERCEL, FIREBASE, SUPABASE, NPM, CUSTOM)

    fun byId(id: String): TokenPreset = ALL.firstOrNull { it.id == id } ?: CUSTOM

    /** True when the given env var name is syntactically usable (A-Z, digits, underscore). */
    fun isValidEnvVarName(name: String): Boolean =
        name.matches(Regex("[A-Z][A-Z0-9_]{1,63}"))
}
