package com.jarves.mh

import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Captures uncaught exceptions to a small file in app storage BEFORE the
 * system crash dialog appears, then delegates to the previous handler so
 * the usual Android crash flow ("Mobile Harness keeps stopping") is untouched.
 *
 * Why this exists: the 2026-10-18 updater crash report ("keeps stopping" while
 * trying to download v1.0.23) could not be diagnosed — no logcat access, and
 * neither the user's screenshot nor the Diagnostics carried a stack trace.
 * The user then uninstalled the app, which also destroyed any chance of
 * reading the crash later. From this build on, Settings → Last crash shows
 * the captured crash and its report can be copied and sent, so a crash is
 * never again a blind spot.
 */
object CrashGuard {

    private const val FILE_NAME = "last-crash.txt"

    /** Upper bound for a copied report; keeps a runaway stack from flooding the clipboard/UI. */
    internal const val MAX_REPORT_CHARS = 24_000

    fun install(context: Context) {
        val appContext = context.applicationContext
        val (versionName, versionCode) = runCatching { readVersion(appContext) }.getOrDefault("?" to 0)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                crashFile(appContext).parentFile?.mkdirs()
                crashFile(appContext).writeText(
                    formatCrash(
                        versionName = versionName,
                        versionCode = versionCode,
                        atMillis = System.currentTimeMillis(),
                        threadName = thread.name,
                        stack = stackTraceOf(throwable),
                    ),
                )
            }
            previous?.uncaughtException(thread, throwable)
        }
    }

    fun crashFile(context: Context): File =
        File(File(context.applicationContext.filesDir, "crash"), FILE_NAME)

    /** Full bounded report for copying out of the app; null when no crash was captured. */
    fun report(context: Context): String? {
        val file = crashFile(context)
        if (!file.isFile) return null
        val text = runCatching { file.readText() }.getOrNull() ?: return null
        return bound(text)
    }

    /** One-line summary for the Settings row: exception class + time. Null when none. */
    fun summary(context: Context): String? {
        val report = report(context) ?: return null
        return summarize(report)
    }

    fun clear(context: Context) {
        runCatching { crashFile(context).delete() }
    }

    // --- pure helpers (JVM-tested) ---

    internal fun readVersion(context: Context): Pair<String, Int> {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        val code = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            info.longVersionCode.toInt()
        } else {
            @Suppress("DEPRECATION")
            info.versionCode
        }
        return (info.versionName ?: "?") to code
    }

    /** Keeps head + tail of an over-long report so both the crash header and the deepest cause survive. */
    internal fun bound(text: String): String {
        if (text.length <= MAX_REPORT_CHARS) return text
        val head = text.take(MAX_REPORT_CHARS * 3 / 4)
        val tail = text.takeLast(MAX_REPORT_CHARS / 4)
        return head + "\n… [middle of the report trimmed] …\n" + tail
    }

    internal fun stackTraceOf(throwable: Throwable): String {
        val sw = StringWriter()
        throwable.printStackTrace(PrintWriter(sw))
        return sw.toString()
    }

    internal fun formatCrash(
        versionName: String,
        versionCode: Int,
        atMillis: Long,
        threadName: String,
        stack: String,
    ): String {
        val whenText = SimpleDateFormat("yyyy-MM-dd HH:mm:ss z", Locale.US).format(Date(atMillis))
        return buildString {
            appendLine("Mobile Harness crash report")
            appendLine("Version: $versionName (code $versionCode)")
            appendLine("At: $whenText")
            appendLine("Thread: $threadName")
            appendLine()
            append(stack)
        }
    }

    /**
     * "java.lang.IllegalStateException: Update download failed (HTTP 403)" plus the At line —
     * enough to identify a crash at a glance without pasting the whole stack.
     */
    internal fun summarize(report: String): String? {
        val lines = report.lines()
        val at = lines.firstOrNull { it.startsWith("At: ") } ?: return null
        val exceptionLine = lines
            .dropWhile { it.isNotBlank() }
            .firstOrNull { it.isNotBlank() }
            ?: return null
        val name = exceptionLine.trim().substringBefore(": ").trim()
        return "$name · ${at.removePrefix("At: ")}"
    }
}
