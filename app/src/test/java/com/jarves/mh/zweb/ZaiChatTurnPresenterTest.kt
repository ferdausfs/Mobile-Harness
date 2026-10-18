package com.jarves.mh.zweb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ZaiChatTurnPresenterTest {

    @Test
    fun `progress titles map to sending and generating and reading`() {
        assertEquals("Connecting to Z.ai", ZaiChatTurnPresenter.progressTitle(ZaiRunPhase.OPENING_PAGE))
        assertEquals("Sending to Z.ai", ZaiChatTurnPresenter.progressTitle(ZaiRunPhase.SENDING))
        assertEquals("Z.ai is generating", ZaiChatTurnPresenter.progressTitle(ZaiRunPhase.GENERATING))
        assertEquals("Reading answer", ZaiChatTurnPresenter.progressTitle(ZaiRunPhase.EXTRACTING))
    }

    @Test
    fun `non-progress phases return null so the live card stays put`() {
        assertNull(ZaiChatTurnPresenter.progressTitle(ZaiRunPhase.DONE))
        assertNull(ZaiChatTurnPresenter.progressTitle(ZaiRunPhase.IDLE))
        assertNull(ZaiChatTurnPresenter.progressTitle(ZaiRunPhase.NEEDS_LOGIN))
    }

    @Test
    fun `every progress phase has a detail line`() {
        listOf(
            ZaiRunPhase.OPENING_PAGE,
            ZaiRunPhase.SENDING,
            ZaiRunPhase.GENERATING,
            ZaiRunPhase.EXTRACTING,
        ).forEach { phase ->
            assertTrue(ZaiChatTurnPresenter.progressDetail(phase)!!.isNotBlank())
        }
    }

    @Test
    fun `login and captcha notices offer the sign-in sheet`() {
        val login = ZaiChatTurnPresenter.terminalNotice(ZaiRunPhase.NEEDS_LOGIN, "ignored")
        assertTrue(login!!.showSignIn)
        assertEquals(ZaiChatTurnPresenter.NoticeKind.NEEDS_LOGIN, login.kind)
        val captcha = ZaiChatTurnPresenter.terminalNotice(ZaiRunPhase.CAPTCHA, "ignored")
        assertTrue(captcha!!.showSignIn)
    }

    @Test
    fun `timeout error and canceled never offer sign-in`() {
        listOf(ZaiRunPhase.TIMEOUT, ZaiRunPhase.ERROR, ZaiRunPhase.CANCELED).forEach { phase ->
            val notice = ZaiChatTurnPresenter.terminalNotice(phase, "engine said: x")
            assertTrue(notice!!.message.isNotBlank())
            if (phase != ZaiRunPhase.CANCELED) {
                assertTrue(notice.message.contains("engine said: x"))
            }
            assertTrue(!notice.showSignIn)
        }
    }

    @Test
    fun `done has no terminal notice`() {
        assertNull(ZaiChatTurnPresenter.terminalNotice(ZaiRunPhase.DONE, null))
    }

    @Test
    fun `error without engine message falls back to generic copy`() {
        val notice = ZaiChatTurnPresenter.terminalNotice(ZaiRunPhase.ERROR, null)
        assertTrue(notice!!.message.contains("try again", ignoreCase = true))
    }
}
