package com.jarves.mh.zweb

/**
 * Pure turn-policy for the Z.ai project-chat engine.
 *
 * Conversation continuation (documented decision, AGENT_LOG v1.0.25):
 *  - Each turn CONTINUES the same chat.z.ai conversation for a project chat —
 *    the site remembers the previous turns, so the app only refreshes the
 *    workspace context. This keeps follow-up messages cheap and coherent.
 *  - A NEW Z.ai conversation is started when the app cannot trust a live page
 *    conversation for this chat: the chat's first engine turn, after an app
 *    restart, after Stop, or after a failed turn. A fresh conversation carries
 *    a history recap so the model still understands the follow-up.
 *
 * NEED_FILES rounds are bounded so a model that keeps asking can never spin
 * the engine forever: at most [MAX_NEED_FILES_ROUNDS] fulfilment rounds per
 * user message, after which the last reply is shown as-is.
 */
object ZaiChatTurnPolicy {

    const val MAX_NEED_FILES_ROUNDS = 3

    data class TurnDecision(
        val freshConversation: Boolean,
        /** Why this decision was made (for logs/diagnostics, never shown as UX copy). */
        val reason: String,
    )

    /**
     * Decides fresh vs continue. [pageConversationChatId] is the chat that owns
     * the current chat.z.ai page conversation (null = none / not trusted).
     */
    fun decideTurn(pageConversationChatId: String?, chatId: String): TurnDecision =
        if (pageConversationChatId == chatId) {
            TurnDecision(freshConversation = false, reason = "continuing the chat's live Z.ai conversation")
        } else {
            TurnDecision(
                freshConversation = true,
                reason = if (pageConversationChatId == null) {
                    "no live Z.ai conversation (new chat, restart, stop, or previous error)"
                } else {
                    "the live Z.ai conversation belongs to a different project chat"
                },
            )
        }

    /** Whether another automatic NEED_FILES fulfilment round is allowed. */
    fun canFulfilMore(roundsUsed: Int): Boolean = roundsUsed in 0 until MAX_NEED_FILES_ROUNDS
}
