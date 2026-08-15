package com.nuanke.focus.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ForegroundEntryTrackerTest {
    private val tracker = ForegroundEntryTracker(
        setOf("com.nuanke.focus", "com.android.systemui"),
    )

    @Test
    fun `repeated restricted-app and overlay events count once`() {
        assertNotNull(tracker.observe("video.app"))
        assertTrue(tracker.markBlocked("video.app"))

        repeat(66) {
            assertNull(tracker.observe("com.nuanke.focus"))
            assertNull(tracker.observe("video.app"))
            assertFalse(tracker.markBlocked("video.app"))
        }
    }

    @Test
    fun `leaving and reopening creates exactly one new block episode`() {
        assertNotNull(tracker.observe("video.app"))
        assertTrue(tracker.markBlocked("video.app"))

        val home = tracker.observe("launcher.app")
        assertEquals("video.app", home?.previousPackage)
        assertFalse(tracker.markBlocked("video.app"))

        val reopened = tracker.observe("video.app")
        assertEquals("launcher.app", reopened?.previousPackage)
        assertTrue(tracker.markBlocked("video.app"))
        assertFalse(tracker.markBlocked("video.app"))
    }

    @Test
    fun `system ui does not replace active package`() {
        tracker.observe("video.app")
        assertNull(tracker.observe("com.android.systemui"))
        assertTrue(tracker.markBlocked("video.app"))
    }
}
