package com.jarves.mh.tokens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TokenTestClassifierTest {

    @Test
    fun `network failure is a clear retry message`() {
        val outcome = TokenTestClassifier.classify(TokenPresets.GITHUB, null, null, "timeout after 10s")
        assertFalse(outcome.valid)
        assertTrue(outcome.human.contains("Could not reach api.github.com"))
        assertTrue(outcome.human.contains("timeout"))
    }

    @Test
    fun `401 and 403 are rejected-token messages`() {
        val o401 = TokenTestClassifier.classify(TokenPresets.GITHUB, 401, """{"message":"Bad credentials"}""", null)
        val o403 = TokenTestClassifier.classify(TokenPresets.VERCEL, 403, null, null)
        assertFalse(o401.valid)
        assertFalse(o403.valid)
        assertTrue(o401.human.contains("HTTP 401"))
        assertTrue(o403.human.contains("Create a fresh one"))
    }

    @Test
    fun `github success names the account`() {
        val outcome = TokenTestClassifier.classify(TokenPresets.GITHUB, 200, """{"login":"octocat","id":1}""", null)
        assertTrue(outcome.valid)
        assertTrue(outcome.human.contains("octocat"))
    }

    @Test
    fun `cloudflare inactive token is reported invalid`() {
        val outcome = TokenTestClassifier.classify(
            TokenPresets.CLOUDFLARE, 200,
            """{"success":false,"errors":[{"code":1000,"message":"Invalid Token"}]}""",
            null,
        )
        assertFalse(outcome.valid)
        assertTrue(outcome.human.contains("not active"))
    }

    @Test
    fun `cloudflare active token passes`() {
        val outcome = TokenTestClassifier.classify(
            TokenPresets.CLOUDFLARE, 200,
            """{"success":true,"result":{"id":"x","status":"active"}}""",
            null,
        )
        assertTrue(outcome.valid)
    }

    @Test
    fun `vercel npm supabase firebase success shapes parse`() {
        assertTrue(TokenTestClassifier.classify(TokenPresets.VERCEL, 200, """{"user":{"username":"me"}}""", null).valid)
        val npm = TokenTestClassifier.classify(TokenPresets.NPM, 200, """{"name":"me"}""", null)
        assertTrue(npm.valid)
        assertTrue(npm.human.contains("me"))
        assertTrue(TokenTestClassifier.classify(TokenPresets.SUPABASE, 200, """[{"id":"p1"}]""", null).valid)
        assertTrue(TokenTestClassifier.classify(TokenPresets.FIREBASE, 200, """{}""", null).valid)
    }

    @Test
    fun `unexpected status redacts and bounds the body fragment`() {
        val body = "html error page with ghp_16C7e42F292c6912E7710c838347Ae178B4a embedded" + "x".repeat(500)
        val outcome = TokenTestClassifier.classify(TokenPresets.CUSTOM, 503, body, null)
        assertFalse(outcome.valid)
        assertFalse(outcome.human.contains("ghp_16C7"))
        assertTrue(outcome.human.length < 400)
    }

    @Test
    fun `custom preset has no test spec and presets carry env vars`() {
        assertEquals(null, TokenPresets.CUSTOM.test)
        assertEquals(listOf("GITHUB_TOKEN"), TokenPresets.GITHUB.envVars)
        assertEquals(listOf("CLOUDFLARE_API_TOKEN", "CLOUDFLARE_ACCOUNT_ID"), TokenPresets.CLOUDFLARE.envVars)
        assertTrue(TokenPresets.ALL.size >= 7)
        assertTrue(TokenPresets.isValidEnvVarName("GITHUB_TOKEN"))
        assertTrue(!TokenPresets.isValidEnvVarName("github-token"))
        assertTrue(!TokenPresets.isValidEnvVarName("1BAD"))
        // every preset's test URL is https and its management URL too
        TokenPresets.ALL.forEach { preset ->
            preset.test?.let { assertTrue(it.url.startsWith("https://")) }
            preset.managementUrl?.let { assertTrue(it.startsWith("https://")) }
        }
    }
}
