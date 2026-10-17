package com.zektopic.cctvapp

import android.media.MediaFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer

/**
 * ClipRecorder's bookkeeping, driven with a fake muxer: what a lost clip reports, how a long
 * visit is cut into parts, and which tracks a clip is opened with. The fake fails the way the
 * real MediaMuxer does: stop() throws when any added track never received a sample.
 */
class ClipRecorderTest {


    private val frameUs = 33_333L
    private val gopFrames = 30
    private val audioFrameUs = 21_333L
    private val baseUs = 1_000_000L

    private class Rig(
        val recorder: ClipRecorder,
        val muxers: MutableList<FakeMuxer>,
        val finished: MutableList<ClipRecorder.FinishedClip>,
        val failures: MutableList<ClipFailure>
    )

    private var nextVideoFrame = 0
    private var nextAudioUs = 0L
    private var audioOn = false
    private var targets = 0
    private var declineRollover = false

    private fun rig(
        audio: Boolean = false,
        preRollUs: Long = ClipRecorder.DEFAULT_PRE_ROLL_US,
        maxClipUs: Long = ClipRecorder.DEFAULT_MAX_CLIP_US,
        configure: (FakeMuxer) -> Unit = {}
    ): Rig {
        val muxers = mutableListOf<FakeMuxer>()
        val finished = mutableListOf<ClipRecorder.FinishedClip>()
        val failures = mutableListOf<ClipFailure>()
        val recorder = ClipRecorder(
            preRollUs = preRollUs,
            maxClipUs = maxClipUs,
            onClipFinished = { finished.add(it) },
            onRollover = { if (declineRollover) null else newTarget() },
            onClipFailed = { failures.add(it) },
            muxerFactory = { FakeMuxer().also { m -> configure(m); muxers.add(m) } }
        )
        nextVideoFrame = 0
        nextAudioUs = 0
        audioOn = audio
        recorder.setVideoFormat(MediaFormat())
        if (audio) recorder.setAudioFormat(MediaFormat())
        return Rig(recorder, muxers, finished, failures)
    }

    private fun newTarget(): ClipRecorder.ClipTarget {
        val n = targets++
        return ClipRecorder.ClipTarget("event-$n", File("event-$n.mp4"))
    }

    private fun frameBytes(ptsUs: Long): ByteArray = ByteBuffer.allocate(8).putLong(ptsUs).array()

    private fun feedVideo(r: Rig) {
        val pts = baseUs + nextVideoFrame * frameUs
        // Audio first up to this video frame, as the two encoders interleave in practice.
        while (audioOn && baseUs + nextAudioUs <= pts) {
            r.recorder.onAudioFrame(AudioPreRollBuffer.Frame(frameBytes(baseUs + nextAudioUs), baseUs + nextAudioUs))
            nextAudioUs += audioFrameUs
        }
        r.recorder.onVideoFrame(PreRollBuffer.Frame(frameBytes(pts), pts, nextVideoFrame % gopFrames == 0))
        nextVideoFrame++
    }

    /** Feeds [seconds] of video (and audio, when on). */
    private fun advance(r: Rig, seconds: Double) {
        repeat((seconds * 1_000_000 / frameUs).toInt()) { feedVideo(r) }
    }

    private fun trigger(r: Rig): ClipRecorder.Trigger = r.recorder.trigger { newTarget() }

    // --- step 1: a lost clip explains itself ---

    @Test
    fun `a finalise failure reports why, how long it ran and what was written`() {
        val r = rig(audio = true)
        advance(r, 6.0)
        assertEquals(ClipRecorder.Outcome.STARTED, trigger(r).outcome)
        advance(r, 3.0)
        val muxer = r.muxers.single()
        muxer.failStop = IllegalStateException("Failed to stop the muxer")

        assertFalse(r.recorder.stop())

        val f = r.failures.single()
        assertEquals("event-0", f.eventId)
        assertEquals(ClipFailureReason.FINALISE_FAILED, f.reason)
        assertEquals("IllegalStateException: Failed to stop the muxer", f.message)
        assertTrue(f.hadAudioTrack)
        assertEquals(muxer.video().size, f.videoSamples)
        assertEquals(muxer.audio().size, f.audioSamples)
        assertTrue(f.audioSamples > 0)
        assertEquals(muxer.samples.maxOf { it.relPtsUs } / 1000, f.recordedMs)
        assertTrue("a few seconds were recorded", f.recordedMs > 5_000)
        assertTrue(f.atMs > 0)
    }

    @Test
    fun `a clip that cannot be opened reports START_FAILED once`() {
        val r = rig { it.failStart = IOException("open failed") }
        advance(r, 3.0)

        assertEquals(ClipRecorder.Outcome.UNAVAILABLE, trigger(r).outcome)

        val f = r.failures.single()
        assertEquals(ClipFailureReason.START_FAILED, f.reason)
        assertEquals("IOException: open failed", f.message)
        assertEquals(0L, f.recordedMs)
        assertFalse(f.hadAudioTrack)
        assertEquals(0, f.videoSamples)
        assertNull(r.recorder.activeEventId)
    }

    @Test
    fun `a write failure that also stops the file from finalising reports WRITE_FAILED`() {
        val r = rig()
        advance(r, 6.0)
        trigger(r)
        val muxer = r.muxers.single()
        muxer.failWriteAt = muxer.samples.size + 5
        muxer.failStop = IllegalStateException("Failed to stop the muxer")

        advance(r, 1.0)

        val f = r.failures.single()
        assertEquals(ClipFailureReason.WRITE_FAILED, f.reason)
        assertEquals("IOException: No space left on device", f.message)
        assertTrue(r.finished.isEmpty())
        assertNull(r.recorder.activeEventId)
    }

    @Test
    fun `a write failure whose file still finalises keeps what was recorded`() {
        val r = rig()
        advance(r, 6.0)
        trigger(r)
        val muxer = r.muxers.single()
        muxer.failWriteAt = muxer.samples.size + 5

        advance(r, 1.0)

        assertEquals("the partial clip is kept", 1, r.finished.size)
        assertTrue(r.failures.isEmpty())
        assertEquals(0L, r.recorder.stats().clipFailures)
    }

    @Test
    fun `a file that cannot be finalised at the cap still continues the recording in a new part`() {
        var opened = 0
        val r = rig(maxClipUs = 20_000_000L) { m ->
            if (opened++ == 0) m.failStop = IllegalStateException("Failed to stop the muxer")
        }
        advance(r, 6.0)
        trigger(r)
        var seconds = 0
        while (r.muxers.size < 2 && seconds++ < 60) { advance(r, 1.0); trigger(r) }

        val lost = r.failures.single()
        assertEquals("event-0", lost.eventId)
        assertEquals(ClipFailureReason.FINALISE_FAILED, lost.reason)
        assertTrue("the recording goes on after this part", lost.continued)
        assertEquals("a continuation part is open", "event-1", r.recorder.activeEventId)
        assertEquals(2, r.muxers.size)
        assertEquals(1L, r.recorder.stats().clipFailures)
    }

    @Test
    fun `when the continuation of a lost part is declined the recording is reported ended once more`() {
        var opened = 0
        val r = rig(maxClipUs = 20_000_000L) { m ->
            if (opened++ == 0) m.failStop = IllegalStateException("Failed to stop the muxer")
        }
        advance(r, 6.0)
        trigger(r)
        declineRollover = true
        try {
            var seconds = 0
            while (r.failures.size < 2 && seconds++ < 60) { advance(r, 1.0); trigger(r) }
        } finally {
            declineRollover = false
        }

        assertEquals(2, r.failures.size)
        assertTrue(r.failures[0].continued)
        assertEquals(ClipFailureReason.ROLLOVER_DECLINED, r.failures[1].reason)
        assertEquals("event-0", r.failures[1].eventId)
        assertFalse("this one ends the recording", r.failures[1].continued)
        assertEquals("the same clip is not counted twice", 1L, r.recorder.stats().clipFailures)
    }

    @Test
    fun `a declined rollover reports ROLLOVER_DECLINED against the part that was kept`() {
        val r = rig(audio = true, maxClipUs = 20_000_000L)
        advance(r, 6.0)
        trigger(r)
        declineRollover = true
        try {
            // Keep the visit alive until the cap cuts it; stop there, before a fresh clip opens.
            var seconds = 0
            while (r.failures.isEmpty() && seconds++ < 60) { advance(r, 1.0); trigger(r) }
        } finally {
            declineRollover = false
        }

        assertEquals(1, r.finished.size)
        assertTrue(r.finished[0].continued)
        val f = r.failures.single()
        assertEquals("event-0", f.eventId)
        assertEquals(ClipFailureReason.ROLLOVER_DECLINED, f.reason)
        assertTrue(f.hadAudioTrack)
        assertEquals(r.finished[0].durationUs / 1000, f.recordedMs)
        assertEquals(r.muxers[0].video().size, f.videoSamples)
        assertEquals(r.muxers[0].audio().size, f.audioSamples)
    }

    @Test
    fun `a rollover that cannot be started reports START_FAILED for the continuation`() {
        var opened = 0
        val r = rig(maxClipUs = 20_000_000L) { m -> if (opened++ == 1) m.failStart = IOException("open failed") }
        advance(r, 6.0)
        trigger(r)
        repeat(30) { advance(r, 1.0); trigger(r) }

        val f = r.failures.single()
        assertEquals("event-1", f.eventId)
        assertEquals(ClipFailureReason.START_FAILED, f.reason)
    }

    @Test
    fun `counters and the last failure are kept for status`() {
        val r = rig()
        advance(r, 6.0)
        trigger(r)
        advance(r, 2.0)
        assertTrue(r.recorder.stop())
        trigger(r)
        r.muxers[1].failStop = IllegalStateException("Failed to stop the muxer")
        r.recorder.stop()
        trigger(r)
        r.recorder.discard()

        val stats = r.recorder.stats()
        assertEquals(3L, stats.clipsStarted)
        assertEquals(1L, stats.clipsFinalised)
        assertEquals(1L, stats.clipFailures)
        assertEquals("event-1", stats.lastFailure?.eventId)
        assertEquals(ClipFailureReason.FINALISE_FAILED, stats.lastFailure?.reason)
    }

    @Test
    fun `starting a clip records how long it held the lock`() {
        val r = rig()
        advance(r, 3.0)
        r.recorder.trigger { Thread.sleep(25); newTarget() }
        assertTrue(r.recorder.stats().maxStartHoldMs >= 25)
    }

    // --- no audio track unless it is given a sample ---

    @Test
    fun `audio that is fresh but older than the pre-roll start does not add an empty track`() {
        val r = rig(audio = true)
        // Audio exists up to one second before video starts, e.g. right after a stream restart.
        val audioPts = baseUs - 1_000_000L
        r.recorder.onAudioFrame(AudioPreRollBuffer.Frame(frameBytes(audioPts), audioPts))
        audioOn = false
        advance(r, 1.0)

        assertEquals(ClipRecorder.Outcome.STARTED, trigger(r).outcome)
        advance(r, 2.0)

        assertEquals("video only", 1, r.muxers.single().tracks.size)
        assertTrue("the clip finalises", r.recorder.stop())
        assertTrue(r.failures.isEmpty())
        assertEquals(1, r.finished.size)
    }

    // --- more pre-roll, same audio freshness bound ---

    @Test
    fun `pre-roll is ten seconds and audio freshness stays five`() {
        assertEquals(10_000_000L, ClipRecorder.DEFAULT_PRE_ROLL_US)
        assertEquals(5_000_000L, ClipRecorder.AUDIO_WAIT_TIMEOUT_US)
    }

    @Test
    fun `a clip opens with at least ten seconds before the trigger`() {
        val r = rig()
        advance(r, 40.0)
        val triggerUs = baseUs + (nextVideoFrame - 1) * frameUs

        trigger(r)

        val firstUs = r.muxers.single().video().first().absPtsUs
        assertTrue("pre-roll was ${(triggerUs - firstUs) / 1000} ms", triggerUs - firstUs >= 10_000_000L)
        assertTrue(r.muxers.single().video().first().key)
    }

    @Test
    fun `audio that went quiet six seconds ago is not trusted for a new clip`() {
        val r = rig(audio = true)
        advance(r, 10.0)
        audioOn = false
        advance(r, 6.0)

        assertEquals(ClipRecorder.Outcome.STARTED, trigger(r).outcome)
        advance(r, 1.0)

        assertEquals("video only", 1, r.muxers.single().tracks.size)
        assertNotNull(r.recorder.activeEventId)
    }
}
