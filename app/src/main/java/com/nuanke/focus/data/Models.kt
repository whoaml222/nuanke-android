package com.nuanke.focus.data

import kotlinx.serialization.Serializable

@Serializable
data class AppRule(
    val packageName: String,
    val appLabel: String,
    val enabled: Boolean = true,
    val sessionLimitMinutes: Int = 20,
    val dailyLimitMinutes: Int = 60,
    val cooldownMinutes: Int = 30,
    val reminder: String = "先把这一刻留给真正重要的事吧。",
)

@Serializable
data class DayStats(
    val date: String,
    val appUsageMillis: Map<String, Long> = emptyMap(),
    val focusMillis: Long = 0,
    val focusCompletions: Int = 0,
    val blockedAttempts: Int = 0,
)

@Serializable
data class StatsArchive(
    val days: List<DayStats> = emptyList(),
)

@Serializable
data class Cooldown(
    val packageName: String,
    val untilEpochMillis: Long,
)

@Serializable
data class RuntimeState(
    val cooldowns: List<Cooldown> = emptyList(),
)

@Serializable
enum class FocusPhase { FOCUS, BREAK, COMPLETED }

@Serializable
data class FocusState(
    val running: Boolean = false,
    val phase: FocusPhase = FocusPhase.COMPLETED,
    val taskLabel: String = "",
    val startedAtEpochMillis: Long = 0,
    val phaseEndsAtEpochMillis: Long = 0,
    val focusMinutes: Int = 25,
    val breakMinutes: Int = 5,
    val currentRound: Int = 1,
    val totalRounds: Int = 4,
    val blockedPackages: Set<String> = emptySet(),
) {
    fun blocks(packageName: String, nowEpochMillis: Long): Boolean =
        running &&
            phase == FocusPhase.FOCUS &&
            phaseEndsAtEpochMillis > nowEpochMillis &&
            packageName in blockedPackages
}

