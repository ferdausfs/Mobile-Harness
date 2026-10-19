package com.jarves.mh.tokens

/**
 * Redacts token values and token-shaped strings out of anything that leaves
 * the secure store (Phase 2, hard requirement):
 *
 *  - prompts sent to chat.z.ai (the model NEVER sees a token value, only
 *    variable NAMES like "$GITHUB_TOKEN is available"),
 *  - chat transcripts, command output, logs, crash reports, diagnostics,
 *    clipboard exports.
 *
 * Two layers:
 *  1. LITERAL: every stored token value is replaced by [MASK] wherever it
 *     appears (substring match, case-sensitive).
 *  2. PATTERN: common token shapes are masked even when the vault does not
 *     know them — GitHub (ghp_/gho_/ghu_/ghs_/ghr_/github_pat_), GitLab
 *     (glpat-), and generic `token/key/secret/password/bearer/authorization`
 *     assignments, Authorization headers, Basic auth, and URL query params
 *     (token=… / access_token=… / api_key=…).
 *
 * Deliberate non-goals: variable NAMES are never redacted ("$GITHUB_TOKEN is
 * available" must survive), and bare long hex/base64 WITHOUT a keyword or
 * known prefix is left alone (sha256 digests and normal hashes must not be
 * destroyed in logs).
 */
object SecretRedactor {

    const val MASK = "***"

    /** Minimum length for a literal secret to be worth masking. */
    const val MIN_LITERAL_LENGTH = 8

    private val GITHUB_PATTERN = Regex("\\b(?:gh[pousr]_[A-Za-z0-9]{16,}|github_pat_[A-Za-z0-9_]{16,})\\b")
    private val GITLAB_PATTERN = Regex("\\bglpat-[A-Za-z0-9_\\-]{15,}\\b")

    /**
     * token/key/secret/password/… assignments: JSON, YAML, shell, headers.
     * Compound names (AUTH_TOKEN, ACCESS_TOKEN, API_KEY, SECRET_KEY) count too.
     */
    private val KEYWORD_ASSIGNMENT = Regex(
        "(?i)\\b((?:api|auth|access|refresh|session|bot|registry|deploy)[_\\-]?)?(token|key|secret|password|passwd|pwd)\\b[\"']?\\s*[:=]\\s*[\"']?([A-Za-z0-9_\\-.+/=]{12,})",
    )

    /** Authorization: Bearer xyz / Authorization: token xyz. */
    private val AUTHORIZATION_HEADER = Regex(
        "(?i)(authorization\\s*:\\s*)(?:bearer|token|basic)?\\s*([A-Za-z0-9_\\-.+/=]{10,})",
    )

    /** URL query: ?token=… &access_token=… &api_key=… */
    private val URL_QUERY_SECRET = Regex(
        "(?i)([?&](?:token|key|secret|access[_\\-]?token|api[_\\-]?key|apikey|password|sig|signature)=)([A-Za-z0-9_\\-.%+/=]{6,})",
    )

    /** Basic <base64>. */
    private val BASIC_AUTH = Regex("(?i)(basic\\s+)([A-Za-z0-9+/=]{16,})")

    /** Replaces every literal [secrets] occurrence with [MASK]. */
    fun redactLiterals(text: String, secrets: Iterable<String>): String {
        var out = text
        secrets.forEach { secret ->
            val value = secret.trim()
            if (value.length >= MIN_LITERAL_LENGTH) {
                out = out.replace(value, MASK)
            }
        }
        return out
    }

    /** Masks token-shaped strings the vault does not know about. */
    fun redactPatterns(text: String): String {
        var out = text
        out = GITHUB_PATTERN.replace(out, MASK)
        out = GITLAB_PATTERN.replace(out, MASK)
        out = KEYWORD_ASSIGNMENT.replace(out) { match ->
            match.value.dropLast(match.groupValues[3].length) + MASK
        }
        out = AUTHORIZATION_HEADER.replace(out) { match ->
            match.groupValues[1] + MASK
        }
        out = URL_QUERY_SECRET.replace(out) { match ->
            match.groupValues[1] + MASK
        }
        out = BASIC_AUTH.replace(out) { match ->
            match.groupValues[1] + MASK
        }
        return out
    }

    /** Full redaction: literals first (exact values), then patterns. */
    fun redact(text: String, secrets: Iterable<String>): String =
        redactPatterns(redactLiterals(text, secrets))
}
