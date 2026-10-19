package com.jarves.mh.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Edge-value coverage for the update download progress line.
 *
 * The v1.0.24 crash: the progress text was built as a String.format pattern
 * by interpolation with a trailing literal '%', so the line threw
 * UnknownFormatConversionException on the main thread the moment the total
 * size became known — every in-app update from Settings > App update died.
 * These tests pin the safe behavior for the values the UI can feed in.
 */
class AppUpdateProgressTextTest {

    @Test
    fun `typical download shows megabytes and percent`() {
        // 50.0 MB into an 88,222,047-byte APK (84.1 MB)
        assertEquals(
            "Downloading 50.0 / 84.1 MB · 59%",
            AppUpdateProgressText.build(52_428_800, 88_222_047),
        )
    }

    @Test
    fun `zero bytes with a known total starts at zero percent`() {
        assertEquals(
            "Downloading 0.0 / 84.1 MB · 0%",
            AppUpdateProgressText.build(0, 88_222_047),
        )
    }

    @Test
    fun `unknown total falls back to downloaded-only text`() {
        // The ViewModel uses -1L as the "total unknown" sentinel.
        assertEquals("Downloading 0.0 MB", AppUpdateProgressText.build(0, -1))
        assertEquals("Downloading 3.5 MB", AppUpdateProgressText.build(3_670_016, -1))
        // A zero total is also treated as unknown.
        assertEquals("Downloading 0.0 MB", AppUpdateProgressText.build(0, 0))
    }

    @Test
    fun `complete download shows 100 percent`() {
        assertEquals(
            "Downloading 84.1 / 84.1 MB · 100%",
            AppUpdateProgressText.build(88_222_047, 88_222_047),
        )
    }

    @Test
    fun `a downloaded count past the total never shows more than 100 percent`() {
        assertEquals(
            "Downloading 100.0 / 84.1 MB · 100%",
            AppUpdateProgressText.build(104_857_600, 88_222_047),
        )
    }

    @Test
    fun `percent helper covers the edge values`() {
        assertEquals(0, AppUpdateProgressText.percentOf(0, 88_222_047))
        assertEquals(59, AppUpdateProgressText.percentOf(52_428_800, 88_222_047))
        assertEquals(100, AppUpdateProgressText.percentOf(88_222_047, 88_222_047))
        assertEquals(0, AppUpdateProgressText.percentOf(10, 0))
        assertEquals(0, AppUpdateProgressText.percentOf(10, -1))
    }

    @Test
    fun `building never throws and never leaves a format specifier behind`() {
        val samples = listOf(
            0L to -1L,
            0L to 0L,
            1L to 0L,
            0L to 88_222_047L,
            1L to 1L,
            88_222_047L to 88_222_047L,
            Long.MAX_VALUE to 1L,
            123L to Long.MAX_VALUE,
        )
        samples.forEach { pair ->
            val text = AppUpdateProgressText.build(pair.first, pair.second)
            assertTrue("must start with the label, got: " + text, text.startsWith("Downloading "))
            // The only '%' allowed in the output is the final glyph of a percent
            // label — anything else means a format specifier survived (the
            // v1.0.24 bug class rendered as "… · %" or "…· %d" style output).
            val percent = text.lastIndexOf('%')
            if (percent >= 0) {
                assertTrue("stray '%' inside progress text: " + text, percent == text.length - 1)
                assertTrue("percent label not preceded by a number: " + text, text.getOrNull(percent - 1)?.isDigit() == true)
            }
        }
    }
}
