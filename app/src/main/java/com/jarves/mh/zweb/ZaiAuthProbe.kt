package com.jarves.mh.zweb

import org.json.JSONArray
import org.json.JSONObject

/**
 * Session-probe result model + pure classifier for the chat.z.ai sign-in state.
 *
 * Why this exists (v1.0.21 investigation, evidence in AGENT_LOG.md):
 *
 *  - chat.z.ai's own frontend keeps the session JWT in `localStorage.token`
 *    and authorizes its API calls with `Authorization: Bearer <token>`
 *    (prod bundle prod-fe-1.1.107: `localStorage.token` read 89×, Bearer
 *    headers on every API fetch, sign-out = `localStorage.removeItem("token")`).
 *  - The server also mirrors the session into a `token` cookie, but sets it
 *    with NO Expires/Max-Age (session-scoped; live `Set-Cookie` header).
 *  - A request that arrives with neither a valid cookie nor a Bearer token is
 *    answered with a FRESH GUEST user (`role:"guest"`, HTTP 200) instead of a
 *    401 — so a cookie-only probe reports "guest" even right after a
 *    successful sign-in whose token only reached localStorage.
 *
 * The probe therefore mirrors the site's own session check: a fetch with
 * `Authorization: Bearer <localStorage.token>` (when present) plus a
 * cookie-only fetch, and either one confirming a real user means signed in.
 *
 * Secrets: the probe JS never returns the token value or the account email —
 * only presence booleans, the role string, and truncated error text.
 */
object ZaiAuthProbeClassifier {

    /** One of the two probe fetches (Bearer-from-localStorage, or cookie-only). */
    data class ZaiAuthProbeSource(
        val httpStatus: Int?,
        /** "user" (a JSON user object), "detail" (API error object), "empty", "error". */
        val shape: String,
        val role: String,
        val emailPresent: Boolean,
        val guest: Boolean,
        val errorText: String,
    ) {
        /** A real (non-guest) authenticated user, as opposed to guest/error shapes. */
        fun isConfirmedUser(): Boolean = shape == SHAPE_USER && !guest && role.isNotBlank()
    }

    /** Combined result of the two probe fetches. */
    data class ZaiAuthProbeResult(
        val hasLocalToken: Boolean,
        val bearer: ZaiAuthProbeSource?,
        val cookie: ZaiAuthProbeSource?,
        /** Account display fields for the card (UI state only — never logged). */
        val accountName: String? = null,
        val accountEmail: String? = null,
    )

    /** Final decision + the fields the Diagnostics report shows (no secrets). */
    data class ZaiAuthVerdict(
        val signedIn: Boolean,
        /** One of [ZaiCheckOutcome.SIGNED_IN_BEARER], [SIGNED_IN_COOKIE], [GUEST], [PROBE_ERROR]. */
        val outcome: String,
        val role: String,
        val emailPresent: Boolean,
        val hasLocalToken: Boolean,
        val errorText: String,
        /** Account display fields from the confirming source (UI state only). */
        val accountName: String? = null,
        val accountEmail: String? = null,
    )

    /** Outcome labels used by the connection check and the Diagnostics report. */
    object ZaiCheckOutcome {
        const val SIGNED_IN_BEARER = "signed-in-bearer"
        const val SIGNED_IN_COOKIE = "signed-in-cookie"
        const val GUEST = "probe-guest"
        const val PROBE_ERROR = "probe-error"
        const val LOGIN_PAGE = "login-page"
        const val CAPTCHA = "captcha"
        const val PAGE_TIMEOUT = "page-timeout"
        const val CONFIG_MISSING = "config-missing"
        const val CHECK_ERROR = "check-error"
        const val SIGNED_OUT_BY_USER = "signed-out-by-user"
    }

    private const val SHAPE_USER = "user"
    private const val SHAPE_DETAIL = "detail"
    private const val SHAPE_EMPTY = "empty"
    private const val SHAPE_ERROR = "error"

    /**
     * Pure decision: a real user confirmed by either fetch wins; otherwise the
     * state is "guest" when at least one fetch answered, or "probe-error" when
     * neither did. A `detail` shape (API error / 401 body) is NEVER read as a
     * signed-in user — the pre-v1.0.21 probe classified such bodies as
     * signed-in because it only checked `!j.error && !guest`.
     */
    fun classify(result: ZaiAuthProbeResult?): ZaiAuthVerdict {
        if (result == null) {
            return ZaiAuthVerdict(
                signedIn = false,
                outcome = ZaiCheckOutcome.PROBE_ERROR,
                role = "",
                emailPresent = false,
                hasLocalToken = false,
                errorText = "probe did not return a readable result",
            )
        }
        val confirming = listOfNotNull(result.bearer, result.cookie).firstOrNull { it.isConfirmedUser() }
        if (confirming != null) {
            return ZaiAuthVerdict(
                signedIn = true,
                outcome = if (confirming === result.bearer) {
                    ZaiCheckOutcome.SIGNED_IN_BEARER
                } else {
                    ZaiCheckOutcome.SIGNED_IN_COOKIE
                },
                role = confirming.role,
                emailPresent = confirming.emailPresent,
                hasLocalToken = result.hasLocalToken,
                errorText = "",
                accountName = result.accountName?.takeIf(String::isNotBlank),
                accountEmail = result.accountEmail?.takeIf(String::isNotBlank),
            )
        }
        val answered = listOfNotNull(result.bearer, result.cookie).filter { it.shape != SHAPE_ERROR }
        if (answered.isEmpty()) {
            return ZaiAuthVerdict(
                signedIn = false,
                outcome = ZaiCheckOutcome.PROBE_ERROR,
                role = "",
                emailPresent = false,
                hasLocalToken = result.hasLocalToken,
                errorText = listOfNotNull(result.bearer, result.cookie)
                    .mapNotNull { it.errorText.takeIf(String::isNotBlank) }
                    .firstOrNull()
                    ?: "both probe fetches failed",
            )
        }
        val informant = result.bearer ?: result.cookie ?: answered.first()
        return ZaiAuthVerdict(
            signedIn = false,
            outcome = ZaiCheckOutcome.GUEST,
            role = informant.role,
            emailPresent = informant.emailPresent,
            hasLocalToken = result.hasLocalToken,
            errorText = informant.errorText,
        )
    }

    /**
     * One-line, secret-free summary for UI state and the Diagnostics report,
     * e.g. `role=user email=yes via=bearer localToken=yes`.
     */
    fun summary(verdict: ZaiAuthVerdict): String = buildString {
        append("role=")
        append(verdict.role.ifBlank { "-" })
        append(" email=")
        append(if (verdict.emailPresent) "yes" else "no")
        append(" outcome=")
        append(verdict.outcome)
        append(" localToken=")
        append(if (verdict.hasLocalToken) "yes" else "no")
        if (verdict.errorText.isNotBlank()) {
            append(" error=")
            append(verdict.errorText.take(120))
        }
    }

    /**
     * Parses the raw `evaluateJavascript` payload of the session probe script
     * ([ZaiProbeScripts.buildAuthProbeForPath]). Handles the one level of JSON
     * quoting WebView adds. Returns null when unreadable — callers treat that
     * as a probe error, never as "signed out".
     */
    fun parseEvalResult(raw: String?): ZaiAuthProbeResult? {
        if (raw.isNullOrBlank()) return null
        val trimmed = raw.trim()
        val inner = if (trimmed.length >= 2 && trimmed.first() == '"' && trimmed.last() == '"') {
            runCatching { JSONArray("[$trimmed]").getString(0) }.getOrDefault(trimmed)
        } else {
            trimmed
        }
        return runCatching {
            val obj = JSONObject(inner)
            if (!obj.optBoolean("signedIn", false) &&
                !obj.has("bearer") && !obj.has("cookie") && !obj.has("hasLocalToken")
            ) {
                // Not the combined shape at all — old/foreign payload.
                return@runCatching null
            }
            ZaiAuthProbeResult(
                hasLocalToken = obj.optBoolean("hasLocalToken", false),
                bearer = parseSource(obj.optJSONObject("bearer")),
                cookie = parseSource(obj.optJSONObject("cookie")),
                accountName = obj.optJSONObject("account")?.optString("name", "")?.take(120)?.ifBlank { null },
                accountEmail = obj.optJSONObject("account")?.optString("email", "")?.take(120)?.ifBlank { null },
            )
        }.getOrNull()
    }

    private fun parseSource(obj: JSONObject?): ZaiAuthProbeSource? {
        obj ?: return null
        return ZaiAuthProbeSource(
            httpStatus = if (obj.isNull("status")) null else obj.optInt("status").takeIf { it > 0 },
            shape = obj.optString("shape", SHAPE_ERROR),
            role = obj.optString("role", ""),
            emailPresent = obj.optBoolean("emailPresent", false),
            guest = obj.optBoolean("guest", false),
            errorText = obj.optString("error", "").take(160),
        )
    }
}
