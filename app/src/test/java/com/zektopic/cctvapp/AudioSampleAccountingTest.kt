package com.zektopic.cctvapp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression coverage for the invariant a duration-only comparison cannot see: whether the
 * number of AAC frames a clip actually contains matches how long it really ran. See
 * [AudioSampleAccounting]'s class doc for why channel count never enters this arithmetic.
 */
class AudioSampleAccountingTest {

    private val second = 1_000_000L

    // --- expectedFrameCount ---

    @Test
    fun `one second at 44100hz needs 44 frames`() {
        // 44100 / 1024 = 43.07, rounded up: a clip can't have a fractional trailing frame.
        assertEquals(44L, AudioSampleAccounting.expectedFrameCount(durationUs = second, sampleRateHz = 44100))
    }

    @Test
    fun `zero duration needs zero frames`() {
        assertEquals(0L, AudioSampleAccounting.expectedFrameCount(durationUs = 0, sampleRateHz = 44100))
    }

    // expectedFrameCount() takes no channel-count parameter at all -- see the class doc for
    // why mono and stereo audio at the same sample rate and duration need the same frame
    // count (samplesPerFrame and sampleRateHz are both already expressed per channel).
    // That's what makes this the right axis to check the bug on.

    // --- frameCountConsistent ---

    @Test
    fun `matching frame count is consistent`() {
        assertTrue(AudioSampleAccounting.frameCountConsistent(durationUs = second, sampleRateHz = 44100, observedFrameCount = 44))
    }

    @Test
    fun `half the expected frame count is not consistent`() {
        // The exact shape of the shipped bug: samples captured mono, muxed as if stereo,
        // so every other mono sample got folded into an L/R pair instead of its own frame.
        assertFalse(AudioSampleAccounting.frameCountConsistent(durationUs = second, sampleRateHz = 44100, observedFrameCount = 22))
    }

    @Test
    fun `double the expected frame count is not consistent`() {
        assertFalse(AudioSampleAccounting.frameCountConsistent(durationUs = second, sampleRateHz = 44100, observedFrameCount = 88))
    }

    @Test
    fun `a partial trailing frame is within tolerance`() {
        // 10.5s of real audio: the muxer's last frame doesn't divide evenly, but this is
        // not the failure this check exists for.
        val durationUs = 10 * second + 500_000L
        val expected = AudioSampleAccounting.expectedFrameCount(durationUs, 44100)
        assertTrue(AudioSampleAccounting.frameCountConsistent(durationUs, 44100, observedFrameCount = expected))
    }

    @Test
    fun `the shipped bug's real clip numbers fail the invariant`() {
        // 2026-09-21T07-37-47_..._175432a5.mp4: container duration_ts 26,473,405 at
        // time_base 1/44100 (600.30 s by the timestamps we wrote), but only 12,865 AAC
        // frames were actually encoded -- 298.7 s of real audio muxed under a 600.3 s
        // timeline. A pure duration comparison (audio track duration vs video track
        // duration) matched to 0.2 s and could never have caught this, because both
        // durations come from the same wall-clock pts this app writes, not from a sample
        // count. This is the check that would have.
        val durationTs = 26_473_405L
        val timeBaseHz = 44_100
        val durationUs = durationTs * 1_000_000L / timeBaseHz
        val observedFrames = 12_865L

        assertFalse(
            AudioSampleAccounting.frameCountConsistent(durationUs, timeBaseHz, observedFrames)
        )

        // The expected count is ~2x what was actually encoded -- the same ratio (2.01x)
        // the real clip's metadata showed.
        val expected = AudioSampleAccounting.expectedFrameCount(durationUs, timeBaseHz)
        val ratio = expected.toDouble() / observedFrames.toDouble()
        assertTrue("expected ratio near 2.0x, was $ratio", ratio in 1.95..2.05)
    }
}
