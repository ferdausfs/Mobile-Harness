package com.jarves.mh.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdateCheckPolicyTest {

    private val throttle = AppUpdateCheckPolicy.DEFAULT_THROTTLE_MILLIS

    @Test
    fun `forced check always runs`() {
        val now = 10_000L
        assertTrue(AppUpdateCheckPolicy.shouldCheck(lastCheckMillis = now, nowMillis = now, force = true))
        assertTrue(
            AppUpdateCheckPolicy.shouldCheck(
                lastCheckMillis = now,
                nowMillis = now,
                force = true,
                throttleMillis = Long.MAX_VALUE,
            ),
        )
    }

    @Test
    fun `first ever check runs even without force`() {
        assertTrue(AppUpdateCheckPolicy.shouldCheck(lastCheckMillis = 0L, nowMillis = 1_000L, force = false))
        assertTrue(AppUpdateCheckPolicy.shouldCheck(lastCheckMillis = -5L, nowMillis = 1_000L, force = false))
    }

    @Test
    fun `automatic check is throttled within the window`() {
        val last = 1_000_000L
        assertFalse(
            AppUpdateCheckPolicy.shouldCheck(
                lastCheckMillis = last,
                nowMillis = last + throttle - 1,
                force = false,
            ),
        )
    }

    @Test
    fun `automatic check runs once the window has elapsed`() {
        val last = 1_000_000L
        assertTrue(
            AppUpdateCheckPolicy.shouldCheck(
                lastCheckMillis = last,
                nowMillis = last + throttle,
                force = false,
            ),
        )
    }

    @Test
    fun `custom throttle is respected`() {
        val last = 1_000_000L
        assertFalse(
            AppUpdateCheckPolicy.shouldCheck(
                lastCheckMillis = last,
                nowMillis = last + 5_000L,
                force = false,
                throttleMillis = 10_000L,
            ),
        )
        assertTrue(
            AppUpdateCheckPolicy.shouldCheck(
                lastCheckMillis = last,
                nowMillis = last + 10_000L,
                force = false,
                throttleMillis = 10_000L,
            ),
        )
    }

    @Test
    fun `completed check may be recorded against the throttle`() {
        assertTrue(AppUpdateCheckPolicy.shouldRecordAttempt(checkCompleted = true))
    }

    @Test
    fun `failed check must not be recorded - the next startup retries instead of waiting a day`() {
        // Regression: v1.0.20/v1.0.21 stamped the throttle even when the check
        // threw, so one failed startup check silenced the updater for 24h while
        // a newer release was already published.
        assertFalse(AppUpdateCheckPolicy.shouldRecordAttempt(checkCompleted = false))
    }
}
