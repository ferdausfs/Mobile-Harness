package com.jarves.mh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the startup crash-recovery decision (v1.0.31).
 *
 * The scheme: MainActivity stamps "the app opened" after its first successful
 * composition; a crash report stamped AFTER that marker makes the next launch
 * a suspected startup crash. One → dialog; two or more in a row → the
 * full-screen gate. These tests pin the decision table and the boundaries so
 * a crash loop can never silently stop being surfaced (or surface forever).
 */
class CrashRecoveryTest {

    private fun state(
        startupCrashes: Int = 0,
        crashAt: Long? = null,
        lastOpenAt: Long = 0L,
    ) = CrashRecovery.State(
        startupCrashes = startupCrashes,
        lastCrashAtMillis = crashAt,
        lastOpenAtMillis = lastOpenAt,
    )

    @Test
    fun `no crash report on disk opens normally`() {
        assertEquals(CrashRecovery.Action.NONE, CrashRecovery.decide(state(crashAt = null)))
    }

    @Test
    fun `crash older than last open opens normally`() {
        // The app opened at 5_000; the report belongs to a launch before that.
        assertEquals(
            CrashRecovery.Action.NONE,
            CrashRecovery.decide(state(crashAt = 4_999, lastOpenAt = 5_000)),
        )
    }

    @Test
    fun `crash inside the slack window opens normally`() {
        // Simultaneous crash+open writes are ambiguous — do not loop the gate.
        val openAt = 10_000L
        assertEquals(
            CrashRecovery.Action.NONE,
            CrashRecovery.decide(state(crashAt = openAt + CrashRecovery.NEWER_THAN_SLACK_MILLIS, lastOpenAt = openAt)),
        )
    }

    @Test
    fun `crash newer than last open shows the dialog`() {
        val openAt = 10_000L
        assertEquals(
            CrashRecovery.Action.DIALOG,
            CrashRecovery.decide(state(crashAt = openAt + CrashRecovery.NEWER_THAN_SLACK_MILLIS + 1, lastOpenAt = openAt)),
        )
    }

    @Test
    fun `never-opened install with a crash shows the dialog`() {
        assertEquals(
            CrashRecovery.Action.DIALOG,
            CrashRecovery.decide(state(crashAt = 1_000, lastOpenAt = 0L)),
        )
    }

    @Test
    fun `second suspected startup crash escalates to full screen`() {
        val openAt = 10_000L
        assertEquals(
            CrashRecovery.Action.FULL_SCREEN,
            CrashRecovery.decide(
                state(
                    startupCrashes = CrashRecovery.FULL_SCREEN_THRESHOLD,
                    crashAt = openAt + CrashRecovery.NEWER_THAN_SLACK_MILLIS + 1,
                    lastOpenAt = openAt,
                ),
            ),
        )
    }

    @Test
    fun `escalated counter with an old crash still opens normally`() {
        // The counter only matters while the crash is newer than the last open.
        assertEquals(
            CrashRecovery.Action.NONE,
            CrashRecovery.decide(state(startupCrashes = 5, crashAt = 1_000, lastOpenAt = 50_000)),
        )
    }

    @Test
    fun `counter grows one per suspected startup launch and resets on open`() {
        var counter = 0
        counter = CrashRecovery.countStartupCrash(counter)
        counter = CrashRecovery.countStartupCrash(counter)
        assertEquals(2, counter)
        counter = CrashRecovery.resetStartupCrashes()
        assertEquals(0, counter)
    }

    @Test
    fun `counter is bounded so a long loop cannot overflow the pref`() {
        var counter = 0
        repeat(500) { counter = CrashRecovery.countStartupCrash(counter) }
        assertEquals(99, counter)
    }

    @Test
    fun `crash report timestamp round-trips through the shared format`() {
        val at = 1_760_800_000_000L
        val report = CrashGuard.formatCrash(
            versionName = "1.0.31",
            versionCode = 32,
            atMillis = at,
            threadName = "main",
            stack = "java.lang.RuntimeException: boom",
        )
        assertEquals(at, CrashGuard.lastCrashAtMillis(report))
    }

    @Test
    fun `corrupt report has no parseable timestamp`() {
        assertNull(CrashGuard.lastCrashAtMillis(null))
        assertNull(CrashGuard.lastCrashAtMillis(""))
        assertNull(CrashGuard.lastCrashAtMillis("Mobile Harness crash report\nVersion: x\nNo At line"))
        assertNull(CrashGuard.lastCrashAtMillis("At: not a date"))
    }

    @Test
    fun `summary still extracts the exception line`() {
        val report = CrashGuard.formatCrash(
            versionName = "1.0.31",
            versionCode = 32,
            atMillis = 1_760_800_000_000L,
            threadName = "main",
            stack = "java.lang.IllegalStateException: boom\n  at whatever",
        )
        val summary = CrashGuard.summarize(report)
        assertTrue(summary.orEmpty().startsWith("java.lang.IllegalStateException"))
        assertTrue(summary.orEmpty().contains("·"))
    }
}
