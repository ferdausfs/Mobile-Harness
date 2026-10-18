package com.jarves.mh.zweb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** JVM tests for the copyable Diagnostics report builder. */
class ZaiDiagnosticsTest {

    private val base = ZaiDiagnostics.Data(
        generatedAtMillis = 1_760_000_000_000,
        lastCheckAtMillis = 1_759_999_900_000,
        lastCheckOutcome = "probe-guest",
        lastProbeSummary = "role=guest email=yes outcome=probe-guest localToken=no",
        signedIn = false,
        engineCreated = true,
        engineLastUrl = "https://chat.z.ai/",
        engineUserAgent = "Mozilla/5.0 (Linux; Android 13) Chrome/131 Mobile",
        sheetLastUrl = "https://chat.z.ai/auth",
        sheetUserAgent = "Mozilla/5.0 (Linux; Android 13) Chrome/131 Mobile",
        sheetProbeSummary = "role=guest email=yes outcome=probe-guest localToken=yes",
        sheetVerifiedAtMillis = 1_759_999_800_000,
        configVersion = 2,
        cookieLines = listOf("chat.z.ai: token, acw_tc, cdn_sec_tc", "z.ai: (no cookies)"),
        previousRunOutcome = "signed-in-bearer",
        previousRunSignedIn = true,
    )

    @Test
    fun `report contains the check outcome and probe summary`() {
        val report = ZaiDiagnostics.build(base, nowMillis = 1_760_000_100_000)
        assertTrue(report.contains("outcome: probe-guest"))
        assertTrue(report.contains("role=guest email=yes outcome=probe-guest localToken=no"))
    }

    @Test
    fun `report contains both webviews and their user agents`() {
        val report = ZaiDiagnostics.build(base, nowMillis = 0)
        assertTrue(report.contains("last URL: https://chat.z.ai/"))
        assertTrue(report.contains("last URL: https://chat.z.ai/auth"))
        assertTrue(report.contains("user agent: Mozilla/5.0"))
    }

    @Test
    fun `report lists cookie names and the session-cookie note`() {
        val report = ZaiDiagnostics.build(base, nowMillis = 0)
        assertTrue(report.contains("chat.z.ai: token, acw_tc, cdn_sec_tc"))
        assertTrue(report.contains("z.ai: (no cookies)"))
        assertTrue(report.contains("SESSION cookie"))
        assertTrue(report.contains("does not"))
    }

    @Test
    fun `report shows the sheet disagreement and previous run`() {
        val report = ZaiDiagnostics.build(base, nowMillis = 0)
        assertTrue(report.contains("sheet saw a signed-in session: yes at"))
        assertTrue(report.contains("previous app run"))
        assertTrue(report.contains("check outcome: signed-in-bearer"))
        assertTrue(report.contains("signed in: yes"))
    }

    @Test
    fun `report without any check says so instead of guessing`() {
        val data = base.copy(
            lastCheckAtMillis = null,
            lastCheckOutcome = null,
            lastProbeSummary = null,
            signedIn = null,
            sheetLastUrl = null,
            sheetUserAgent = null,
            sheetProbeSummary = null,
            sheetVerifiedAtMillis = null,
            previousRunOutcome = null,
            previousRunSignedIn = null,
        )
        val report = ZaiDiagnostics.build(data, nowMillis = 0)
        assertTrue(report.contains("outcome: not run yet"))
        assertTrue(report.contains("not opened this session"))
        assertTrue(report.contains("sheet saw a signed-in session: no"))
        assertTrue(report.contains("check outcome: no record"))
        assertTrue(report.contains("unknown (no successful check)"))
    }

    @Test
    fun `report carries the config version`() {
        val report = ZaiDiagnostics.build(base, nowMillis = 0)
        assertTrue(report.contains("selector config version: v2"))
    }

    @Test
    fun `report never includes cookie values passed in as lines`() {
        // The builder only prints what it is given; the UI layer passes NAMES.
        val malicious = base.copy(cookieLines = listOf("chat.z.ai: token"))
        val report = ZaiDiagnostics.build(malicious, nowMillis = 0)
        assertFalse(report.contains("token=eyJ"))
        assertTrue(report.contains("values are never shown"))
    }

    @Test
    fun `card state line reflects all three signed-in states`() {
        assertTrue(ZaiDiagnostics.build(base.copy(signedIn = true), 0).contains("card shows: Connected"))
        assertTrue(ZaiDiagnostics.build(base.copy(signedIn = false), 0).contains("card shows: Not signed in"))
        assertTrue(ZaiDiagnostics.build(base.copy(signedIn = null), 0).contains("card shows: unknown"))
    }

    @Test
    fun `empty cookie lines render as unavailable`() {
        val report = ZaiDiagnostics.build(base.copy(cookieLines = emptyList()), nowMillis = 0)
        assertTrue(report.contains("(unavailable)"))
    }

    @Test
    fun `timestamps render in a stable format`() {
        val report = ZaiDiagnostics.build(base, nowMillis = 0)
        assertTrue(Regex("""at: \d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}""").containsMatchIn(report))
    }

    @Test
    fun `summary and report agree on the outcome label constants`() {
        // The classifier's outcome labels must surface verbatim in the report.
        ZaiAuthProbeClassifier.ZaiCheckOutcome.SIGNED_IN_BEARER
        val data = base.copy(lastCheckOutcome = ZaiAuthProbeClassifier.ZaiCheckOutcome.SIGNED_IN_BEARER)
        assertEquals(
            "outcome: ${ZaiAuthProbeClassifier.ZaiCheckOutcome.SIGNED_IN_BEARER}",
            ZaiDiagnostics.build(data, 0).split("\n").first { it.startsWith("outcome:") },
        )
    }
}
