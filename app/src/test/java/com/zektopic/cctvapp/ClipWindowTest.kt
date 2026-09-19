package com.zektopic.cctvapp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClipWindowTest {

    private val second = 1_000_000L

    private fun window(startUs: Long = 0, triggeredAtUs: Long = 5 * second) =
        ClipWindow(postRollUs = 10 * second, maxClipUs = 60 * second, startUs = startUs, triggeredAtUs = triggeredAtUs)

    @Test
    fun `runs the post-roll past the trigger`() {
        val w = window()
        assertFalse(w.isOver(15 * second))
        assertTrue(w.isOver(15 * second + 1))
    }

    @Test
    fun `a later detection extends it`() {
        val w = window()
        w.extend(12 * second)
        assertEquals(22 * second, w.endAtUs)
    }

    @Test
    fun `an earlier detection never shortens it`() {
        val w = window()
        w.extend(1 * second)
        assertEquals(15 * second, w.endAtUs)
    }

    @Test
    fun `never runs past the maximum length`() {
        val w = window()
        w.extend(200 * second)
        assertEquals(60 * second, w.endAtUs)
    }

    @Test
    fun `knows when the cap cut it short`() {
        val w = window()
        assertFalse(w.cutShort)
        w.extend(200 * second)
        assertTrue(w.cutShort)
        assertEquals(210 * second, w.requestedEndUs)
    }
}
