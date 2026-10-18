package com.jarves.mh.ui

import com.jarves.mh.model.AgentKind

/**
 * Pure decision logic for the Agent screen's engine-selector tabs.
 *
 * Extracted so the tap/switch rules can be unit-tested on the JVM without
 * Compose. The ViewModel ([MainViewModel.selectAgent], [MainViewModel.installAgent])
 * and the tab click handler in [AgentScreen] both read from here, so the UI's
 * optimistic `viewedAgent` move and the ViewModel's authoritative guards can
 * never disagree.
 *
 * Background (agent-tab no-op bug): the tabs used to be disabled while any
 * install was in flight and [AgentScreen] only showed the progress card when
 * `agentInstalling == viewedAgent`, so taps were swallowed with no feedback
 * whenever an install had been started elsewhere (e.g. from Settings). A
 * rejected switch also moved the viewed tab optimistically, rendering a blank
 * body. These helpers encode the single source of truth for those decisions.
 */

/** Why a request to make [AgentKind] the active agent cannot proceed right now. */
enum class AgentSwitchBlock {
    NONE,

    /** A task is running; switching would abandon the live session. */
    RUNNING,

    /** A different agent is being installed; switching mid-install desyncs the UI. */
    INSTALLING_OTHER,
}

/** What the tab click handler should do for a tap on an engine tab. */
enum class AgentTabTapAction {
    /** The switch can proceed: move the viewed tab and call onSelectAgent. */
    SWITCH,

    /** The agent is not installed and nothing blocks an install: show its install card. */
    SHOW_INSTALL,

    /** This tab's own install is already running: reveal its progress card. */
    SHOW_INSTALL_PROGRESS,

    /**
     * Another agent's install is running: report the tap through onInstallAgent
     * so the ViewModel explains with a toast (no silent no-op).
     */
    REFUSED_INSTALLING,

    /**
     * A task is running and the tapped agent is not installed: report through
     * onInstallAgent so the ViewModel explains with a toast.
     */
    REFUSED_RUNNING,
}

/**
 * Classifies whether switching the active agent to [target] is allowed.
 *
 * [installingAgent] equal to [target] is explicitly allowed: that is the
 * automatic switch [MainViewModel.installAgent] performs once its install
 * succeeds (the install state is still set at that moment).
 */
fun agentSwitchBlock(
    isRunning: Boolean,
    installingAgent: AgentKind?,
    target: AgentKind,
): AgentSwitchBlock = when {
    installingAgent != null && installingAgent != target -> AgentSwitchBlock.INSTALLING_OTHER
    isRunning -> AgentSwitchBlock.RUNNING
    else -> AgentSwitchBlock.NONE
}

/**
 * Maps a tab tap onto the action the handler should perform, given the
 * composition-time snapshot. The ViewModel re-validates every switch
 * authoritatively; this only decides what the tap should visibly do so no
 * tap is ever a silent no-op.
 */
fun agentTabTapAction(
    tapped: AgentKind,
    installed: Boolean,
    isRunning: Boolean,
    installingAgent: AgentKind?,
): AgentTabTapAction = when {
    installingAgent != null && tapped != installingAgent -> AgentTabTapAction.REFUSED_INSTALLING
    installingAgent != null -> AgentTabTapAction.SHOW_INSTALL_PROGRESS
    installed -> AgentTabTapAction.SWITCH
    isRunning -> AgentTabTapAction.REFUSED_RUNNING
    else -> AgentTabTapAction.SHOW_INSTALL
}

/**
 * Whether a CLI engine tab (Claude Code / DeepSeek / Antigravity) renders as
 * selected. The Z.ai web tab is highlighted by [zaiTabSelected]; the two are
 * mutually exclusive, so opening the Z.ai tab can never leave the last-viewed
 * engine tab highlighted too (the v1.0.19 double-highlight bug: `viewedAgent`
 * keeps its old value while the Z.ai flag is on).
 */
fun agentTabSelected(viewedAgent: AgentKind?, agent: AgentKind, viewingZai: Boolean): Boolean =
    !viewingZai && viewedAgent == agent

/** Whether the Z.ai web tab renders as selected. */
fun zaiTabSelected(viewingZai: Boolean): Boolean = viewingZai
