package com.jarves.mh.zweb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ZaiSheetUserAgentTest {

    @Test
    fun `webview markers are stripped from a canonical android webview ua`() {
        val wv = "Mozilla/5.0 (Linux; Android 14; Pixel 8 Build/UD1A.230803.004; wv) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/130.0.0.0 Mobile Safari/537.36"
        val chrome = ZaiSheetUserAgent.chromeLike(wv)
        assertEquals(
            "Mozilla/5.0 (Linux; Android 14; Pixel 8 Build/UD1A.230803.004) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36",
            chrome,
        )
        // neither WebView marker survives
        assertFalseMarker(chrome, "; wv")
        assertFalseMarker(chrome, "Version/4.0")
    }

    @Test
    fun `plain chrome ua passes through untouched`() {
        val chrome = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/130.0.0.0 Mobile Safari/537.36"
        assertEquals(chrome, ZaiSheetUserAgent.chromeLike(chrome))
    }

    @Test
    fun `blank input returns blank and whitespace-only input falls back`() {
        assertEquals("", ZaiSheetUserAgent.chromeLike(""))
        // a UA made solely of the two markers collapses to blank → original returned
        val weird = "; wv Version/4.0 "
        assertTrue(ZaiSheetUserAgent.chromeLike(weird).isNotEmpty())
    }

    private fun assertFalseMarker(haystack: String, marker: String) {
        assertTrue("expected no '$marker' in: $haystack", !haystack.contains(marker))
    }
}
