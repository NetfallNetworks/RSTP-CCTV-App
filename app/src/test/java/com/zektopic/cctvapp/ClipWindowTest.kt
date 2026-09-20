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
        assertEquals(210 * second, w.detectionEndUs)
    }

    @Test
    fun `a hold keeps the clip going past the detection end`() {
        val w = window()                 // detection end 15 s
        w.hold(40 * second)
        assertFalse(w.isOver(39 * second))
        assertTrue(w.isOver(40 * second + 1))
    }

    @Test
    fun `detections carry a clip past an expired hold`() {
        val w = window()
        w.hold(20 * second)
        w.extend(25 * second)            // detection end 35 s
        assertEquals(35 * second, w.endAtUs)
    }

    @Test
    fun `a hold past the cap cuts the file short for rollover`() {
        val w = window()
        w.hold(100 * second)             // cap is 60 s
        assertEquals(60 * second, w.endAtUs)
        assertTrue(w.cutShort)
    }

    @Test
    fun `a hold never shortens`() {
        val w = window()
        w.hold(40 * second)
        w.hold(20 * second)
        assertEquals(40 * second, w.holdUntilUs)
    }
}
