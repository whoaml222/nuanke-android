package com.nuanke.focus.focus

import com.nuanke.focus.data.FocusPhase
import com.nuanke.focus.data.FocusState

enum class FocusCycleEvent {
    FOCUS_ENDED_FOR_BREAK,
    FOCUS_STARTED,
    SESSION_COMPLETED,
    NONE,
}

data class FocusCycleTransition(
    val state: FocusState,
    val event: FocusCycleEvent,
    val completedFocusMillis: Long = 0,
)

object FocusCycle {
    fun advance(state: FocusState, nowEpochMillis: Long): FocusCycleTransition = when (state.phase) {
        FocusPhase.FOCUS -> {
            val completedMillis = state.focusMinutes * 60_000L
            if (state.currentRound >= state.totalRounds) {
                FocusCycleTransition(
                    state = state.copy(
                        running = false,
                        phase = FocusPhase.COMPLETED,
                        phaseEndsAtEpochMillis = nowEpochMillis,
                    ),
                    event = FocusCycleEvent.SESSION_COMPLETED,
                    completedFocusMillis = completedMillis,
                )
            } else {
                FocusCycleTransition(
                    state = state.copy(
                        phase = FocusPhase.BREAK,
                        phaseEndsAtEpochMillis = nowEpochMillis + state.breakMinutes * 60_000L,
                    ),
                    event = FocusCycleEvent.FOCUS_ENDED_FOR_BREAK,
                    completedFocusMillis = completedMillis,
                )
            }
        }

        FocusPhase.BREAK -> FocusCycleTransition(
            state = state.copy(
                phase = FocusPhase.FOCUS,
                currentRound = state.currentRound + 1,
                phaseEndsAtEpochMillis = nowEpochMillis + state.focusMinutes * 60_000L,
            ),
            event = FocusCycleEvent.FOCUS_STARTED,
        )

        FocusPhase.COMPLETED -> FocusCycleTransition(
            state = state.copy(running = false),
            event = FocusCycleEvent.NONE,
        )
    }
}
