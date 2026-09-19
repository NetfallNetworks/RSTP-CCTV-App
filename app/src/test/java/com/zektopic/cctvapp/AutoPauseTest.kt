package com.zektopic.cctvapp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoPauseTest {
    private var now = 1_000_000L
    private val pause = AutoPause { now }

    @Test
    fun `pauses for the given minutes then resumes by itself`() {
        pause.pauseFor(10)
        assertTrue(pause.isPaused)
        assertEquals(1_000_000L + 600_000L, pause.pausedUntilMs)
        now += 600_001L
        assertFalse(pause.isPaused)
        assertNull(pause.pausedUntilMs)
    }

    @Test
    fun `resume clears it early`() {
        pause.pauseFor(10)
        pause.resume()
        assertFalse(pause.isPaused)
    }

    @Test
    fun `a shorter pause never cuts a longer one`() {
        pause.pauseFor(10)
        pause.pauseFor(1)
        assertEquals(1_000_000L + 600_000L, pause.pausedUntilMs)
    }
}
