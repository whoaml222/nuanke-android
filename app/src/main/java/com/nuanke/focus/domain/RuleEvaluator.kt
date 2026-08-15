package com.nuanke.focus.domain

import com.nuanke.focus.data.AppRule

enum class BlockReason { FOCUS, COOLDOWN, SESSION_LIMIT, DAILY_LIMIT }

sealed interface RuleDecision {
    data object Allow : RuleDecision
    data class Block(val reason: BlockReason, val untilEpochMillis: Long) : RuleDecision
}

object RuleEvaluator {
    fun evaluate(
        rule: AppRule,
        sessionElapsedMillis: Long,
        dailyElapsedMillis: Long,
        nowEpochMillis: Long,
        nextMidnightEpochMillis: Long,
        cooldownUntilEpochMillis: Long?,
        blockedByFocus: Boolean,
        focusEndsAtEpochMillis: Long = 0,
    ): RuleDecision {
        if (!rule.enabled) return RuleDecision.Allow
        if (blockedByFocus) {
            return RuleDecision.Block(BlockReason.FOCUS, focusEndsAtEpochMillis)
        }
        if (cooldownUntilEpochMillis != null && cooldownUntilEpochMillis > nowEpochMillis) {
            return RuleDecision.Block(BlockReason.COOLDOWN, cooldownUntilEpochMillis)
        }
        val dailyLimitMillis = rule.dailyLimitMinutes.coerceAtLeast(0) * 60_000L
        if (dailyLimitMillis > 0 && dailyElapsedMillis >= dailyLimitMillis) {
            return RuleDecision.Block(BlockReason.DAILY_LIMIT, nextMidnightEpochMillis)
        }
        val sessionLimitMillis = rule.sessionLimitMinutes.coerceAtLeast(0) * 60_000L
        if (sessionLimitMillis > 0 && sessionElapsedMillis >= sessionLimitMillis) {
            val until = nowEpochMillis + rule.cooldownMinutes.coerceAtLeast(1) * 60_000L
            return RuleDecision.Block(BlockReason.SESSION_LIMIT, until)
        }
        return RuleDecision.Allow
    }
}

