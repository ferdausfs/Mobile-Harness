package com.jarves.mh.zweb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthProbePollPolicyTest {

    private val config = AuthProbePollPolicy.Config(rounds = 2, readsPerRound = 3, readDelayMs = 250)

    @Test
    fun `first action of a round is always inject-and-read`() {
        assertEquals(
            AuthProbePollPolicy.Action.INJECT_AND_READ,
            AuthProbePollPolicy.next(AuthProbePollPolicy.State(), config),
        )
        assertEquals(
            AuthProbePollPolicy.Action.INJECT_AND_READ,
            AuthProbePollPolicy.next(AuthProbePollPolicy.State(round = 1, readInRound = 0), config),
        )
    }

    @Test
    fun `subsequent reads within a round are read-only`() {
        assertEquals(
            AuthProbePollPolicy.Action.READ,
            AuthProbePollPolicy.next(AuthProbePollPolicy.State(round = 0, readInRound = 1), config),
        )
        assertEquals(
            AuthProbePollPolicy.Action.READ,
            AuthProbePollPolicy.next(AuthProbePollPolicy.State(round = 0, readInRound = 2), config),
        )
    }

    @Test
    fun `exhausted rounds give up`() {
        assertEquals(
            AuthProbePollPolicy.Action.GIVE_UP,
            AuthProbePollPolicy.next(AuthProbePollPolicy.State(round = 2, readInRound = 0), config),
        )
        assertEquals(
            AuthProbePollPolicy.Action.GIVE_UP,
            AuthProbePollPolicy.next(AuthProbePollPolicy.State(round = 5, readInRound = 1), config),
        )
    }

    @Test
    fun `unreadable reads advance within the round then roll over`() {
        var state = AuthProbePollPolicy.State()
        state = AuthProbePollPolicy.afterUnreadableRead(state, config)
        assertEquals(AuthProbePollPolicy.State(round = 0, readInRound = 1), state)
        state = AuthProbePollPolicy.afterUnreadableRead(state, config)
        assertEquals(AuthProbePollPolicy.State(round = 0, readInRound = 2), state)
        // last read of the round -> next round, reset read counter
        state = AuthProbePollPolicy.afterUnreadableRead(state, config)
        assertEquals(AuthProbePollPolicy.State(round = 1, readInRound = 0), state)
    }

    @Test
    fun `rollover past the last round is a give-up on the next decision`() {
        var state = AuthProbePollPolicy.State()
        repeat(6) { state = AuthProbePollPolicy.afterUnreadableRead(state, config) }
        assertEquals(AuthProbePollPolicy.State(round = 2, readInRound = 0), state)
        assertEquals(AuthProbePollPolicy.Action.GIVE_UP, AuthProbePollPolicy.next(state, config))
    }

    @Test
    fun `total wait matches rounds times reads times delay`() {
        assertEquals(1500L, AuthProbePollPolicy.totalWaitMs(config))
        assertEquals(9000L, AuthProbePollPolicy.totalWaitMs(AuthProbePollPolicy.ENGINE_CHECK))
        assertEquals(1000L, AuthProbePollPolicy.totalWaitMs(AuthProbePollPolicy.SHEET_POLL))
        assertEquals(1500L, AuthProbePollPolicy.totalWaitMs(AuthProbePollPolicy.SHEET_FINAL))
    }

    @Test
    fun `sheet budgets stay short enough not to stall the automation loop`() {
        // The sheet tick runs every 2s; the poll budget must fit inside it with
        // headroom for the automation steps that follow in the same tick.
        assertTrue(AuthProbePollPolicy.totalWaitMs(AuthProbePollPolicy.SHEET_POLL) <= 1_200)
        // The close probe holds the dialog open; keep it under ~2s.
        assertTrue(AuthProbePollPolicy.totalWaitMs(AuthProbePollPolicy.SHEET_FINAL) <= 2_000)
    }
}
