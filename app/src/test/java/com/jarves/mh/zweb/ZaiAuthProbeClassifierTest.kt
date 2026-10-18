package com.jarves.mh.zweb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the session-probe classifier. The shapes mirror what the live
 * site returns (guest minting verified 2026-10-18; user shapes per the prod
 * bundle's own session check).
 */
class ZaiAuthProbeClassifierTest {

    private fun source(
        shape: String = "user",
        role: String = "",
        emailPresent: Boolean = false,
        guest: Boolean = false,
        status: Int? = 200,
        error: String = "",
    ) = ZaiAuthProbeClassifier.ZaiAuthProbeSource(
        httpStatus = status,
        shape = shape,
        role = role,
        emailPresent = emailPresent,
        guest = guest,
        errorText = error,
    )

    // ── signed-in paths ──

    @Test
    fun `bearer fetch confirming a real user wins`() {
        val result = ZaiAuthProbeClassifier.ZaiAuthProbeResult(
            hasLocalToken = true,
            bearer = source(role = "user", emailPresent = true),
            cookie = source(shape = "user", role = "guest", emailPresent = true, guest = true),
        )
        val v = ZaiAuthProbeClassifier.classify(result)
        assertTrue(v.signedIn)
        assertEquals(ZaiAuthProbeClassifier.ZaiCheckOutcome.SIGNED_IN_BEARER, v.outcome)
        assertEquals("user", v.role)
        assertTrue(v.emailPresent)
        assertTrue(v.hasLocalToken)
    }

    @Test
    fun `cookie fetch alone can confirm sign-in when bearer is a guest`() {
        val result = ZaiAuthProbeClassifier.ZaiAuthProbeResult(
            hasLocalToken = false,
            bearer = source(shape = "user", role = "guest", emailPresent = true, guest = true),
            cookie = source(role = "admin", emailPresent = true),
        )
        val v = ZaiAuthProbeClassifier.classify(result)
        assertTrue(v.signedIn)
        assertEquals(ZaiAuthProbeClassifier.ZaiCheckOutcome.SIGNED_IN_COOKIE, v.outcome)
        assertEquals("admin", v.role)
    }

    @Test
    fun `pending role counts as signed in (new OAuth users are not guests)`() {
        val result = ZaiAuthProbeClassifier.ZaiAuthProbeResult(
            hasLocalToken = true,
            bearer = source(role = "pending", emailPresent = true),
            cookie = null,
        )
        val v = ZaiAuthProbeClassifier.classify(result)
        assertTrue(v.signedIn)
    }

    @Test
    fun `both fetches signed-in prefers the bearer outcome`() {
        val result = ZaiAuthProbeClassifier.ZaiAuthProbeResult(
            hasLocalToken = true,
            bearer = source(role = "user", emailPresent = true),
            cookie = source(role = "user", emailPresent = true),
        )
        val v = ZaiAuthProbeClassifier.classify(result)
        assertEquals(ZaiAuthProbeClassifier.ZaiCheckOutcome.SIGNED_IN_BEARER, v.outcome)
    }

    // ── signed-out paths ──

    @Test
    fun `guest role reads as signed out`() {
        val result = ZaiAuthProbeClassifier.ZaiAuthProbeResult(
            hasLocalToken = false,
            bearer = source(shape = "user", role = "guest", emailPresent = true, guest = true),
            cookie = source(shape = "user", role = "guest", emailPresent = true, guest = true),
        )
        val v = ZaiAuthProbeClassifier.classify(result)
        assertFalse(v.signedIn)
        assertEquals(ZaiAuthProbeClassifier.ZaiCheckOutcome.GUEST, v.outcome)
        assertEquals("guest", v.role)
    }

    @Test
    fun `guest email pattern reads as signed out even with a non-guest role`() {
        val result = ZaiAuthProbeClassifier.ZaiAuthProbeResult(
            hasLocalToken = false,
            bearer = source(shape = "user", role = "user", emailPresent = true, guest = true),
            cookie = null,
        )
        val v = ZaiAuthProbeClassifier.classify(result)
        assertFalse(v.signedIn)
        assertEquals(ZaiAuthProbeClassifier.ZaiCheckOutcome.GUEST, v.outcome)
    }

    @Test
    fun `detail shape (API error body) is never read as signed in`() {
        // Regression: the pre-v1.0.21 probe classified {detail:"Not authenticated"}
        // as SIGNED IN because it only checked !j.error && !guest.
        val result = ZaiAuthProbeClassifier.ZaiAuthProbeResult(
            hasLocalToken = true,
            bearer = source(shape = "detail", status = 401, error = "Not authenticated"),
            cookie = null,
        )
        val v = ZaiAuthProbeClassifier.classify(result)
        assertFalse(v.signedIn)
        assertEquals(ZaiAuthProbeClassifier.ZaiCheckOutcome.GUEST, v.outcome)
        assertEquals("Not authenticated", v.errorText)
    }

    @Test
    fun `blank role with user shape is not a confirmed user`() {
        val result = ZaiAuthProbeClassifier.ZaiAuthProbeResult(
            hasLocalToken = false,
            bearer = source(shape = "user", role = ""),
            cookie = null,
        )
        val v = ZaiAuthProbeClassifier.classify(result)
        assertFalse(v.signedIn)
        assertEquals(ZaiAuthProbeClassifier.ZaiCheckOutcome.GUEST, v.outcome)
    }

    // ── error paths ──

    @Test
    fun `both fetches erroring is a probe error, not signed out`() {
        val result = ZaiAuthProbeClassifier.ZaiAuthProbeResult(
            hasLocalToken = true,
            bearer = source(shape = "error", status = null, error = "TypeError: Failed to fetch"),
            cookie = source(shape = "error", status = null, error = "TypeError: Failed to fetch"),
        )
        val v = ZaiAuthProbeClassifier.classify(result)
        assertFalse(v.signedIn)
        assertEquals(ZaiAuthProbeClassifier.ZaiCheckOutcome.PROBE_ERROR, v.outcome)
        assertTrue(v.errorText.contains("Failed to fetch"))
    }

    @Test
    fun `null result is a probe error`() {
        val v = ZaiAuthProbeClassifier.classify(null)
        assertFalse(v.signedIn)
        assertEquals(ZaiAuthProbeClassifier.ZaiCheckOutcome.PROBE_ERROR, v.outcome)
    }

    @Test
    fun `one erroring fetch does not hide the other fetchs guest answer`() {
        val result = ZaiAuthProbeClassifier.ZaiAuthProbeResult(
            hasLocalToken = false,
            bearer = source(shape = "error", status = null, error = "TypeError: network"),
            cookie = source(shape = "user", role = "guest", emailPresent = true, guest = true),
        )
        val v = ZaiAuthProbeClassifier.classify(result)
        assertFalse(v.signedIn)
        assertEquals(ZaiAuthProbeClassifier.ZaiCheckOutcome.GUEST, v.outcome)
    }

    // ── parsing ──

    @Test
    fun `parseEvalResult reads the combined probe payload`() {
        val json = """
            {"signedIn":true,"hasLocalToken":true,
             "bearer":{"status":200,"shape":"user","role":"user","emailPresent":true,"guest":false,"error":""},
             "cookie":{"status":200,"shape":"user","role":"guest","emailPresent":true,"guest":true,"error":""}}
        """.trimIndent()
        val parsed = ZaiAuthProbeClassifier.parseEvalResult(json)
        assertTrue(parsed != null)
        assertTrue(parsed!!.hasLocalToken)
        assertEquals("user", parsed.bearer!!.role)
        assertTrue(parsed.cookie!!.guest)
        val v = ZaiAuthProbeClassifier.classify(parsed)
        assertTrue(v.signedIn)
    }

    @Test
    fun `parseEvalResult unwraps the WebView JSON quoting`() {
        val inner = """{"signedIn":false,"hasLocalToken":false,
             "bearer":{"status":null,"shape":"error","role":"","emailPresent":false,"guest":false,"error":"x"},
             "cookie":{"status":200,"shape":"user","role":"guest","emailPresent":true,"guest":true,"error":""}}"""
        val quoted = org.json.JSONObject.quote(inner)
        val parsed = ZaiAuthProbeClassifier.parseEvalResult(quoted)
        assertTrue(parsed != null)
        assertFalse(ZaiAuthProbeClassifier.classify(parsed).signedIn)
    }

    @Test
    fun `parseEvalResult rejects garbage and null`() {
        assertEquals(null, ZaiAuthProbeClassifier.parseEvalResult(null))
        assertEquals(null, ZaiAuthProbeClassifier.parseEvalResult(""))
        assertEquals(null, ZaiAuthProbeClassifier.parseEvalResult("not json"))
        assertEquals(null, ZaiAuthProbeClassifier.parseEvalResult("\"random string\""))
    }

    // ── summary ──

    @Test
    fun `summary never contains an email value or token`() {
        val v = ZaiAuthProbeClassifier.classify(
            ZaiAuthProbeClassifier.ZaiAuthProbeResult(
                hasLocalToken = true,
                bearer = source(role = "user", emailPresent = true),
                cookie = null,
            ),
        )
        val s = ZaiAuthProbeClassifier.summary(v)
        assertTrue(s.contains("role=user"))
        assertTrue(s.contains("email=yes"))
        assertTrue(s.contains("outcome=signed-in-bearer"))
        assertTrue(s.contains("localToken=yes"))
        assertFalse(s.contains("@"))
    }

    @Test
    fun `summary carries truncated error text for diagnostics`() {
        val v = ZaiAuthProbeClassifier.classify(
            ZaiAuthProbeClassifier.ZaiAuthProbeResult(
                hasLocalToken = false,
                bearer = source(shape = "error", status = null, error = "boom"),
                cookie = source(shape = "error", status = null, error = "boom"),
            ),
        )
        assertTrue(ZaiAuthProbeClassifier.summary(v).contains("error=boom"))
    }
}
