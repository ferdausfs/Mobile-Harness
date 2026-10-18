package com.jarves.mh.ui

import com.jarves.mh.model.AgentKind
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * JVM tests for the agent-tab tap/switch decision policy behind the
 * "tapping Claude Code / Antigravity sometimes does nothing" fix.
 */
class AgentTabPolicyTest {

    // ── agentSwitchBlock: the ViewModel's authoritative switch guard ──

    @Test
    fun switchAllowedWhenIdle() {
        assertEquals(
            AgentSwitchBlock.NONE,
            agentSwitchBlock(
                isRunning = false,
                installingAgent = null,
                target = AgentKind.CLAUDE_CODE,
            ),
        )
    }

    @Test
    fun switchBlockedWhileTaskRuns() {
        assertEquals(
            AgentSwitchBlock.RUNNING,
            agentSwitchBlock(
                isRunning = true,
                installingAgent = null,
                target = AgentKind.ANTIGRAVITY,
            ),
        )
    }

    @Test
    fun switchBlockedWhileAnotherAgentInstalls() {
        assertEquals(
            AgentSwitchBlock.INSTALLING_OTHER,
            agentSwitchBlock(
                isRunning = false,
                installingAgent = AgentKind.CLAUDE_CODE,
                target = AgentKind.DEEPSEEK_HARNESS,
            ),
        )
    }

    @Test
    fun switchToTheInstallingAgentItselfIsAllowed() {
        // installAgent() switches to the agent it just installed while
        // agentInstalling is still set — that path must never be rejected.
        assertEquals(
            AgentSwitchBlock.NONE,
            agentSwitchBlock(
                isRunning = false,
                installingAgent = AgentKind.ANTIGRAVITY,
                target = AgentKind.ANTIGRAVITY,
            ),
        )
    }

    @Test
    fun installInProgressBeatsRunningCheck() {
        // Ordering contract: a pending install of another agent is reported
        // first; both blocks together still report INSTALLING_OTHER.
        assertEquals(
            AgentSwitchBlock.INSTALLING_OTHER,
            agentSwitchBlock(
                isRunning = true,
                installingAgent = AgentKind.CLAUDE_CODE,
                target = AgentKind.DEEPSEEK_HARNESS,
            ),
        )
    }

    // ── agentTabTapAction: the click-handler classification ──

    @Test
    fun tapOnInstalledAgentSwitches() {
        assertEquals(
            AgentTabTapAction.SWITCH,
            agentTabTapAction(AgentKind.CLAUDE_CODE, installed = true, isRunning = false, installingAgent = null),
        )
    }

    @Test
    fun tapOnUninstalledIdleAgentStartsInstall() {
        assertEquals(
            AgentTabTapAction.SHOW_INSTALL,
            agentTabTapAction(AgentKind.ANTIGRAVITY, installed = false, isRunning = false, installingAgent = null),
        )
    }

    @Test
    fun tapDuringOtherInstallIsRefusedNotSilent() {
        // Regression: the tabs used to be disabled during any install, so a
        // tap was swallowed with zero feedback (progress card only rendered
        // when viewedAgent matched the installing agent).
        assertEquals(
            AgentTabTapAction.REFUSED_INSTALLING,
            agentTabTapAction(AgentKind.DEEPSEEK_HARNESS, installed = true, isRunning = false, installingAgent = AgentKind.CLAUDE_CODE),
        )
        assertEquals(
            AgentTabTapAction.REFUSED_INSTALLING,
            agentTabTapAction(AgentKind.ANTIGRAVITY, installed = false, isRunning = false, installingAgent = AgentKind.CLAUDE_CODE),
        )
    }

    @Test
    fun tapOnInstallingAgentRevealsProgress() {
        assertEquals(
            AgentTabTapAction.SHOW_INSTALL_PROGRESS,
            agentTabTapAction(AgentKind.CLAUDE_CODE, installed = false, isRunning = false, installingAgent = AgentKind.CLAUDE_CODE),
        )
    }

    @Test
    fun tapOnUninstalledAgentWhileRunningIsRefused() {
        assertEquals(
            AgentTabTapAction.REFUSED_RUNNING,
            agentTabTapAction(AgentKind.CLAUDE_CODE, installed = false, isRunning = true, installingAgent = null),
        )
    }

    @Test
    fun tapOnInstalledAgentWhileRunningStillRoutesToSelectAgent() {
        // SWITCH keeps the historical behavior: the ViewModel re-checks
        // isRunning authoritatively and toasts; the UI never moves viewedAgent
        // optimistically past the isRunning guard.
        assertEquals(
            AgentTabTapAction.SWITCH,
            agentTabTapAction(AgentKind.ANTIGRAVITY, installed = true, isRunning = true, installingAgent = null),
        )
    }

    // ── Tab highlight rule (v1.0.20 double-highlight fix) ──
    //
    // Bug: with the Z.ai tab open, the last-viewed CLI tab kept its highlight
    // because the rule was plain `viewedAgent == agent` and `viewedAgent` is
    // untouched while the Z.ai flag is on. The highlight must be exclusive.

    @Test
    fun noCliTabIsHighlightedWhileTheZaiTabIsOpen() {
        AgentKind.entries.forEach { agent ->
            org.junit.Assert.assertFalse(
                "agent $agent must not be highlighted while viewing Z.ai",
                agentTabSelected(viewedAgent = agent, agent = agent, viewingZai = true),
            )
        }
    }

    @Test
    fun cliTabHighlightFollowsTheViewedAgentOnlyWhenZaiIsClosed() {
        org.junit.Assert.assertTrue(
            agentTabSelected(viewedAgent = AgentKind.CLAUDE_CODE, agent = AgentKind.CLAUDE_CODE, viewingZai = false),
        )
        org.junit.Assert.assertFalse(
            agentTabSelected(viewedAgent = AgentKind.CLAUDE_CODE, agent = AgentKind.DEEPSEEK_HARNESS, viewingZai = false),
        )
        org.junit.Assert.assertFalse(
            agentTabSelected(viewedAgent = AgentKind.CLAUDE_CODE, agent = AgentKind.ANTIGRAVITY, viewingZai = false),
        )
    }

    @Test
    fun aNullViewedAgentNeverHighlightsAnyCliTab() {
        AgentKind.entries.forEach { agent ->
            org.junit.Assert.assertFalse(agentTabSelected(null, agent, viewingZai = false))
            org.junit.Assert.assertFalse(agentTabSelected(null, agent, viewingZai = true))
        }
    }

    @Test
    fun exactlyOneTabIsHighlightedInEveryCombination() {
        // The Z.ai tab and the CLI tabs are mutually exclusive: whatever the
        // state, at most one highlight can render.
        AgentKind.entries.forEach { viewed ->
            for (viewingZai in booleanArrayOf(false, true)) {
                AgentKind.entries.forEach { agent ->
                    val cliSelected = agentTabSelected(viewed, agent, viewingZai)
                    val zaiSelected = zaiTabSelected(viewingZai)
                    org.junit.Assert.assertFalse("exclusivity broken at viewed=$viewed agent=$agent zai=$viewingZai", cliSelected && zaiSelected)
                }
            }
        }
    }

    @Test
    fun zaiTabHighlightFollowsItsOwnFlag() {
        org.junit.Assert.assertTrue(zaiTabSelected(true))
        org.junit.Assert.assertFalse(zaiTabSelected(false))
    }
}
