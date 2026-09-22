package com.nuanke.focus.domain

import java.time.Instant
import java.time.ZoneId

object DailyDurations {
    fun split(endEpochMillis: Long, durationMillis: Long, zone: ZoneId = ZoneId.systemDefault()): Map<String, Long> {
        val result = linkedMapOf<String, Long>()
        var cursor = endEpochMillis - durationMillis.coerceAtLeast(0)
        while (cursor < endEpochMillis) {
            val date = Instant.ofEpochMilli(cursor).atZone(zone).toLocalDate()
            val end = minOf(endEpochMillis, date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli())
            result[date.toString()] = (result[date.toString()] ?: 0) + end - cursor
            cursor = end
        }
        return result
    }
}
