package com.nuanke.focus.focus

import com.nuanke.focus.data.FocusPhase
import com.nuanke.focus.data.FocusState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FocusCycleTest {
    @Test
    fun `completed focus round enters break and records its duration`() {
        val transition = FocusCycle.advance(
            state = runningState(phase = FocusPhase.FOCUS, currentRound = 1, totalRounds = 3),
            nowEpochMillis = 1_000L,
        )

        assertEquals(FocusCycleEvent.FOCUS_ENDED_FOR_BREAK, transition.event)
        assertEquals(FocusPhase.BREAK, transition.state.phase)
        assertEquals(301_000L, transition.state.phaseEndsAtEpochMillis)
        assertEquals(25 * 60_000L, transition.completedFocusMillis)
        assertTrue(transition.state.running)
    }

    @Test
    fun `completed break starts the next focus round`() {
        val transition = FocusCycle.advance(
            state = runningState(phase = FocusPhase.BREAK, currentRound = 2, totalRounds = 4),
            nowEpochMillis = 2_000L,
        )

        assertEquals(FocusCycleEvent.FOCUS_STARTED, transition.event)
        assertEquals(FocusPhase.FOCUS, transition.state.phase)
        assertEquals(3, transition.state.currentRound)
        assertEquals(1_502_000L, transition.state.phaseEndsAtEpochMillis)
        assertEquals(0L, transition.completedFocusMillis)
    }

    @Test
    fun `final focus round completes the session without another break`() {
        val transition = FocusCycle.advance(
            state = runningState(phase = FocusPhase.FOCUS, currentRound = 4, totalRounds = 4),
            nowEpochMillis = 3_000L,
        )

        assertEquals(FocusCycleEvent.SESSION_COMPLETED, transition.event)
        assertEquals(FocusPhase.COMPLETED, transition.state.phase)
        assertEquals(3_000L, transition.state.phaseEndsAtEpochMillis)
        assertFalse(transition.state.running)
        assertEquals(25 * 60_000L, transition.completedFocusMillis)
    }

    private fun runningState(
        phase: FocusPhase,
        currentRound: Int,
        totalRounds: Int,
    ) = FocusState(
        running = true,
        phase = phase,
        focusMinutes = 25,
        breakMinutes = 5,
        currentRound = currentRound,
        totalRounds = totalRounds,
    )
}
