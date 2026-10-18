package com.jarves.mh.zweb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * The regression net for the v1.0.23 bug class. The original defect passed
 * every JVM test because headless Chromium AWAITS promises while a real
 * Android WebView's `evaluateJavascript` never does — a Promise completion
 * value reaches the callback as "{}" / null.
 *
 * This test reads the ACTUAL Kotlin sources (the unit-test working directory
 * is the app module) and enforces, structurally:
 *
 *  1. `evaluateJavascript(` may only appear in the audited files — a new call
 *     site fails this test until a human audits it for the Promise trap.
 *  2. No file may call evaluateJavascript with the auth probe in the old
 *     one-shot style (`evaluateJavascript(authProbeJs)`) — results must flow
 *     through the store-and-poll contract (runAuthProbeScript).
 *  3. Both probe consumers go through runAuthProbeScript and surface the
 *     poll-timeout verdict.
 *
 * Companion script-level checks live in [ZaiProbeScriptsTest]; the manual
 * on-device verification steps live in docs/zai-auth-probe-verification.md.
 */
class EvaluateJavascriptCallSiteGuardTest {

    private fun sourceRoot(): File? {
        val suffix = "src/main/java/com/jarves/mh"
        var dir: File? = null
        var candidate = File(System.getProperty("user.dir") ?: ".").absoluteFile
        repeat(6) {
            listOf(suffix, "app/$suffix").forEach { rel ->
                val f = File(candidate, rel)
                if (dir == null && f.isDirectory) dir = f
            }
            candidate = candidate.parentFile ?: return@repeat
        }
        return dir
    }

    @Test
    fun `evaluateJavascript call sites stay inside the audited file set`() {
        val root = sourceRoot()
        assumeTrue("Kotlin source tree not found from ${System.getProperty("user.dir")}", root != null)
        val hits = root!!.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .map { it to it.readText() }
            .filter { (_, text) -> text.contains("evaluateJavascript(") }
            .toList()
        val fileNames = hits.map { (f, _) -> f.name }.toSet()
        assertEquals(
            "New evaluateJavascript call site detected — audit it for the Promise trap " +
                "(a script whose completion value is a Promise reads back as \"{}\" on a real WebView) " +
                "and then add the file to this audited set.",
            setOf("AgentZaiCard.kt", "ZaiWebEngine.kt"),
            fileNames,
        )
    }

    @Test
    fun `no one-shot auth probe evaluation remains`() {
        val root = sourceRoot()
        assumeTrue("Kotlin source tree not found", root != null)
        val card = File(root, "ui/AgentZaiCard.kt").readText()
        val engine = File(root, "zweb/ZaiWebEngine.kt").readText()
        // The broken v1.0.20-22 pattern: the probe script (its completion value
        // is a Promise) passed straight to evaluateJavascript.
        assertFalse(card.contains("evaluateJavascript(authProbeJs)"))
        assertFalse(engine.contains("evaluateJavascript(ZaiProbeScripts.buildAuthProbe"))
        assertFalse(engine.contains("evaluateJs(ZaiProbeScripts.buildAuthProbe("))
    }

    @Test
    fun `both consumers use the store-and-poll contract and surface timeouts`() {
        val root = sourceRoot()
        assumeTrue("Kotlin source tree not found", root != null)
        val card = File(root, "ui/AgentZaiCard.kt").readText()
        val engine = File(root, "zweb/ZaiWebEngine.kt").readText()
        assertTrue(engine.contains("runAuthProbeScript("))
        assertTrue(card.contains("runAuthProbeScript("))
        assertTrue(engine.contains("probeTimeoutVerdict("))
        assertTrue(card.contains("probeTimeoutVerdict("))
        // The reader must be the synchronous one — never the injector — in the read slot.
        assertTrue(engine.contains("buildAuthResultReaderScript()"))
        assertTrue(card.contains("buildAuthResultReaderScript()"))
    }

    @Test
    fun `engine bridge doc still warns about promise completion values`() {
        val root = sourceRoot()
        assumeTrue("Kotlin source tree not found", root != null)
        val engine = File(root, "zweb/ZaiWebEngine.kt").readText()
        assertTrue(
            "ZaiWebEngine.evaluateJs must keep its synchronous-completion-value contract note",
            engine.contains("suspendCancellableCoroutine"),
        )
    }
}
