package com.jarves.mh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class CrashGuardTest {

    private val stack = """
        java.lang.IllegalStateException: Update download failed (HTTP 403)
            at com.jarves.mh.update.AppUpdater.check(AppUpdater.kt:43)
            at com.jarves.mh.ui.MainViewModel.checkForAppUpdate(MainViewModel.kt:1257)
        Caused by: java.io.IOException: unexpected end of stream
            at java.net.HttpURLConnection.readLine(HttpURLConnection.java:1)
    """.trimIndent()

    @Test
    fun `formatCrash carries header, version, time, thread and stack`() {
        val report = CrashGuard.formatCrash(
            versionName = "1.0.24",
            versionCode = 25,
            atMillis = 1_760_796_392_000L,
            threadName = "DefaultDispatcher-worker-3",
            stack = stack,
        )
        assertTrue(report.startsWith("Mobile Harness crash report"))
        assertTrue(report.contains("Version: 1.0.24 (code 25)"))
        assertTrue(report.contains("At: "))
        assertTrue(report.contains("Thread: DefaultDispatcher-worker-3"))
        assertTrue(report.contains("java.lang.IllegalStateException: Update download failed (HTTP 403)"))
        assertTrue(report.contains("Caused by: java.io.IOException"))
    }

    @Test
    fun `summarize extracts the exception class and the At line`() {
        val report = CrashGuard.formatCrash("1.0.24", 25, 1_760_796_392_000L, "main", stack)
        val summary = CrashGuard.summarize(report)!!
        assertTrue(summary.startsWith("java.lang.IllegalStateException"))
        assertTrue(summary.contains("· "))
        assertFalse(summary.contains("Caused by"))
        assertFalse(summary.contains("\n"))
    }

    @Test
    fun `summarize handles an exception line without a message`() {
        val report = CrashGuard.formatCrash("1.0.24", 25, 1_760_796_392_000L, "main", "java.lang.OutOfMemoryError\n  at x")
        val summary = CrashGuard.summarize(report)!!
        assertTrue(summary.startsWith("java.lang.OutOfMemoryError · "))
        assertFalse(summary.contains("\n"))
    }

    @Test
    fun `summarize returns null without an At line`() {
        assertNull(CrashGuard.summarize("not a crash report"))
        assertNull(CrashGuard.summarize(""))
    }

    @Test
    fun `bound keeps head and tail of an over-long report`() {
        val long = "A".repeat(CrashGuard.MAX_REPORT_CHARS) + "MIDDLE".repeat(10) + "Z".repeat(100)
        val bounded = CrashGuard.bound(long)
        assertTrue(bounded.length < CrashGuard.MAX_REPORT_CHARS + 200)
        assertTrue(bounded.contains("… [middle of the report trimmed] …"))
        assertTrue(bounded.endsWith("Z".repeat(100)))
    }

    @Test
    fun `bound leaves a short report untouched`() {
        val short = "Mobile Harness crash report\nVersion: 1.0.24 (code 25)"
        assertEquals(short, CrashGuard.bound(short))
    }

    @Test
    fun `stackTraceOf includes the cause chain`() {
        val trace = CrashGuard.stackTraceOf(IllegalStateException("outer", IOException("inner")))
        assertTrue(trace.contains("java.lang.IllegalStateException: outer"))
        assertTrue(trace.contains("Caused by: java.io.IOException: inner"))
    }
}
