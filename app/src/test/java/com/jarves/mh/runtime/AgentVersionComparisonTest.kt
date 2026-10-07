package com.jarves.mh.runtime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for the agent update checks: the previous comparison
 * folded pre-release digits into the numeric core, so a stable release was
 * never offered to users on its own rc and suffix words containing "rc"
 * (e.g. "-march") misclassified.
 */
class AgentVersionComparisonTest {
    @Test
    fun stableReleaseIsOfferedOverItsOwnRc() {
        assertTrue(isAgentVersionNewer("0.1.2", "0.1.2-rc.1"))
        assertTrue(isAgentVersionNewer("1.2.3", "1.2.3-rc.4"))
    }

    @Test
    fun stableReleaseIsNotDowngradedToItsOwnPreRelease() {
        assertFalse(isAgentVersionNewer("1.2.3-rc.1", "1.2.3"))
        assertFalse(isAgentVersionNewer("2.0.0-alpha", "2.0.0"))
    }

    @Test
    fun newerCoreBeatsOlderStableRegardlessOfSuffix() {
        // Semver: 2.2.0-rc.1 has higher precedence than 2.1.9.
        assertTrue(isAgentVersionNewer("2.2.0-rc.1", "2.1.9"))
        assertTrue(isAgentVersionNewer("2.2.0", "2.1.9"))
    }

    @Test
    fun sameCoreWithSamePreReleaseIsNotNewer() {
        assertFalse(isAgentVersionNewer("1.2.3-rc.1", "1.2.3-rc.1"))
        assertFalse(isAgentVersionNewer("1.2.3", "1.2.3"))
    }

    @Test
    fun higherPreReleaseCounterIsOffered() {
        assertTrue(isAgentVersionNewer("1.2.3-rc.2", "1.2.3-rc.1"))
        assertFalse(isAgentVersionNewer("1.2.3-rc.1", "1.2.3-rc.2"))
    }

    @Test
    fun suffixWordsThatContainRcDoNotMisclassify() {
        // The old implementation used a substring contains("rc") check.
        assertTrue(isAgentVersionNewer("1.2.4", "1.2.3-march"))
        assertFalse(isAgentVersionNewer("1.2.3-march", "1.2.4"))
    }

    @Test
    fun plainNumericBumps() {
        assertTrue(isAgentVersionNewer("0.2.0", "0.1.9"))
        assertTrue(isAgentVersionNewer("10.0.0", "9.9.9"))
        assertFalse(isAgentVersionNewer("0.1.9", "0.2.0"))
        assertFalse(isAgentVersionNewer("1.0.0", "1.0.1"))
    }
}
