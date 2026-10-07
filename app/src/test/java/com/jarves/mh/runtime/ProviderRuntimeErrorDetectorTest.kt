package com.jarves.mh.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProviderRuntimeErrorDetectorTest {
    @Test
    fun userNotFoundIsFatal() {
        assertEquals(
            "User not found. Check the API key and provider account.",
            ProviderRuntimeErrorDetector.detect("Failed to authenticate. API Error: 401 User not found."),
        )
    }

    @Test
    fun authenticationRetryIsFatalImmediately() {
        val event = """{"type":"system","subtype":"api_retry","attempt":1,"error_status":401,"error":"authentication_failed"}"""
        assertEquals(
            "The provider rejected the saved API key.",
            ProviderRuntimeErrorDetector.detect(event),
        )
    }

    @Test
    fun ordinaryRuntimeOutputIsNotFatal() {
        assertNull(ProviderRuntimeErrorDetector.detect("Claude Code connected"))
    }

    @Test
    fun toolResultMentioningRateLimitDoesNotKillSession() {
        // Regression: an agent reading or grepping code that contains "rate limit"
        // used to destroy a healthy session and trigger a bogus provider failover.
        val toolResult = """{"type":"user","message":{"role":"user","content":[{"type":"tool_result","tool_use_id":"t1","content":"grep hits: rate limit exceeded, token expired, quota"}]}}"""
        assertNull(ProviderRuntimeErrorDetector.detect(toolResult))
    }

    @Test
    fun assistantTextMentioningQuotaDoesNotKillSession() {
        val assistant = """{"type":"assistant","message":{"role":"assistant","content":[{"type":"text","text":"The docs mention quota handling."}]}}"""
        assertNull(ProviderRuntimeErrorDetector.detect(assistant))
    }

    @Test
    fun successfulResultIsNotFatalEvenWithScaryWords() {
        val result = """{"type":"result","subtype":"success","is_error":false,"result":"analyzed rate limit code","session_id":"s1"}"""
        assertNull(ProviderRuntimeErrorDetector.detect(result))
    }

    @Test
    fun resultError429IsRateLimitNotAuth() {
        // Regression: 429 used to be worded as a key rejection, which made the
        // live status card show AUTH_ERROR instead of LIMIT.
        val result = """{"type":"result","subtype":"error_during_execution","is_error":true,"result":"API Error: 429 rate limit exceeded","session_id":"s1"}"""
        assertEquals(
            "The provider is rate limiting requests.",
            ProviderRuntimeErrorDetector.detect(result),
        )
    }

    @Test
    fun resultError402IsCredits() {
        val result = """{"type":"result","subtype":"error_during_execution","is_error":true,"result":"HTTP 402 Payment Required: insufficient credits","session_id":"s1"}"""
        assertEquals(
            "The provider reports insufficient credits or quota.",
            ProviderRuntimeErrorDetector.detect(result),
        )
    }

    @Test
    fun stderrLine429IsRateLimit() {
        assertEquals(
            "The provider is rate limiting requests.",
            ProviderRuntimeErrorDetector.detect("API Error: 429 {\"requestId\":\"req_1\"}"),
        )
    }

    @Test
    fun apiRetry429IsRateLimit() {
        val event = """{"type":"system","subtype":"api_retry","attempt":2,"error_status":429,"error":"rate_limit_error"}"""
        assertEquals(
            "The provider is rate limiting requests.",
            ProviderRuntimeErrorDetector.detect(event),
        )
    }

    @Test
    fun resultError401IsStillAuth() {
        val result = """{"type":"result","subtype":"error_during_execution","is_error":true,"result":"API Error: 401 invalid api key","session_id":"s1"}"""
        assertEquals(
            "The provider rejected the saved API key.",
            ProviderRuntimeErrorDetector.detect(result),
        )
    }

    @Test
    fun stderrLineWithLooseQuotaAndExpiredWordsIsNotFatal() {
        // Regression: the non-JSON branch used to scan arbitrary stderr lines
        // for "quota"/"expired"/"rate limit" and kill healthy sessions.
        assertNull(
            ProviderRuntimeErrorDetector.detect(
                "warn: storage quota check skipped, cache entry expired yesterday, see rate limit docs",
            ),
        )
    }

    @Test
    fun bareStatusNumberInDiagnosticsIsNotFatal() {
        // Regression: a standalone 401/429 (line numbers, durations, IDs) used
        // to be read as an HTTP status and trigger a provider failover.
        assertNull(ProviderRuntimeErrorDetector.detect("error at build.gradle line 401: unexpected symbol"))
        assertNull(ProviderRuntimeErrorDetector.detect("retrying model call in 429 ms"))
    }

    @Test
    fun isErrorResultWithArbitraryExpiredTextIsNotFatal() {
        // Regression: is_error result text was scanned for loose "expired".
        val result = """{"type":"result","subtype":"error_during_execution","is_error":true,"result":"The sandbox session expired while reading files","session_id":"s1"}"""
        assertNull(ProviderRuntimeErrorDetector.detect(result))
    }

    @Test
    fun httpVersionLine429IsRateLimit() {
        assertEquals(
            "The provider is rate limiting requests.",
            ProviderRuntimeErrorDetector.detect("HTTP/1.1 429 Too Many Requests"),
        )
    }

    @Test
    fun longNumberNearHttpIsNotAStatus() {
        assertNull(ProviderRuntimeErrorDetector.detect("fetched http 14293 bytes of debug log"))
    }

    @Test
    fun oauthTokenExpiryIsAuth() {
        assertEquals(
            "The provider rejected the saved API key.",
            ProviderRuntimeErrorDetector.detect("OAuth token has expired. Please re-authenticate."),
        )
    }
}
