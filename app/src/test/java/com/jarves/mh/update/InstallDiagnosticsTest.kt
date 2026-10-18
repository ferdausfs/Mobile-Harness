package com.jarves.mh.update

import com.jarves.mh.update.InstallDiagnostics.DiagnosticsData
import com.jarves.mh.update.InstallDiagnostics.InstallResultRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the pure half of the install diagnostics: status mapping,
 * reason extraction, human sentences, record round-trip and report format.
 */
class InstallDiagnosticsTest {

    // --- status code names ---

    @Test
    fun `status names cover every PackageInstaller EXTRA_STATUS constant`() {
        assertEquals("PENDING_USER_ACTION", InstallDiagnostics.statusCodeName(-1))
        assertEquals("SUCCESS", InstallDiagnostics.statusCodeName(0))
        assertEquals("FAILURE", InstallDiagnostics.statusCodeName(1))
        assertEquals("FAILURE_BLOCKED", InstallDiagnostics.statusCodeName(2))
        assertEquals("FAILURE_ABORTED", InstallDiagnostics.statusCodeName(3))
        assertEquals("FAILURE_INVALID", InstallDiagnostics.statusCodeName(4))
        assertEquals("FAILURE_CONFLICT", InstallDiagnostics.statusCodeName(5))
        assertEquals("FAILURE_STORAGE", InstallDiagnostics.statusCodeName(6))
        assertEquals("FAILURE_INCOMPATIBLE", InstallDiagnostics.statusCodeName(7))
        assertEquals("FAILURE_TIMEOUT", InstallDiagnostics.statusCodeName(8))
    }

    @Test
    fun `unknown status codes stay visible instead of collapsing`() {
        assertEquals("UNKNOWN(42)", InstallDiagnostics.statusCodeName(42))
        assertEquals("UNKNOWN(-99)", InstallDiagnostics.statusCodeName(-99))
    }

    // --- reason extraction ---

    @Test
    fun `extracts the real reason from a typical UPDATE_INCOMPATIBLE message`() {
        val raw = "INSTALL_FAILED_UPDATE_INCOMPATIBLE: Package com.jarves.mh signatures " +
            "do not match previously installed version; ignoring!"
        assertEquals("INSTALL_FAILED_UPDATE_INCOMPATIBLE", InstallDiagnostics.extractFailureReason(raw))
    }

    @Test
    fun `extracts parse failures which indicate a corrupted download`() {
        assertEquals(
            "INSTALL_PARSE_FAILED_NO_CERTIFICATES",
            InstallDiagnostics.extractFailureReason("INSTALL_PARSE_FAILED_NO_CERTIFICATES: Failed collecting certificates"),
        )
        assertEquals(
            "INSTALL_PARSE_FAILED_NOT_APK",
            InstallDiagnostics.extractFailureReason("INSTALL_PARSE_FAILED_NOT_APK"),
        )
    }

    @Test
    fun `blank or reason-free messages yield null`() {
        assertNull(InstallDiagnostics.extractFailureReason(""))
        assertNull(InstallDiagnostics.extractFailureReason("  "))
        assertNull(InstallDiagnostics.extractFailureReason("Failed to install"))
    }

    // --- friendly failures ---

    @Test
    fun `update-incompatible points at signing key or leftover state`() {
        val text = InstallDiagnostics.friendlyInstallFailure(
            InstallDiagnostics.STATUS_FAILURE_INCOMPATIBLE,
            "INSTALL_FAILED_UPDATE_INCOMPATIBLE: signatures do not match",
        )
        assertTrue(text.contains("FAILURE_INCOMPATIBLE"))
        assertTrue(text.contains("INSTALL_FAILED_UPDATE_INCOMPATIBLE"))
        assertTrue(text.contains("signing key"))
    }

    @Test
    fun `no-certificates parse failure blames a truncated download`() {
        val text = InstallDiagnostics.friendlyInstallFailure(
            InstallDiagnostics.STATUS_FAILURE_INVALID,
            "INSTALL_PARSE_FAILED_NO_CERTIFICATES",
        )
        assertTrue(text.contains("truncated or corrupted download"))
    }

    @Test
    fun `aborted failure names the device as the likely aborter`() {
        val text = InstallDiagnostics.friendlyInstallFailure(
            InstallDiagnostics.STATUS_FAILURE_ABORTED,
            "INSTALL_FAILED_ABORTED: User isn't allowed to install",
        )
        assertTrue(text.contains("aborted"))
        assertTrue(text.contains("INSTALL_FAILED_ABORTED"))
    }

    @Test
    fun `unknown reason still reports the raw message`() {
        val text = InstallDiagnostics.friendlyInstallFailure(
            InstallDiagnostics.STATUS_FAILURE,
            "Something entirely new went wrong",
        )
        assertTrue(text.contains("Something entirely new went wrong"))
    }

    @Test
    fun `empty message says the installer provided nothing`() {
        val text = InstallDiagnostics.friendlyInstallFailure(InstallDiagnostics.STATUS_FAILURE, "")
        assertTrue(text.contains("no EXTRA_STATUS_MESSAGE"))
    }

    // --- record formatting / parsing round-trip ---

    @Test
    fun `record survives a format-parse round trip`() {
        val record = InstallResultRecord(
            atMillis = 1_760_000_000_000L,
            sessionId = 7,
            statusCode = InstallDiagnostics.STATUS_FAILURE_INCOMPATIBLE,
            statusMessage = "INSTALL_FAILED_UPDATE_INCOMPATIBLE: signatures do not match",
            apkName = "mobile-harness-online.apk",
            apkSizeBytes = 87_980_479L,
            otherPackageName = "",
        )
        val parsed = InstallDiagnostics.parseRecord(InstallDiagnostics.formatRecord(record))
        assertEquals(record, parsed)
    }

    @Test
    fun `parseRecord rejects non-record text`() {
        assertNull(InstallDiagnostics.parseRecord("not a record"))
        assertNull(InstallDiagnostics.parseRecord(""))
    }

    @Test
    fun `multi-line status messages are flattened for storage`() {
        val record = InstallResultRecord(
            atMillis = 1L, sessionId = 1, statusCode = 1,
            statusMessage = "line one\nline two", apkName = "a.apk",
            apkSizeBytes = 1L, otherPackageName = "",
        )
        val text = InstallDiagnostics.formatRecord(record)
        assertFalse(text.contains("line one\nline two"))
        assertEquals("line one line two", InstallDiagnostics.parseRecord(text)!!.statusMessage)
    }

    // --- summarize ---

    @Test
    fun `summary carries status reason and time`() {
        val summary = InstallDiagnostics.summarizeRecord(
            InstallResultRecord(
                atMillis = 1_760_000_000_000L, sessionId = 3,
                statusCode = InstallDiagnostics.STATUS_FAILURE_INVALID,
                statusMessage = "INSTALL_PARSE_FAILED_NO_CERTIFICATES: nope",
                apkName = "", apkSizeBytes = -1, otherPackageName = "",
            ),
        )
        assertNotNull(summary)
        assertTrue(summary!!.contains("FAILURE_INVALID"))
        assertTrue(summary.contains("INSTALL_PARSE_FAILED_NO_CERTIFICATES"))
    }

    @Test
    fun `summary of no record is null`() {
        assertNull(InstallDiagnostics.summarizeRecord(null))
    }

    // --- report format ---

    private fun fullData(lastRecord: InstallResultRecord?) = DiagnosticsData(
        installedVersionName = "1.0.24",
        installedVersionCode = 25L,
        installedCertSha256 = "d07ba804cfa95dd39083002628b65487d7ae99acab12cdfa61c5f42bca7dccfe",
        downloadedApkPath = "/data/user/0/com.jarves.mh/files/updates/mobile-harness-online.apk",
        downloadedApkSizeBytes = 87_980_479L,
        downloadedApkSha256 = null,
        downloadedCertSha256 = "d07ba804cfa95dd39083002628b65487d7ae99acab12cdfa61c5f42bca7dccfe",
        freeInternalBytes = 240_000_000_000L,
        sdkInt = 36,
        abis = listOf("arm64-v8a"),
        lastRecord = lastRecord,
    )

    @Test
    fun `report contains device facts and cert match`() {
        val report = InstallDiagnostics.formatReport(fullData(lastRecord = null))
        assertTrue(report.startsWith("Mobile Harness install diagnostics"))
        assertTrue(report.contains("Android SDK: 36"))
        assertTrue(report.contains("arm64-v8a"))
        assertTrue(report.contains("MATCH"))
        assertTrue(report.contains("v1.0.24 (versionCode 25)"))
        assertTrue(report.contains("Last in-app install attempt: none recorded"))
        assertFalse(report.contains("MISMATCH"))
    }

    @Test
    fun `report shows the exact status code and raw message on failure`() {
        val record = InstallResultRecord(
            atMillis = 1_760_000_000_000L,
            sessionId = 9,
            statusCode = InstallDiagnostics.STATUS_FAILURE_INCOMPATIBLE,
            statusMessage = "INSTALL_FAILED_UPDATE_INCOMPATIBLE: signatures do not match",
            apkName = "mobile-harness-online.apk",
            apkSizeBytes = 87_980_479L,
            otherPackageName = "",
        )
        val report = InstallDiagnostics.formatReport(fullData(lastRecord = record))
        assertTrue(report.contains("FAILURE_INCOMPATIBLE (code 7)"))
        assertTrue(report.contains("EXTRA_STATUS_MESSAGE: INSTALL_FAILED_UPDATE_INCOMPATIBLE"))
        assertTrue(report.contains("Likely meaning:"))
    }

    @Test
    fun `cert mismatch is flagged loudly in the report`() {
        val report = InstallDiagnostics.formatReport(
            fullData(lastRecord = null).copy(downloadedCertSha256 = "deadbeef".repeat(4)),
        )
        assertTrue(report.contains("MISMATCH"))
    }

    @Test
    fun `missing fingerprints degrade to unknown instead of crashing`() {
        val report = InstallDiagnostics.formatReport(
            fullData(lastRecord = null).copy(installedCertSha256 = null, downloadedCertSha256 = null),
        )
        assertTrue(report.contains("unknown"))
    }

    @Test
    fun `report without a pending download says so`() {
        val report = InstallDiagnostics.formatReport(
            fullData(lastRecord = null).copy(downloadedApkPath = null),
        )
        assertTrue(report.contains("none pending"))
    }

    // --- bytes ---

    @Test
    fun `byte formatting covers the ranges the report uses`() {
        assertEquals("83.9 MB", InstallDiagnostics.formatBytes(87_980_479L))
        assertEquals("1.5 KB", InstallDiagnostics.formatBytes(1_500L))
        assertEquals("512 B", InstallDiagnostics.formatBytes(512L))
        assertEquals("unknown", InstallDiagnostics.formatBytes(-1L))
    }
}
