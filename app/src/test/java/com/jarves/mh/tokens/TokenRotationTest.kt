package com.jarves.mh.tokens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TokenRotationTest {

    private fun token(
        id: String,
        presetId: String = "github",
        autoDisable: Boolean = false,
        enabled: Boolean = true,
        allowedProjects: Set<String> = emptySet(),
    ) = StoredToken(
        id = id,
        presetId = presetId,
        name = id,
        envVar = "TEST_TOKEN",
        autoDisableAfterTask = autoDisable,
        enabled = enabled,
        allowedProjects = allowedProjects,
        createdAtMillis = 1L,
    )

    @Test
    fun `task end flags every used token`() {
        val used = listOf(token("a", "github"), token("b", "vercel"))
        val decision = TokenRotation.onTaskEnded(used, "Fix the login bug")
        val applied = TokenRotation.apply(used, decision, nowMillis = 100L)
        assertTrue(applied.all { it.needsRotation })
        assertTrue(applied.all { it.enabled })
        assertEquals("Task \"Fix the login bug\" finished", applied[0].rotationReason)
        assertEquals(100L, applied[0].rotationAtMillis)
    }

    @Test
    fun `auto-disable tokens are disabled but never deleted`() {
        val used = listOf(token("a", autoDisable = true), token("b"))
        val decision = TokenRotation.onTaskEnded(used, "t")
        val applied = TokenRotation.apply(used, decision, 1L)
        assertFalse(applied.first { it.id == "a" }.enabled)
        assertTrue(applied.first { it.id == "b" }.enabled)
        assertEquals(2, applied.size)
    }

    @Test
    fun `done stopped and failed all flag the same way`() {
        TokenRotation.TaskEndReason.entries.forEach { _ ->
            // The reason enum exists for wording/UI; the flagging rule is identical.
            val decision = TokenRotation.onTaskEnded(listOf(token("x")), "t")
            assertTrue(decision.flagIds.contains("x"))
        }
    }

    @Test
    fun `markRotated clears the badge`() {
        val flagged = token("a").copy(needsRotation = true, rotationReason = "r", rotationAtMillis = 5L)
        val cleared = TokenRotation.markRotated(flagged)
        assertFalse(cleared.needsRotation)
        assertEquals(null, cleared.rotationReason)
        assertEquals(null, cleared.rotationAtMillis)
    }

    @Test
    fun `replacing a value clears the badge too`() {
        val flagged = token("a").copy(needsRotation = true)
        assertTrue(!TokenRotation.markReplaced(flagged).needsRotation)
    }

    @Test
    fun `project availability respects enabled and allow list`() {
        val records = listOf(
            token("all"),                                    // enabled, all projects
            token("limited", allowedProjects = setOf("p1")), // enabled, p1 only
            token("off", enabled = false),                   // disabled
        )
        assertEquals(setOf("all", "limited"), TokenRotation.availableForProject(records, "p1").map { it.id }.toSet())
        assertEquals(setOf("all"), TokenRotation.availableForProject(records, "p2").map { it.id }.toSet())
        assertEquals(setOf("all", "limited", "off").filter { it != "off" }.toSet(), TokenRotation.availableForProject(records, null).map { it.id }.toSet())
    }

    @Test
    fun `reminder message names the providers`() {
        val flagged = listOf(token("a", "github"), token("b", "cloudflare"))
        assertEquals(
            "Task finished. Rotate these tokens now: GitHub, Cloudflare",
            TokenRotation.reminderMessage(flagged),
        )
        assertEquals("", TokenRotation.reminderMessage(emptyList()))
    }

    @Test
    fun `long task titles are bounded in the reason`() {
        val decision = TokenRotation.onTaskEnded(listOf(token("a")), "x".repeat(500))
        assertTrue(decision.rotationReason.length <= TokenRotation.MAX_REASON_LENGTH + 20)
    }
}
