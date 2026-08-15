package com.nuanke.focus.domain

import com.nuanke.focus.data.AppRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RuleEvaluatorTest {
    private val rule = AppRule(
        packageName = "video.app",
        appLabel = "视频",
        sessionLimitMinutes = 20,
        dailyLimitMinutes = 60,
        cooldownMinutes = 30,
    )
    private val now = 1_000_000L
    private val midnight = 86_400_000L

    @Test
    fun `allows while both limits remain`() {
        val result = evaluate(session = 19 * 60_000L, daily = 59 * 60_000L)
        assertEquals(RuleDecision.Allow, result)
    }

    @Test
    fun `session limit starts configured cooldown`() {
        val result = evaluate(session = 20 * 60_000L, daily = 20 * 60_000L)
        assertEquals(
            RuleDecision.Block(BlockReason.SESSION_LIMIT, now + 30 * 60_000L),
            result,
        )
    }

    @Test
    fun `daily limit blocks until next midnight`() {
        val result = evaluate(session = 2_000L, daily = 60 * 60_000L)
        assertEquals(RuleDecision.Block(BlockReason.DAILY_LIMIT, midnight), result)
    }

    @Test
    fun `existing cooldown takes priority over fresh counters`() {
        val until = now + 10_000L
        val result = evaluate(session = 0, daily = 0, cooldown = until)
        assertEquals(RuleDecision.Block(BlockReason.COOLDOWN, until), result)
    }

    @Test
    fun `focus mode has highest blocking priority`() {
        val focusEnd = now + 50_000L
        val result = evaluate(session = 0, daily = 0, cooldown = now + 20_000L, focus = true, focusEnd = focusEnd)
        assertEquals(RuleDecision.Block(BlockReason.FOCUS, focusEnd), result)
    }

    @Test
    fun `disabled rule never blocks`() {
        val result = RuleEvaluator.evaluate(
            rule = rule.copy(enabled = false),
            sessionElapsedMillis = Long.MAX_VALUE,
            dailyElapsedMillis = Long.MAX_VALUE,
            nowEpochMillis = now,
            nextMidnightEpochMillis = midnight,
            cooldownUntilEpochMillis = now + 999_999,
            blockedByFocus = true,
            focusEndsAtEpochMillis = now + 999_999,
        )
        assertTrue(result is RuleDecision.Allow)
    }

    private fun evaluate(
        session: Long,
        daily: Long,
        cooldown: Long? = null,
        focus: Boolean = false,
        focusEnd: Long = 0,
    ) = RuleEvaluator.evaluate(
        rule = rule,
        sessionElapsedMillis = session,
        dailyElapsedMillis = daily,
        nowEpochMillis = now,
        nextMidnightEpochMillis = midnight,
        cooldownUntilEpochMillis = cooldown,
        blockedByFocus = focus,
        focusEndsAtEpochMillis = focusEnd,
    )
}

