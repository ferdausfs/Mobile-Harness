package com.jarves.mh.update

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Self-explaining installation diagnostics.
 *
 * "App not installed" on the device gives no reason. When Mobile Harness
 * installs an APK through PackageInstaller, the session callback carries the
 * exact numeric [android.content.pm.PackageInstaller.EXTRA_STATUS] plus often
 * an [android.content.pm.PackageInstaller.EXTRA_STATUS_MESSAGE] naming the
 * real PackageManager failure (INSTALL_FAILED_…). This object is the pure
 * half: code→name mapping, reason extraction, human sentences, record and
 * report formatting. The Android half ([android.content.Context] access,
 * persistence) lives in [InstallResultStore].
 */
object InstallDiagnostics {

    /** One recorded PackageInstaller session outcome. */
    data class InstallResultRecord(
        val atMillis: Long,
        val sessionId: Int,
        val statusCode: Int,
        val statusMessage: String,
        val apkName: String,
        val apkSizeBytes: Long,
        val otherPackageName: String,
    )

    /** Everything the Settings card needs for its report; produced Android-side, formatted here. */
    data class DiagnosticsData(
        val installedVersionName: String,
        val installedVersionCode: Long,
        val installedCertSha256: String?,
        val downloadedApkPath: String?,
        val downloadedApkSizeBytes: Long,
        val downloadedApkSha256: String?,
        val downloadedCertSha256: String?,
        val freeInternalBytes: Long,
        val sdkInt: Int,
        val abis: List<String>,
        val lastRecord: InstallResultRecord?,
    )

    // --- status codes (android.content.pm.PackageInstaller) ---

    const val STATUS_PENDING_USER_ACTION = -1
    const val STATUS_SUCCESS = 0
    const val STATUS_FAILURE = 1
    const val STATUS_FAILURE_BLOCKED = 2
    const val STATUS_FAILURE_ABORTED = 3
    const val STATUS_FAILURE_INVALID = 4
    const val STATUS_FAILURE_CONFLICT = 5
    const val STATUS_FAILURE_STORAGE = 6
    const val STATUS_FAILURE_INCOMPATIBLE = 7
    const val STATUS_FAILURE_TIMEOUT = 8

    /** Numeric EXTRA_STATUS → stable name; unknown codes fall back to "UNKNOWN(n)". */
    fun statusCodeName(code: Int): String = when (code) {
        STATUS_PENDING_USER_ACTION -> "PENDING_USER_ACTION"
        STATUS_SUCCESS -> "SUCCESS"
        STATUS_FAILURE -> "FAILURE"
        STATUS_FAILURE_BLOCKED -> "FAILURE_BLOCKED"
        STATUS_FAILURE_ABORTED -> "FAILURE_ABORTED"
        STATUS_FAILURE_INVALID -> "FAILURE_INVALID"
        STATUS_FAILURE_CONFLICT -> "FAILURE_CONFLICT"
        STATUS_FAILURE_STORAGE -> "FAILURE_STORAGE"
        STATUS_FAILURE_INCOMPATIBLE -> "FAILURE_INCOMPATIBLE"
        STATUS_FAILURE_TIMEOUT -> "FAILURE_TIMEOUT"
        else -> "UNKNOWN($code)"
    }

    /**
     * The PackageManager reason inside EXTRA_STATUS_MESSAGE, e.g.
     * "INSTALL_FAILED_UPDATE_INCOMPATIBLE: Package com.jarves.mh signatures do
     * not match previously installed version; ignoring!" →
     * "INSTALL_FAILED_UPDATE_INCOMPATIBLE". Null when the message carries none.
     */
    fun extractFailureReason(rawMessage: String): String? {
        if (rawMessage.isBlank()) return null
        val match = Regex("INSTALL_[A-Z0-9_]+").find(rawMessage) ?: return null
        return match.value
    }

    /**
     * One human sentence for a failure. Verified parts: the numeric status name
     * and the PackageManager reason string. The advice text is interpretation,
     * clearly phrased as likely cause.
     */
    fun friendlyInstallFailure(statusCode: Int, rawMessage: String): String {
        val status = statusCodeName(statusCode)
        val reason = extractFailureReason(rawMessage)
        val advice = when (reason) {
            "INSTALL_FAILED_UPDATE_INCOMPATIBLE",
            "INSTALL_FAILED_ALREADY_EXISTS",
            ->
                "the package on the phone conflicts with this APK (usually a different signing key or a same/older version). If no Mobile Harness is visible, its leftover entry may still exist: use the adb recipe in docs/adb-install-diagnostics.md (pm list packages -u) to check."
            "INSTALL_FAILED_VERSION_DOWNGRADE" ->
                "a newer versionCode is already installed than the APK being installed."
            "INSTALL_PARSE_FAILED_NO_CERTIFICATES",
            "INSTALL_PARSE_FAILED_INCONSISTENT_CERTIFICATES",
            "INSTALL_PARSE_FAILED_CERTIFICATE_ENCODING",
            ->
                "the APK file did not parse as signed — most often a truncated or corrupted download. Verify the exact byte size and SHA-256 before installing."
            "INSTALL_PARSE_FAILED_NOT_APK",
            "INSTALL_PARSE_FAILED_MANIFEST_MALFORMED",
            "INSTALL_PARSE_FAILED_BAD_MANIFEST",
            ->
                "the file is not a readable APK (truncated download or wrong file). Verify the exact byte size and SHA-256."
            "INSTALL_FAILED_INVALID_APK" ->
                "the system rejected the APK as invalid (corrupt file or bad structure). Verify the exact byte size and SHA-256."
            "INSTALL_FAILED_ABORTED" ->
                "the install was aborted before completion — often by the device (installer closed, security software, or a timeout). Try again on Wi-Fi with the screen on."
            "INSTALL_FAILED_BLOCKED" ->
                "installation was blocked by policy on the device (unknown-sources restriction or device security settings)."
            "INSTALL_FAILED_INSUFFICIENT_STORAGE" ->
                "the device ran out of storage for this app."
            "INSTALL_FAILED_NO_MATCHING_ABIS" ->
                "the APK does not contain a native library for this device's ABI (this APK is arm64-v8a only)."
            "INSTALL_FAILED_CONFLICTING_PROVIDER",
            "INSTALL_FAILED_CONFLICT" ->
                "another installed package claims the same name or provider."
            "INSTALL_FAILED_VERIFICATION_FAILURE" ->
                "the system's package verification (e.g. Play Protect) rejected the APK."
            "INSTALL_FAILED_TIMEOUT" ->
                "the system timed out installing — try again with the phone idle."
            else -> if (rawMessage.isNotBlank()) {
                "no known reason string was found in the message."
            } else {
                "no EXTRA_STATUS_MESSAGE was provided by the installer."
            }
        }
        val raw = rawMessage.trim()
        return buildString {
            append("Installer returned $status")
            if (reason != null) append(" ($reason)")
            append(": $advice")
            if (raw.isNotEmpty()) append(" Raw message: \"$raw\"")
        }
    }

    /** One-line summary for the App update card row. Null-safe for empty history. */
    fun summarizeRecord(record: InstallResultRecord?): String? {
        record ?: return null
        val status = statusCodeName(record.statusCode)
        val reason = extractFailureReason(record.statusMessage)
        val whenText = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(record.atMillis))
        return buildString {
            append("$status")
            if (reason != null) append(" · $reason")
            append(" · $whenText")
        }
    }

    /** Machine-readable persistence format (also human-readable). */
    fun formatRecord(record: InstallResultRecord): String = buildString {
        appendLine("atMillis=${record.atMillis}")
        appendLine("sessionId=${record.sessionId}")
        appendLine("statusCode=${record.statusCode}")
        appendLine("statusName=${statusCodeName(record.statusCode)}")
        appendLine("statusMessage=${record.statusMessage.replace("\n", " ")}")
        appendLine("apkName=${record.apkName}")
        appendLine("apkSizeBytes=${record.apkSizeBytes}")
        appendLine("otherPackageName=${record.otherPackageName}")
    }

    /** Inverse of [formatRecord]; null when the text is not a stored record. */
    fun parseRecord(text: String): InstallResultRecord? {
        val map = text.lineSequence()
            .mapNotNull { line ->
                val idx = line.indexOf('=')
                if (idx <= 0) null else line.take(idx) to line.substring(idx + 1)
            }
            .toMap()
        val at = map["atMillis"]?.toLongOrNull() ?: return null
        val code = map["statusCode"]?.toIntOrNull() ?: return null
        return InstallResultRecord(
            atMillis = at,
            sessionId = map["sessionId"]?.toIntOrNull() ?: -1,
            statusCode = code,
            statusMessage = map["statusMessage"].orEmpty(),
            apkName = map["apkName"].orEmpty(),
            apkSizeBytes = map["apkSizeBytes"]?.toLongOrNull() ?: -1L,
            otherPackageName = map["otherPackageName"].orEmpty(),
        )
    }

    /** Full copy-out report for Settings → Install diagnostics. */
    fun formatReport(data: DiagnosticsData): String = buildString {
        appendLine("Mobile Harness install diagnostics")
        appendLine("Android SDK: ${data.sdkInt}")
        appendLine("ABIs: ${data.abis.joinToString(", ")}")
        appendLine("Free internal storage: ${formatBytes(data.freeInternalBytes)}")
        appendLine()
        appendLine("Installed Mobile Harness: v${data.installedVersionName} (versionCode ${data.installedVersionCode})")
        appendLine("Installed cert SHA-256: ${data.installedCertSha256 ?: "unavailable"}")
        appendLine()
        if (data.downloadedApkPath != null) {
            appendLine("Downloaded update APK: ${data.downloadedApkPath}")
            appendLine("Downloaded APK size: ${formatBytes(data.downloadedApkSizeBytes)}")
            appendLine("Downloaded APK cert SHA-256: ${data.downloadedCertSha256 ?: "unavailable"}")
            appendLine("Cert comparison: ${certVerdict(data.installedCertSha256, data.downloadedCertSha256)}")
        } else {
            appendLine("Downloaded update APK: none pending")
        }
        appendLine()
        val record = data.lastRecord
        if (record == null) {
            appendLine("Last in-app install attempt: none recorded")
        } else {
            appendLine("Last in-app install attempt: ${statusCodeName(record.statusCode)} (code ${record.statusCode})")
            appendLine("At: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss z", Locale.US).format(Date(record.atMillis))}")
            appendLine("Session id: ${record.sessionId}")
            appendLine("APK: ${record.apkName} (${formatBytes(record.apkSizeBytes)})")
            if (record.otherPackageName.isNotBlank()) {
                appendLine("Other package involved: ${record.otherPackageName}")
            }
            appendLine("EXTRA_STATUS_MESSAGE: ${record.statusMessage.ifBlank { "(none)" }}")
            if (record.statusCode != STATUS_SUCCESS) {
                appendLine("Likely meaning: ${friendlyInstallFailure(record.statusCode, record.statusMessage)}")
            }
        }
        appendLine()
        appendLine(
            "If a browser-installed APK also fails with \"App not installed\", the above in-app record " +
                "will not exist — capture the system installer reason with adb instead: " +
                "docs/adb-install-diagnostics.md (Termux, wireless debugging, logcat INSTALL_FAILED).",
        )
    }

    /** Match / MISMATCH / unknown verdict for the two cert fingerprints. */
    fun certVerdict(installed: String?, downloaded: String?): String = when {
        installed == null || downloaded == null -> "unknown (a fingerprint was unavailable)"
        installed.equals(downloaded, ignoreCase = true) ->
            "MATCH — the downloaded APK is signed with the same key as the installed app"
        else ->
            "MISMATCH — the downloaded APK is signed with a DIFFERENT key; it cannot install over this app"
    }

    fun formatBytes(bytes: Long): String = when {
        bytes < 0 -> "unknown"
        bytes >= 1_073_741_824L -> "%.2f GB".format(bytes / 1_073_741_824.0)
        bytes >= 1_048_576L -> "%.1f MB".format(bytes / 1_048_576.0)
        bytes >= 1_024L -> "%.1f KB".format(bytes / 1_024.0)
        else -> "$bytes B"
    }
}
