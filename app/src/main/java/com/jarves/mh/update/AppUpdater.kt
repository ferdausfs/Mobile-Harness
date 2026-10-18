package com.jarves.mh.update

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.jarves.mh.BuildConfig
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import org.json.JSONObject

data class AppUpdateInfo(
    val versionCode: Long,
    val versionName: String,
    val apkUrl: String,
    val sha256: String,
    val sizeBytes: Long,
    val notes: String,
)

class AppUpdater(
    private val context: Context,
    /**
     * Optional manifest URL override. When non-empty, used in place of
     * [BuildConfig.APP_UPDATE_MANIFEST_URL]. Debug builds populate this
     * from Settings → Update channel so the update flow can be exercised
     * end-to-end against a Cloudflare Tunnel or ngrok HTTPS URL without
     * publishing a release to GitHub.
     */
    private val manifestUrlOverride: String = "",
) {
    fun check(): AppUpdateInfo? {
        val manifestUrl = manifestUrlOverride.ifBlank { BuildConfig.APP_UPDATE_MANIFEST_URL }
        if (!manifestUrl.startsWith("https://")) return null
        val connection = URL(manifestUrl).openConnection() as HttpURLConnection
        return try {
            connection.connectTimeout = 8_000
            connection.readTimeout = 10_000
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("Accept", "application/json")
            val code = connection.responseCode
            if (code !in 200..299) return null
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            val root = JSONObject(body)
            val versionCode = root.optLong("versionCode")
            if (versionCode <= BuildConfig.VERSION_CODE) return null
            // Fall back to the sibling "online" artifact when the running
            // variant has none: both flavors share the applicationId, and
            // verifyApk rejects anything that does not actually match.
            val artifact = root.optJSONObject("artifacts")?.let { artifacts ->
                artifacts.optJSONObject(BuildConfig.APP_VARIANT)
                    ?: artifacts.optJSONObject("online")
            }
                ?: root.optJSONObject(BuildConfig.APP_VARIANT)
                ?: root
            val url = artifact.optString("url").ifBlank { artifact.optString("apkUrl") }
            if (!url.startsWith("https://")) return null
            AppUpdateInfo(
                versionCode = versionCode,
                versionName = root.optString("versionName", versionCode.toString()),
                apkUrl = url,
                sha256 = artifact.optString("sha256").lowercase(),
                sizeBytes = artifact.optLong("sizeBytes", -1L),
                notes = root.optString("notes"),
            )
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Resumable download: a connection that dies mid-download continues from
     * the bytes already on disk instead of starting an ~88 MB transfer over.
     * Interrupted transfers were the user's recurring failure (network drops
     * long downloads), and a partial file is useless for installation.
     */
    fun download(info: AppUpdateInfo, progress: (Long, Long) -> Unit): File {
        val directory = File(context.filesDir, "updates").also { it.mkdirs() }
        // Version-scoped partial: resuming bytes meant for another version
        // would produce a corrupt APK; stale parts of other versions are removed.
        val partial = File(directory, partFileName(BuildConfig.APP_VARIANT, info.versionCode))
        val target = File(directory, "mobile-harness-${BuildConfig.APP_VARIANT}.apk")
        directory.listFiles()?.forEach { stale ->
            if (stale.isFile && stale.name.endsWith(".apk.part") && stale.name != partial.name) stale.delete()
        }

        var attempt = 0
        while (true) {
            attempt++
            try {
                downloadOnce(info, partial, progress)
                break
            } catch (error: IOException) {
                // Transient failure (timeout, reset connection, early close):
                // the next attempt resumes from whatever reached the disk.
                if (attempt >= DownloadResumePolicy.MAX_ATTEMPTS) {
                    throw IOException(
                        "Update download failed after ${DownloadResumePolicy.MAX_ATTEMPTS} attempts: ${error.message}",
                        error,
                    )
                }
                Thread.sleep(DownloadResumePolicy.backoffMillis(attempt))
            }
        }
        return try {
            if (info.sha256.isNotBlank()) {
                val actual = sha256(partial)
                check(actual.equals(info.sha256, ignoreCase = true)) { "Downloaded APK failed its SHA-256 verification" }
            }
            verifyApk(partial, info.versionCode)
            if (target.exists()) target.delete()
            check(partial.renameTo(target)) { "Could not prepare the downloaded update" }
            target
        } catch (error: Throwable) {
            // Do not strand a ~90 MB orphan when verification fails.
            partial.delete()
            throw error
        }
    }

    private fun downloadOnce(info: AppUpdateInfo, partial: File, progress: (Long, Long) -> Unit) {
        val resumeFrom = if (partial.isFile && partial.length() > 0) partial.length() else 0L
        val connection = URL(info.apkUrl).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.instanceFollowRedirects = true
            if (resumeFrom > 0) connection.setRequestProperty("Range", "bytes=$resumeFrom-")
            val code = connection.responseCode
            val decision = DownloadResumePolicy.decide(code, resumeFrom, connection.contentLengthLong, info.sizeBytes)
            when (decision.action) {
                DownloadResumePolicy.Action.RESUME -> Unit
                DownloadResumePolicy.Action.RESTART -> {
                    partial.delete()
                    partial.createNewFile()
                }
                DownloadResumePolicy.Action.RETRY_FRESH -> {
                    partial.delete()
                    throw IOException("Server rejected the resume (HTTP $code); the next attempt starts from scratch")
                }
                DownloadResumePolicy.Action.FAIL -> error(decision.failure ?: "Update download failed")
            }
            var downloaded = decision.offset
            connection.inputStream.use { input ->
                FileOutputStream(partial, decision.action == DownloadResumePolicy.Action.RESUME).use { output ->
                    val buffer = ByteArray(128 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        downloaded += count
                        progress(downloaded, decision.total)
                    }
                }
            }
            // A stream that ends before the advertised size is a silent
            // truncation — retry (the next attempt resumes the missing tail).
            if (decision.total > 0 && downloaded < decision.total) {
                throw IOException("Connection closed early at $downloaded of ${decision.total} bytes")
            }
        } finally {
            connection.disconnect()
        }
    }

    @Suppress("DEPRECATION")
    private fun verifyApk(apk: File, expectedVersionCode: Long) {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val archive = context.packageManager.getPackageArchiveInfo(apk.absolutePath, flags)
            ?: error("Downloaded file is not a valid APK")
        check(archive.packageName == context.packageName) { "Update package name does not match Mobile Harness" }
        val archiveVersion = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) archive.longVersionCode else archive.versionCode.toLong()
        check(archiveVersion == expectedVersionCode && archiveVersion > BuildConfig.VERSION_CODE) { "Update version does not match its manifest" }
        val installed = context.packageManager.getPackageInfo(context.packageName, flags)
        val archiveSignatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) archive.signingInfo?.apkContentsSigners else archive.signatures
        val installedSignatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) installed.signingInfo?.apkContentsSigners else installed.signatures
        check(!archiveSignatures.isNullOrEmpty() && !installedSignatures.isNullOrEmpty() &&
            archiveSignatures.map { sha256(it.toByteArray()) }.toSet() == installedSignatures.map { sha256(it.toByteArray()) }.toSet()
        ) {
            // Releases before v1.0.8 were signed with several different keys, so
            // users of those builds can never update in place. Say exactly what
            // to do instead of leaving a dead-end error.
            "This update uses a new signing key and cannot install over the version on this phone. Copy your API key somewhere safe, uninstall Mobile Harness once, then install the new APK from GitHub Releases. Your chats, workspaces and installed agents are removed by the uninstall."
        }
    }

    private fun sha256(file: File): String = file.inputStream().use { input ->
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(128 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }
}

/** Partial downloads are scoped per version code so bytes of two versions can never mix. */
internal fun partFileName(variant: String, versionCode: Long): String =
    "mobile-harness-$variant-$versionCode.apk.part"
