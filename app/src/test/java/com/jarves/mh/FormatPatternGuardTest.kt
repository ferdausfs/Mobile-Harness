package com.jarves.mh

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Source-tree guard for the crash class that broke every in-app update in
 * v1.0.24 (UnknownFormatConversionException: Conversion = 'End of String',
 * SettingsScreenModern AppUpdateCard): a string literal used directly as a
 * String.format / "…".format pattern that either
 *
 *  1. carries a literal '%' that is not a valid conversion specifier
 *     (the trailing "…%" of a percent label), or
 *  2. contains Kotlin interpolation ("${…}" / "$identifier") — interpolated
 *     text can inject '%' into the pattern at runtime even when the literal
 *     itself looks valid.
 *
 * The rule from now on: user-visible strings are NEVER fed through
 * String.format as the pattern. Numbers are formatted against
 * compile-time-constant patterns (see AppUpdateProgressText) and joined by
 * plain interpolation, which cannot throw.
 *
 * Scanner design: comments and raw strings are blanked out first (newline
 * counts preserved so reported line numbers stay true), then every string
 * literal that directly precedes ".format(" is extracted and validated with
 * an approximation of the java.util.Formatter grammar.
 *
 * Known blind spots (accepted, kept documented): patterns held in a
 * variable, built by concatenation, or raw strings are not extracted by this
 * simple scanner — those cases need code review.
 */
class FormatPatternGuardTest {

    // java.util.Formatter specifier grammar (approximation): '%', optional
    // argument index, flags, width, precision, conversion character. '%%'
    // matches as the escaped-percent conversion, so only a lone/dangling '%'
    // fails this pattern.
    private val specifier = Regex("%(\\d+\\$)?[-#+ 0,(]*\\d*(\\.\\d+)?[a-zA-Z%]")

    // Kotlin interpolation that would end up inside the runtime pattern.
    // (?<!\\) keeps escaped '\$' out.
    private val interpolation = Regex("(?<!\\\\)\\$(\\{|[A-Za-z_])")

    private val literalThenFormat = Regex("\"((?:[^\"\\\\\\n]|\\\\.)*)\"\\s*\\.format")

    @Test
    fun `no format pattern is interpolated or carries an invalid percent specifier`() {
        val roots = sourceRoots()
        assertTrue("main source roots not found from " + System.getProperty("user.dir"), roots.isNotEmpty())
        val offenders = roots.flatMap { root ->
            root.walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .flatMap { file ->
                    scan(file).map {
                        root.name + "/" + file.relativeTo(root).path + ":" + it.line + " " + it.reason
                    }
                }
        }
        assertTrue(
            "Dangerous .format() pattern(s) found — build the string by plain interpolation " +
                "and format numbers against constant patterns (see AppUpdateProgressText, the v1.0.24 fix):\n" +
                offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
    }

    // --- scanner ---

    private data class Offender(val line: Int, val reason: String)

    private fun sourceRoots(): List<File> {
        var dir: File? = File(System.getProperty("user.dir")!!)
        repeat(6) {
            val current = dir ?: return emptyList()
            val roots = listOf("app/src/main/java", "app/src/main/kotlin")
                .map { File(current, it) }
                .filter { it.isDirectory }
            if (roots.isNotEmpty()) return roots
            dir = current.parentFile
        }
        return emptyList()
    }

    private fun scan(file: File): List<Offender> =
        scanText(runCatching { file.readText() }.getOrDefault(""))

    private fun scanText(text: String): List<Offender> {
        val cleaned = withoutCommentsAndRawStrings(text)
        return literalThenFormat.findAll(cleaned).mapNotNull { match ->
            val pattern = match.groupValues[1]
            val line = cleaned.substring(0, match.range.first).count { it == '\n' } + 1
            firstBadPercent(pattern)?.let { index ->
                return@mapNotNull Offender(
                    line,
                    "literal '%' at index $index is not a valid conversion specifier in format pattern: \"$pattern\"",
                )
            }
            if (interpolation.containsMatchIn(pattern)) {
                return@mapNotNull Offender(
                    line,
                    "interpolated text used as a format pattern (can inject '%' at runtime): \"$pattern\"",
                )
            }
            null
        }.toList()
    }

    // --- scanner self-tests: the guard must actually fire ---

    @Test
    fun `scanner detects the planted v1_0_24 crash pattern`() {
        val planted = """
            class Sample {
                fun render(progress: Float): String =
                    "Downloading " + "${'$'}{(progress * 100).toInt()}%".format(progress)
            }
        """.trimIndent()
        val offenders = scanText(planted)
        assertEquals(1, offenders.size)
        assertTrue(
            offenders[0].reason.contains("not a valid conversion specifier") ||
                offenders[0].reason.contains("interpolated"),
        )
    }

    @Test
    fun `scanner passes constant valid patterns and non-format percent labels`() {
        val safe = """
            val hex = "%02x".format(10)
            val clock = "%d:%02d:%02d".format(1, 2, 3)
            val bytes = "Downloading: %.1f / %.1f MB".format(1.0, 2.0)
            val simple = SimpleDateFormat("yyyy-MM-dd").format(Date())
            val label = "100%" // plain label, never passed through format
            val empty = "".format()
        """.trimIndent()
        assertEquals(0, scanText(safe).size)
    }

    @Test
    fun `scanner ignores the pattern inside comments and raw strings`() {
        // A raw string carrying a literal '%' and a trailing ".format(" call.
        val rawJson = "\"\"\"json: {\"p\": 100%}\"\"\""
        val source = buildString {
            appendLine("// \"bad %\".format(x) inside a line comment")
            appendLine("/* \"also \" + \"bad\".format(x) in a block comment */")
            append("val raw = ").append(rawJson).append(".format(x)")
        }
        assertEquals(0, scanText(source).size)
    }

    /** Index of the first '%' that java.util.Formatter would reject, or null when the pattern parses. */
    private fun firstBadPercent(pattern: String): Int? {
        var i = 0
        while (i < pattern.length) {
            if (pattern[i] != '%') {
                i++
                continue
            }
            val m = specifier.matchAt(pattern, i) ?: return i
            i = m.range.last + 1
        }
        return null
    }

    /**
     * Copy of the source with line comments, block comments and raw strings
     * replaced by spaces / newlines. Regular string literals are kept
     * verbatim (escapes included) because the pattern extraction reads them.
     */
    private fun withoutCommentsAndRawStrings(text: String): String {
        val out = StringBuilder(text.length)
        val n = text.length
        var i = 0
        while (i < n) {
            val c = text[i]
            val next = if (i + 1 < n) text[i + 1] else ' '
            when {
                c == '/' && next == '/' -> {
                    while (i < n && text[i] != '\n') i++
                }
                c == '/' && next == '*' -> {
                    out.append(' ')
                    i += 2
                    var depth = 1
                    while (i < n && depth > 0) {
                        if (text[i] == '/' && i + 1 < n && text[i + 1] == '*') {
                            depth++
                            i += 2
                        } else if (text[i] == '*' && i + 1 < n && text[i + 1] == '/') {
                            depth--
                            i += 2
                        } else {
                            if (text[i] == '\n') out.append('\n')
                            i++
                        }
                    }
                }
                c == '"' && next == '"' && i + 2 < n && text[i + 2] == '"' -> {
                    // Raw string: drop the content, keep newline counts for line numbers.
                    i += 3
                    while (i < n) {
                        if (i + 2 < n && text[i] == '"' && text[i + 1] == '"' && text[i + 2] == '"') {
                            i += 3
                            break
                        }
                        if (text[i] == '\n') out.append('\n')
                        i++
                    }
                }
                c == '"' && next == '"' -> {
                    // Empty literal "" (only two quotes).
                    out.append("\"\"")
                    i += 2
                }
                c == '"' -> {
                    out.append(c)
                    i++
                    var closed = false
                    while (i < n && !closed) {
                        val ch = text[i]
                        out.append(ch)
                        when {
                            ch == '\\' && i + 1 < n -> {
                                out.append(text[i + 1])
                                i += 2
                            }
                            ch == '\n' -> i++ // unterminated literal: bail, keep the newline
                            ch == '"' -> {
                                closed = true
                                i++
                            }
                            else -> i++
                        }
                    }
                }
                else -> {
                    out.append(c)
                    i++
                }
            }
        }
        return out.toString()
    }
}
