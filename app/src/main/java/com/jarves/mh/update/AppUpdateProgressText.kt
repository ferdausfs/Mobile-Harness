package com.jarves.mh.update

import java.util.Locale

/**
 * Builds the "Downloading X / Y MB · N%" line for the update UI.
 *
 * Why this exists: v1.0.24 crashed during every in-app update download with
 * UnknownFormatConversionException ("Conversion = 'End of String'"). The old
 * code built the format pattern itself by interpolation —
 * `"Downloading %.1f / %.1f MB · ${(progress * 100).toInt()}%".format(...)` —
 * so the trailing literal '%' was parsed as the start of a conversion
 * specifier that never arrived, and the app died on the main thread the
 * moment the download size became known.
 *
 * The rule after this fix: user-visible strings are never used as a
 * String.format pattern. Numbers are formatted against compile-time-constant
 * patterns ([megabytes]) and the variable parts are joined by plain Kotlin
 * interpolation, which cannot throw. [FormatPatternGuardTest] scans the
 * source tree so the same bug class cannot come back silently.
 */
object AppUpdateProgressText {

    private const val MB = 1_048_576.0

    /**
     * @param downloadedBytes bytes already on disk
     * @param totalBytes advertised size; 0 or negative means unknown
     */
    fun build(downloadedBytes: Long, totalBytes: Long): String {
        val downloaded = megabytes(downloadedBytes)
        return if (totalBytes > 0) {
            "Downloading $downloaded / ${megabytes(totalBytes)} MB · ${percentOf(downloadedBytes, totalBytes)}%"
        } else {
            "Downloading $downloaded MB"
        }
    }

    /** 0..100, clamped so a server that misreports the size can never show "137%". */
    internal fun percentOf(downloadedBytes: Long, totalBytes: Long): Int {
        if (totalBytes <= 0) return 0
        return ((downloadedBytes.toDouble() / totalBytes) * 100.0).toInt().coerceIn(0, 100)
    }

    /** Locale.US keeps ASCII digits and '.' separators on every device locale. */
    internal fun megabytes(bytes: Long): String = String.format(Locale.US, "%.1f", bytes / MB)
}
