package com.jarves.mh.tokens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SecretRedactorTest {

    @Test
    fun `literal token values are replaced everywhere including mid-word`() {
        val secret = "ghp_16C7e42F292c6912E7710c838347Ae178B4a"
        val text = "prefix ${secret}middle and again\n$secret end"
        val out = SecretRedactor.redact(text, listOf(secret))
        assertFalse(out.contains(secret))
        assertEquals(2, out.split(SecretRedactor.MASK).size - 1)
    }

    @Test
    fun `multi-line and JSON embedding are covered`() {
        val secret = "vercel_secret_value_9f8e7d6c5b4a"
        val json = """{"name":"deploy","token":"$secret","nested":{"key":"$secret"}}"""
        val out = SecretRedactor.redact(json, listOf(secret))
        assertFalse(out.contains(secret))
        assertTrue(out.contains("\"token\":\"***\""))
    }

    @Test
    fun `url-embedded tokens are masked but the parameter name stays`() {
        val out = SecretRedactor.redactPatterns(
            "https://api.example.com/data?token=abcdef1234567890abcdef&page=2&access_token=ZYXWVUTSRQPONMLK",
        )
        assertTrue(out.contains("token="))
        assertFalse(out.contains("abcdef1234567890abcdef"))
        assertFalse(out.contains("ZYXWVUTSRQPONMLK"))
        assertTrue(out.contains("page=2"))
    }

    @Test
    fun `github and gitlab shapes are masked without vault knowledge`() {
        val out = SecretRedactor.redactPatterns(
            "found ghp_16C7e42F292c6912E7710c838347Ae178B4a and github_pat_11ABCDEFG0abcdefghijklmnopqrstuvwxyz12 and glpat-9xkwtcdhnptkexvhgbrw",
        )
        assertFalse(out.contains("ghp_16C7"))
        assertFalse(out.contains("github_pat_11ABC"))
        assertFalse(out.contains("glpat-9xkwtcdhnptkexvhgbrw"))
        assertEquals(3, out.split(SecretRedactor.MASK).size - 1)
    }

    @Test
    fun `keyword assignments in yaml json and shell are masked`() {
        val out = SecretRedactor.redactPatterns(
            """
            api_key: 9f8e7d6c5b4a3210fedcba
            "secret":"SuperSecretValue123"
            export PASSWORD=hunter2plusmore
            AUTH_TOKEN = ZZZZ1234abcd5678
            """.trimIndent(),
        )
        assertFalse(out.contains("9f8e7d6c5b4a3210fedcba"))
        assertFalse(out.contains("SuperSecretValue123"))
        assertFalse(out.contains("hunter2plusmore"))
        assertFalse(out.contains("ZZZZ1234abcd5678"))
        // the names survive
        assertTrue(out.contains("api_key:"))
        assertTrue(out.contains("\"secret\":\"***\""))
        assertTrue(out.contains("export PASSWORD="))
    }

    @Test
    fun `authorization headers and basic auth are masked`() {
        val out = SecretRedactor.redactPatterns(
            "Authorization: Bearer sk-abcdef1234567890abcdef\nAuthorization:token abcdef1234567890\nBasic dXNlcjpwYXNzd29yZDEyMzQ1Njc4",
        )
        assertFalse(out.contains("sk-abcdef1234567890abcdef"))
        assertFalse(out.contains("dXNlcjpwYXNzd29yZDEyMzQ1Njc4"))
    }

    @Test
    fun `variable names are never redacted`() {
        val text = "\$GITHUB_TOKEN is available. The GITHUB_TOKEN variable is set. token and key words alone."
        val out = SecretRedactor.redactPatterns(text)
        assertEquals(text, out)
    }

    @Test
    fun `hashes without keywords survive`() {
        val sha = "a77c30a86f11ba6792a3a6d555a1373ee156b96f4130f88815ddb0c507d6e3ec"
        val text = "sha256: $sha"
        assertEquals(text, SecretRedactor.redactPatterns(text))
    }

    @Test
    fun `short values below the literal threshold are ignored`() {
        val text = "pin 123456 stays"
        assertEquals(text, SecretRedactor.redact(text, listOf("123456")))
    }

    @Test
    fun `redactAll applies literals then patterns`() {
        val secret = "ghp_16C7e42F292c6912E7710c838347Ae178B4a"
        val out = SecretRedactor.redact("token=$secret", listOf(secret))
        assertFalse(out.contains("ghp_16C7"))
        assertTrue(out.contains("token="))
    }

    @Test
    fun `crash-report style multi line stack with embedded secrets is cleaned`() {
        val secret = "supabase_pat_9f8e7d6c5b4a3210"
        val report = """
            java.lang.RuntimeException: fetch failed with token=$secret
                at com.example.fetch(Source.kt:10)
            Authorization: Bearer 9f8e7d6c5b4a3210fedc
        """.trimIndent()
        val out = SecretRedactor.redact(report, listOf(secret))
        assertFalse(out.contains(secret))
        assertFalse(out.contains("9f8e7d6c5b4a3210fedc"))
        assertTrue(out.contains("at com.example.fetch(Source.kt:10)"))
    }
}
