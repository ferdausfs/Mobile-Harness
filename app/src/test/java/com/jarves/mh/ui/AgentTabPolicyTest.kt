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
}
