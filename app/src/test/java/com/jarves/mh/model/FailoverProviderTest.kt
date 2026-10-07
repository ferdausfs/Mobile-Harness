package com.jarves.mh.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FailoverProviderTest {
    private val hasSecretKinds = setOf(
        ProviderKind.DEEPSEEK.name,
        ProviderKind.OLLAMA_CLOUD.name,
        ProviderKind.NVIDIA_NIM.name,
    )
    private val hasSecret: (ProviderKind) -> Boolean = { it.name in hasSecretKinds }

    @Test
    fun picksFirstEnabledEntryWithStoredSecret() {
        val chain = listOf(
            FailoverProvider(kind = ProviderKind.OLLAMA_CLOUD, model = "gpt-oss:120b"),
            FailoverProvider(kind = ProviderKind.DEEPSEEK, model = "deepseek-v4-flash"),
        )

        val candidate = nextFailoverCandidate(
            chain = chain,
            currentKind = ProviderKind.LLM_ROUTER,
            failedProviderNames = emptySet(),
            hasSecret = hasSecret,
        )

        assertEquals(ProviderKind.OLLAMA_CLOUD, candidate?.kind)
        assertEquals("gpt-oss:120b", candidate?.model)
    }

    @Test
    fun skipsEntriesWithoutStoredSecretAndDisabledOnes() {
        val chain = listOf(
            FailoverProvider(kind = ProviderKind.KIMI),
            FailoverProvider(kind = ProviderKind.OLLAMA_CLOUD, enabled = false),
            FailoverProvider(kind = ProviderKind.DEEPSEEK),
        )

        val candidate = nextFailoverCandidate(
            chain = chain,
            currentKind = ProviderKind.LLM_ROUTER,
            failedProviderNames = emptySet(),
            hasSecret = hasSecret,
        )

        assertEquals(ProviderKind.DEEPSEEK, candidate?.kind)
    }

    @Test
    fun skipsAlreadyFailedProvidersAndTheActiveOne() {
        val chain = listOf(
            FailoverProvider(kind = ProviderKind.DEEPSEEK),
            FailoverProvider(kind = ProviderKind.OLLAMA_CLOUD),
            FailoverProvider(kind = ProviderKind.NVIDIA_NIM),
        )

        val candidate = nextFailoverCandidate(
            chain = chain,
            currentKind = ProviderKind.DEEPSEEK,
            failedProviderNames = setOf(ProviderKind.OLLAMA_CLOUD.name),
            hasSecret = hasSecret,
        )

        assertEquals(ProviderKind.NVIDIA_NIM, candidate?.kind)
    }

    @Test
    fun returnsNullWhenNothingUsableRemains() {
        val chain = listOf(FailoverProvider(kind = ProviderKind.DEEPSEEK))

        assertNull(
            nextFailoverCandidate(
                chain = chain,
                currentKind = ProviderKind.DEEPSEEK,
                failedProviderNames = emptySet(),
                hasSecret = hasSecret,
            ),
        )
        assertNull(
            nextFailoverCandidate(
                chain = chain,
                currentKind = ProviderKind.LLM_ROUTER,
                failedProviderNames = setOf(ProviderKind.DEEPSEEK.name),
                hasSecret = hasSecret,
            ),
        )
    }

    @Test
    fun fixedKindsResolveTheirConstantBaseUrl() {
        val entry = FailoverProvider(kind = ProviderKind.OLLAMA_CLOUD, baseUrl = "https://drift.example")
        assertTrue(entry.resolvedBaseUrl.startsWith("https://ollama.com"))
        assertFalse(entry.resolvedBaseUrl.contains("drift"))
    }

    @Test
    fun roundTripsThroughProviderProfile() {
        val entry = FailoverProvider(kind = ProviderKind.CUSTOM, baseUrl = "https://api.example.com", model = "m1")
        val profile = entry.toProfile()

        assertEquals(entry.kind, profile.kind)
        assertEquals(entry.baseUrl, profile.baseUrl)
        assertEquals(entry.model, profile.model)
        assertEquals(ProviderKind.CUSTOM, FailoverProvider.fromProfile(profile).kind)
    }
}
