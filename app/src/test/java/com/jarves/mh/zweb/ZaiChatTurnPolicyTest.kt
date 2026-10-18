package com.jarves.mh.zweb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ZaiChatTurnPolicyTest {

    @Test
    fun `same chat id continues the live conversation`() {
        val decision = ZaiChatTurnPolicy.decideTurn(pageConversationChatId = "chat-1", chatId = "chat-1")
        assertFalse(decision.freshConversation)
        assertTrue(decision.reason.contains("continuing"))
    }

    @Test
    fun `no live conversation starts fresh`() {
        val decision = ZaiChatTurnPolicy.decideTurn(pageConversationChatId = null, chatId = "chat-1")
        assertTrue(decision.freshConversation)
    }

    @Test
    fun `live conversation owned by another chat starts fresh`() {
        val decision = ZaiChatTurnPolicy.decideTurn(pageConversationChatId = "chat-9", chatId = "chat-1")
        assertTrue(decision.freshConversation)
        assertTrue(decision.reason.contains("different project chat"))
    }

    @Test
    fun `fulfilment rounds are bounded`() {
        assertTrue(ZaiChatTurnPolicy.canFulfilMore(0))
        assertTrue(ZaiChatTurnPolicy.canFulfilMore(ZaiChatTurnPolicy.MAX_NEED_FILES_ROUNDS - 1))
        assertFalse(ZaiChatTurnPolicy.canFulfilMore(ZaiChatTurnPolicy.MAX_NEED_FILES_ROUNDS))
        assertFalse(ZaiChatTurnPolicy.canFulfilMore(-1))
    }
}
