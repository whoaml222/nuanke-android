package com.nuanke.focus.domain

/** Count only observed, unlocked foreground time; preserve sub-second app switches. */
class UsageClock {
    private var lastElapsed = 0L
    private var initialized = false

    fun reset(nowElapsed: Long) { lastElapsed = nowElapsed; initialized = true }

    fun tick(nowElapsed: Long, interactive: Boolean): Long {
        if (!initialized) { reset(nowElapsed); return 0 }
        val elapsed = (nowElapsed - lastElapsed).coerceAtLeast(0)
        lastElapsed = nowElapsed
        // A long gap means the process was suspended; don't invent foreground use.
        return if (interactive && elapsed <= 5_000) elapsed else 0
    }
}
