package com.jarves.mh.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.0.34 CONCERN B: the workspace bottom tabs are a property of the PROJECT,
 * never of the chat engine. A Z.ai-engine project and an Antigravity project
 * must show the SAME tabs (Chat, Files, Terminal, Preview).
 */
class WorkspaceTabPolicyTest {

    @Test
    fun `zai and antigravity projects get the identical tab set`() {
        val forZai = WorkspaceTabPolicy.visibleTabs(engineTitle = "Z.ai")
        val forAntigravity = WorkspaceTabPolicy.visibleTabs(engineTitle = "Antigravity")
        val forNone = WorkspaceTabPolicy.visibleTabs(engineTitle = null)
        assertEquals(forAntigravity, forZai)
        assertEquals(forAntigravity, forNone)
        assertEquals(listOf("Chat", "Files", "Terminal", "Preview"), forZai)
    }

    @Test
    fun `terminal and preview are never gated on the engine`() {
        // The explicit rule: Terminal/Preview depend only on the project folder.
        WorkspaceTabPolicy.visibleTabs("Z.ai").let { tabs ->
            assertTrue(tabs.contains(WorkspaceTabPolicy.TAB_TERMINAL))
            assertTrue(tabs.contains(WorkspaceTabPolicy.TAB_PREVIEW))
        }
        WorkspaceTabPolicy.visibleTabs("anything-else").let { tabs ->
            assertTrue(tabs.contains(WorkspaceTabPolicy.TAB_TERMINAL))
            assertTrue(tabs.contains(WorkspaceTabPolicy.TAB_PREVIEW))
        }
    }

    @Test
    fun `preview usability needs a ready flag and a url`() {
        assertTrue(WorkspaceTabPolicy.previewUsable(previewReady = true, previewUrl = "http://127.0.0.1:8000/"))
        assertFalse(WorkspaceTabPolicy.previewUsable(previewReady = false, previewUrl = "http://127.0.0.1:8000/"))
        assertFalse(WorkspaceTabPolicy.previewUsable(previewReady = true, previewUrl = null))
        assertFalse(WorkspaceTabPolicy.previewUsable(previewReady = true, previewUrl = ""))
    }
}
