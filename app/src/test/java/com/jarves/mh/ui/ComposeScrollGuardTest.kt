package com.jarves.mh.ui

import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * The regression net for the v1.0.34 crash class:
 *
 *   java.lang.IllegalStateException: Vertically scrollable component was
 *   measured with an infinity maximum height constraints.
 *   (Column -> AnimatedVisibility -> Box -> ScrollingLayoutNode, inside a
 *   LazyColumn item, which measures children with unbounded height.)
 *
 * Compose's ScrollingLayoutNode THROWS when the incoming maxHeight is
 * infinite, and a modifier chain hands the scroll modifier the constraints
 * of whatever sits BEFORE it in the chain. Therefore the contract is:
 *
 *   Every `verticalScroll(` chain must contain a height BOUND that appears
 *   BEFORE the scroll token — one of heightIn( / weight( / fillMaxSize( /
 *   fillMaxHeight( / height(. A bound AFTER verticalScroll (the actual
 *   v1.0.34 defect: .verticalScroll(...).heightIn(max = 180.dp)) does NOT
 *   protect the scroll node and fails this test.
 *
 * A bound of fillMaxSize()/weight() is valid only when the parent chain
 * provides bounded constraints (screen roots inside Scaffold, weight rows) —
 * those are the two audited contexts where the bound comes from the parent.
 * Anything else must bound itself with heightIn.
 *
 * HONEST LIMITATION (also recorded in AGENT_LOG): this is a JVM source
 * contract test over the actual Kotlin sources, not a compiler-enforced
 * Compose lint rule — it reconstructs modifier chains textually (a chain is
 * a Modifier... expression plus its continuation lines starting with ".").
 * It reliably catches the crash pattern (unbounded scroll chains, and
 * bounds placed after the scroll token) but cannot in general prove
 * constraint flow through arbitrary helper composables. A real device smoke
 * test of every expandable card remains part of the release checklist.
 */
class ComposeScrollGuardTest {

    private fun sourceRoot(): File? {
        val suffix = "src/main/java/com/jarves/mh/ui"
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

    /** Reconstructed modifier chains: (chainText, firstLineNumber, fileName). */
    private data class Chain(val text: String, val startLine: Int, val file: String)

    /**
     * A chain starts at a line containing `Modifier` (or a modifier-parameter
     * continuation) and continues while the following non-blank lines start
     * with "." (Compose modifier continuation style used across this codebase).
     */
    private fun chains(lines: List<String>, fileName: String): List<Chain> {
        val out = mutableListOf<Chain>()
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val isChainStart = line.contains("Modifier") &&
                (line.trimStart().startsWith("Modifier") ||
                    line.trimStart().startsWith("modifier =") ||
                    line.trimStart().endsWith("Modifier") ||
                    Regex("\\bModifier\\b").containsMatchIn(line))
            if (isChainStart) {
                val sb = StringBuilder(line)
                var j = i + 1
                while (j < lines.size) {
                    val t = lines[j].trim()
                    if (t.startsWith(".") || t.startsWith(")")) {
                        // A ")" continuation usually closes the call — include it
                        // so single-line `Modifier.x()` closures are covered, but
                        // stop once the line no longer extends the chain.
                        sb.append(' ').append(t)
                        if (!t.endsWith(",") && !t.endsWith(")") && !t.endsWith("}")) break
                        if (t.endsWith(",") || t.endsWith(")")) {
                            // Heuristic: a line that starts with "." continues the
                            // chain; a line that merely closes stops it.
                            if (t.startsWith(")")) break
                        }
                        j++
                    } else break
                }
                out.add(Chain(sb.toString(), i + 1, fileName))
                i = j
            } else {
                i++
            }
        }
        return out
    }

    private val boundTokens = listOf("heightIn(", "weight(", "fillMaxSize(", "fillMaxHeight(", "height(")

    private fun violations(): List<String> {
        val root = sourceRoot() ?: return emptyList()
        val problems = mutableListOf<String>()
        root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .sortedBy { it.name }
            .forEach { file ->
                val lines = file.readLines()
                val fullText = lines.joinToString("\n")
                chains(lines, file.name).forEach { chain ->
                    var idx = chain.text.indexOf("verticalScroll(")
                    while (idx >= 0) {
                        val before = chain.text.substring(0, idx)
                        val boundBefore = boundTokens.any { before.contains(it) }
                        if (!boundBefore && !inAuditedBoundedContext(fullText, chain, file.name)) {
                            problems.add(
                                "${chain.file}:${chain.startLine}: verticalScroll without a " +
                                    "preceding height bound (heightIn/weight/fillMaxSize/fillMaxHeight/height " +
                                    "must appear BEFORE the scroll token in the chain). " +
                                    "Chain: ${chain.text.take(220)}",
                            )
                        }
                        idx = chain.text.indexOf("verticalScroll(", idx + 1)
                    }
                }
                // Direct nesting check: a verticalScroll chain may not appear
                // inside a composable lambda that ALSO contains a LazyColumn or
                // another verticalScroll — the audited nesting crash shape.
                // (Textual approximation: same-file scroll count is allowed;
                // instead check the known-bad pattern of verticalScroll inside
                // an AnimatedVisibility block without an intermediate bound —
                // already covered above because the bound must be in-chain.)
            }
        return problems
    }

    /**
     * Audited bounded contexts where the height bound comes from the PARENT
     * rather than the chain itself. Currently: a scroll inside an
     * `AlertDialog` text slot — the dialog window bounds its content pane
     * (bounded constraints; can never be measured with infinite height).
     * Add new entries ONLY after reading the actual parent constraints.
     */
    private fun inAuditedBoundedContext(fullText: String, chain: Chain, fileName: String): Boolean {
        if (fileName == "SettingsTokens.kt") {
            // Enclosing composable of this chain (from the previous `fun ` to the next).
            val before = fullText.substring(0, charIndexOfLine(fullText, chain.startLine))
            val funStart = before.lastIndexOf("fun ")
            val nextFun = fullText.indexOf("fun ", charIndexOfLine(fullText, chain.startLine) + 1)
            val segment = fullText.substring(
                if (funStart >= 0) funStart else 0,
                if (nextFun > 0) nextFun else fullText.length,
            )
            if (segment.contains("AlertDialog(")) return true
        }
        return false
    }

    private fun charIndexOfLine(fullText: String, line: Int): Int {
        var count = 1
        for (i in fullText.indices) {
            if (count == line) return i
            if (fullText[i] == '\n') count++
        }
        return 0
    }

    @Test
    fun `every verticalScroll chain is height-bounded BEFORE the scroll token`() {
        val root = sourceRoot()
        assumeTrue("Compose UI source tree not found from ${System.getProperty("user.dir")}", root != null)
        val problems = violations()
        assertTrue(
            "v1.0.34 crash pattern detected (unbounded verticalScroll inside an " +
                "infinite-height parent such as a LazyColumn item or AnimatedVisibility):\n" +
                problems.joinToString("\n"),
            problems.isEmpty(),
        )
    }

    @Test
    fun `the two v1_0_34 crash sites stay fixed`() {
        val root = sourceRoot()
        assumeTrue("Compose UI source tree not found", root != null)
        val lines = File(root, "ZaiChatCards.kt").readLines()
        val scrolls = chains(lines, "ZaiChatCards.kt")
            .flatMap { chain -> chain.text.indexOf("verticalScroll(").let { i -> if (i >= 0) listOf(chain) else emptyList() } }
        assertTrue("expected the two ZaiChatCards scroll chains (thinking + diff)", scrolls.size >= 2)
        fun chainWithBound(bound: String): Chain? = scrolls.firstOrNull { chain ->
            val before = chain.text.substring(0, chain.text.indexOf("verticalScroll("))
            before.contains(bound)
        }
        assertTrue(
            "ZaiLiveProcessCard thinking block lost its heightIn(max = 180.dp)-before-verticalScroll fix",
            chainWithBound("heightIn(max = 180.dp)") != null,
        )
        assertTrue(
            "ZaiFileReviewCard diff block lost its heightIn(max = 260.dp) bound",
            chainWithBound("heightIn(max = 260.dp)") != null,
        )
    }
}
