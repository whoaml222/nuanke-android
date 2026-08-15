package com.nuanke.focus.domain

import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

class TimeMathTest {
    @Test
    fun `next midnight uses local timezone`() {
        val zone = ZoneId.of("Asia/Shanghai")
        val now = Instant.parse("2026-08-15T12:30:00Z").toEpochMilli()
        val expected = Instant.parse("2026-08-15T16:00:00Z").toEpochMilli()
        assertEquals(expected, TimeMath.nextMidnightEpochMillis(now, zone))
    }
}

