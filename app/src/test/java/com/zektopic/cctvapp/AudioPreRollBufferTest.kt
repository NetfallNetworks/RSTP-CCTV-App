package com.zektopic.cctvapp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioPreRollBufferTest {

    private val second = 1_000_000L

    /** One frame every 20 ms (50/s, a plausible AAC frame rate), [bytesPerFrame] each. */
    private fun feed(
        buffer: AudioPreRollBuffer,
        frames: Int,
        bytesPerFrame: Int = 100,
        startFrame: Int = 0,
        stepUs: Long = 20_000L
    ) {
        for (i in startFrame until startFrame + frames) {
            buffer.add(AudioPreRollBuffer.Frame(ByteArray(bytesPerFrame), i * stepUs))
        }
    }

    @Test
    fun `empty buffer has nothing`() {
        val buffer = AudioPreRollBuffer(preRollUs = 5 * second, maxBytes = Long.MAX_VALUE)
        assertTrue(buffer.framesFrom(0).isEmpty())
        assertEquals(0, buffer.size)
        assertEquals(null, buffer.newestPtsUs)
    }

    @Test
    fun `keeps roughly the pre-roll window`() {
        val buffer = AudioPreRollBuffer(preRollUs = 5 * second, maxBytes = Long.MAX_VALUE)
        // 10 s of frames at 50/s.
        feed(buffer, frames = 500)
        val frames = buffer.framesFrom(0)
        val spanUs = frames.last().ptsUs - frames.first().ptsUs
        assertTrue("span $spanUs should be close to the 5 s window", spanUs in (5 * second - 20_000)..(5 * second))
    }

    @Test
    fun `reading does not consume`() {
        val buffer = AudioPreRollBuffer(preRollUs = 5 * second, maxBytes = Long.MAX_VALUE)
        feed(buffer, frames = 80)
        val first = buffer.framesFrom(0)
        assertEquals(first.size, buffer.framesFrom(0).size)
    }

    @Test
    fun `framesFrom drops anything before the floor`() {
        val buffer = AudioPreRollBuffer(preRollUs = 60 * second, maxBytes = Long.MAX_VALUE)
        feed(buffer, frames = 100, stepUs = 100_000L) // 100 ms apart, 10 s total
        val floor = 5 * second
        val kept = buffer.framesFrom(floor)
        assertTrue(kept.isNotEmpty())
        assertTrue(kept.all { it.ptsUs >= floor })
        // Nothing earlier than the floor slipped through.
        assertEquals(kept.size, buffer.framesFrom(0).count { it.ptsUs >= floor })
    }

    @Test
    fun `framesFrom past the newest sample is empty`() {
        val buffer = AudioPreRollBuffer(preRollUs = 5 * second, maxBytes = Long.MAX_VALUE)
        feed(buffer, frames = 10, stepUs = 20_000L)
        assertTrue(buffer.framesFrom(100 * second).isEmpty())
    }

    @Test
    fun `the byte ceiling trims regardless of time window`() {
        val buffer = AudioPreRollBuffer(preRollUs = 600 * second, maxBytes = 5_000)
        feed(buffer, frames = 200, bytesPerFrame = 100)
        assertTrue(buffer.byteCount <= 5_000)
    }

    @Test
    fun `clear empties the buffer`() {
        val buffer = AudioPreRollBuffer(preRollUs = 5 * second, maxBytes = Long.MAX_VALUE)
        feed(buffer, frames = 10)
        buffer.clear()
        assertEquals(0, buffer.size)
        assertEquals(0L, buffer.byteCount)
        assertTrue(buffer.framesFrom(0).isEmpty())
    }
}
