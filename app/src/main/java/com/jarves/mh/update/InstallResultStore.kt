package com.jarves.mh.update

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.StatFs
import com.jarves.mh.BuildConfig
import java.io.File
import java.security.MessageDigest

/**
 * Android-side persistence for the last PackageInstaller session outcome,
 * written by [com.jarves.mh.runtime.AndroidAppInstallReceiver] and read by the
 * Settings install diagnostics card. One small file, last result wins.
 */
object InstallResultStore {

    private const val DIR = "install"
    private const val FILE_NAME = "last-install-result.txt"

    fun file(context: Context): File =
        File(File(context.applicationContext.filesDir, DIR), FILE_NAME)

    fun save(context: Context, record: InstallDiagnostics.InstallResultRecord) {
        runCatching {
            file(context).parentFile?.mkdirs()
            file(context).writeText(InstallDiagnostics.formatRecord(record))
        }
    }

    fun load(context: Context): InstallDiagnostics.InstallResultRecord? {
        val text = runCatching { file(context).takeIf { it.isFile }?.readText() }.getOrNull() ?: return null
        return runCatching { InstallDiagnostics.parseRecord(text) }.getOrNull()
    }

    fun clear(context: Context) {
        runCatching { file(context).delete() }
    }
}

/**
 * Collects the on-device facts the install diagnostics report is built from.
 * Every read is best-effort: a diagnostics screen must never crash the app.
 */
object InstallDiagnosticsProvider {

    /** Path of the in-app updater's last verified download, when one exists. */
    fun downloadedApkFile(context: Context): File? =
        File(File(context.applicationContext.filesDir, "updates"), "mobile-harness-${BuildConfig.APP_VARIANT}.apk")
            .takeIf { it.isFile }

    fun collect(context: Context): InstallDiagnostics.DiagnosticsData {
        val appContext = context.applicationContext
        val pm = appContext.packageManager
        val installed = runCatching { readInstalled(pm, appContext.packageName) }.getOrNull()
        val downloaded = downloadedApkFile(appContext)
        val downloadedInfo = downloaded?.let { readArchive(pm, it.absolutePath) }
        val freeBytes = runCatching {
            StatFs(appContext.filesDir.path).availableBytes
        }.getOrDefault(-1L)
        return InstallDiagnostics.DiagnosticsData(
            installedVersionName = installed?.first ?: "not installed",
            installedVersionCode = installed?.second ?: -1L,
            installedCertSha256 = installed?.third,
            downloadedApkPath = downloaded?.absolutePath,
            downloadedApkSizeBytes = downloaded?.length() ?: -1L,
            downloadedApkSha256 = null,
            downloadedCertSha256 = downloadedInfo?.third,
            freeInternalBytes = freeBytes,
            sdkInt = Build.VERSION.SDK_INT,
            abis = Build.SUPPORTED_ABIS.toList(),
            lastRecord = InstallResultStore.load(appContext),
        )
    }

    /** (versionName, versionCode, signing cert SHA-256 hex) of the installed package. */
    private fun readInstalled(pm: PackageManager, packageName: String): Triple<String, Long, String?>? {
        val flags = PackageManager.GET_SIGNING_CERTIFICATES
        val info: PackageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(flags.toLong()))
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(packageName, flags)
        }
        val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }
        return Triple(info.versionName ?: "?", code, firstCertSha256(info.signingInfo?.apkContentsSigners))
    }

    /** (versionName, versionCode, signing cert SHA-256 hex) of an APK file on disk. */
    private fun readArchive(pm: PackageManager, path: String): Triple<String, Long, String?>? {
        val flags = PackageManager.GET_SIGNING_CERTIFICATES
        val info = pm.getPackageArchiveInfo(path, flags) ?: return null
        val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }
        return Triple(info.versionName ?: "?", code, firstCertSha256(info.signingInfo?.apkContentsSigners))
    }

    private fun firstCertSha256(signers: Array<out android.content.pm.Signature>?): String? {
        val first = signers?.firstOrNull() ?: return null
        val digest = MessageDigest.getInstance("SHA-256").digest(first.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }
}
