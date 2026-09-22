package com.nuanke.focus.domain

import com.nuanke.focus.data.FocusPhase
import com.nuanke.focus.data.FocusState
import com.nuanke.focus.focus.FocusCycle
import com.nuanke.focus.focus.FocusCycleEvent
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class ReliabilityTest {
    @Test fun `clock counts subsecond switches without inventing suspended time`() {
        val clock = UsageClock()
        clock.reset(100)
        assertEquals(450L, clock.tick(550, true))
        assertEquals(0L, clock.tick(1550, false))
        assertEquals(0L, clock.tick(7000, true))
        assertEquals(1000L, clock.tick(8000, true))
        assertEquals(0L, clock.tick(1, true))
    }

    @Test fun `usage is split at local midnight`() {
        val end = Instant.parse("2026-09-21T16:00:02Z").toEpochMilli()
        assertEquals(mapOf("2026-09-21" to 3000L, "2026-09-22" to 2000L),
            DailyDurations.split(end, 5000, ZoneId.of("Asia/Shanghai")))
    }

    @Test fun `day splitting respects DST and duration conservation`() {
        val end = Instant.parse("2026-03-09T04:00:00Z").toEpochMilli()
        val split = DailyDurations.split(end, 24 * 3600_000L, ZoneId.of("America/New_York"))
        assertEquals(23 * 3600_000L, split["2026-03-08"])
        assertEquals(24 * 3600_000L, split.values.sum())
        assertTrue(DailyDurations.split(end, -1).isEmpty())
    }

    @Test fun `entry reset and expiry allow subsequent blocking`() {
        val tracker = ForegroundEntryTracker(emptySet())
        tracker.observe("video")
        assertFalse(tracker.isBlocked("video")) // Failed overlay must not mark an entry.
        tracker.markBlocked("video")
        tracker.releaseBlock()
        assertTrue(tracker.markBlocked("video"))
        tracker.reset()
        assertNotNull(tracker.observe("video"))
        assertFalse(tracker.isBlocked("video"))
    }

    @Test fun `focus does not advance early or record completion twice`() {
        val state = FocusState(running = true, phase = FocusPhase.FOCUS, phaseEndsAtEpochMillis = 60000, totalRounds = 1)
        assertEquals(FocusCycleEvent.NONE, FocusCycle.advance(state, 59000).event)
        val done = FocusCycle.advance(state, 60000)
        assertEquals(FocusCycleEvent.SESSION_COMPLETED, done.event)
        assertEquals(0L, FocusCycle.advance(done.state, 61000).completedFocusMillis)
        assertEquals(FocusCycleEvent.NONE, FocusCycle.advance(done.state, 61000).event)
    }

    @Test fun `update accepts only release hosts secure transport and plain versions`() {
        UpdatePolicy.requireAllowedUrl("https://release-assets.githubusercontent.com/file.apk?signature=x")
        UpdatePolicy.requireVersion("0.2.1")
        listOf("http://github.com/file", "https://github.com.evil.test/file", "https://github.com@evil.test/file",
            "https://evil.test/file", "https://github.com:444/file", "https://raw.githubusercontent.com/file").forEach {
            assertTrue(runCatching { UpdatePolicy.requireAllowedUrl(it) }.isFailure)
        }
        listOf("../../outside", "0.2.1/../../x", "v0.2.1", "invalid", "0.2").forEach {
            assertTrue(runCatching { UpdatePolicy.requireVersion(it) }.isFailure)
        }
    }
}
