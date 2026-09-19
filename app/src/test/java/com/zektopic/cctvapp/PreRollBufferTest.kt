package com.zektopic.cctvapp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PreRollBufferTest {

    private val second = 1_000_000L

    /** 10 fps, a keyframe every [gopFrames] frames, [bytesPerFrame] each. */
    private fun feed(
        buffer: PreRollBuffer,
        frames: Int,
        gopFrames: Int = 20,
        bytesPerFrame: Int = 100,
        startFrame: Int = 0
    ) {
        for (i in startFrame until startFrame + frames) {
            buffer.add(PreRollBuffer.Frame(ByteArray(bytesPerFrame), i * second / 10, i % gopFrames == 0))
        }
    }

    @Test
    fun `nothing is available before the first keyframe`() {
        val buffer = PreRollBuffer(preRollUs = 5 * second, maxBytes = Long.MAX_VALUE)
        feed(buffer, frames = 5, startFrame = 1)
        assertTrue(buffer.framesFromKeyFrame().isEmpty())
        assertEquals(0, buffer.size)
    }

    @Test
    fun `a clip always opens on a keyframe`() {
        val buffer = PreRollBuffer(preRollUs = 5 * second, maxBytes = Long.MAX_VALUE)
        feed(buffer, frames = 137)
        assertTrue(buffer.framesFromKeyFrame().first().isKeyFrame)
    }

    @Test
    fun `keeps at least the pre-roll and at most one extra keyframe interval`() {
        val buffer = PreRollBuffer(preRollUs = 5 * second, maxBytes = Long.MAX_VALUE)
        // 2 s GOPs; check at every frame of a long run.
        for (i in 0 until 400) {
            feed(buffer, frames = 1, startFrame = i)
            val frames = buffer.framesFromKeyFrame()
            val spanUs = frames.last().ptsUs - frames.first().ptsUs
            if (i * second / 10 >= 5 * second) {
                assertTrue("span $spanUs at frame $i", spanUs >= 5 * second)
            }
            assertTrue("span $spanUs at frame $i", spanUs < 5 * second + 2 * second)
        }
    }

    @Test
    fun `reading does not consume`() {
        val buffer = PreRollBuffer(preRollUs = 5 * second, maxBytes = Long.MAX_VALUE)
        feed(buffer, frames = 80)
        val first = buffer.framesFromKeyFrame()
        assertEquals(first.size, buffer.framesFromKeyFrame().size)
    }

    @Test
    fun `the byte ceiling drops whole GOPs early`() {
        val buffer = PreRollBuffer(preRollUs = 60 * second, maxBytes = 5_000)
        feed(buffer, frames = 200, bytesPerFrame = 100)
        assertTrue(buffer.byteCount <= 5_000)
        assertTrue(buffer.framesFromKeyFrame().first().isKeyFrame)
    }

    @Test
    fun `a GOP that never ends is dropped rather than growing without bound`() {
        val buffer = PreRollBuffer(preRollUs = 5 * second, maxBytes = 5_000)
        feed(buffer, frames = 200, gopFrames = 1_000, bytesPerFrame = 100)
        assertTrue(buffer.byteCount <= 5_000)
    }
}
