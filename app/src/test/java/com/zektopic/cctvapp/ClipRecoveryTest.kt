package com.zektopic.cctvapp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The rebuild ladder and the verification rule, with a fake muxer and a fake verifier. The real
 * MediaMuxer / MediaExtractor halves are in ClipMedia.kt and are not covered here.
 */
class ClipRecoveryTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val video = JournalVideoInfo("video/avc", 1600, 1200, 30, 1_300_000, byteArrayOf(1), byteArrayOf(2))
    private val audio = JournalAudioInfo("audio/mp4a-latm", 48_000, 1, byteArrayOf(3))

    private fun journal() = File(tempFolder.root, "e1_clip.journal")
    private fun target() = File(tempFolder.root, "e1_clip.mp4")
    private fun scratch() = File(tempFolder.root, "e1_clip.mp4.rebuild")

    /** A 3 s journal: video every 100 ms from 0, audio every 100 ms from 50 ms. */
    private fun writeJournal(withAudioHeader: Boolean = true, audioSamples: Boolean = true, videoSamples: Boolean = true) {
        val w = ClipJournalWriter(journal(), JournalHeader(1234L, video, if (withAudioHeader) audio else null))
        for (i in 0 until 30) {
            if (videoSamples) w.append(true, i * 100_000L, i % 10 == 0, ByteArray(4) { i.toByte() })
            if (audioSamples) w.append(false, i * 100_000L + 50_000L, false, ByteArray(2))
        }
        w.close()
    }

    private class Rig(val recovery: ClipRecovery, val muxers: MutableList<FakeMuxer>, val verifier: FakeVerifier)

    private fun rig(
        verifier: FakeVerifier = FakeVerifier(),
        rename: (File, File) -> Boolean = { a, b -> a.renameTo(b) },
        configure: (Int, FakeMuxer) -> Unit = { _, _ -> }
    ): Rig {
        val muxers = mutableListOf<FakeMuxer>()
        val replay = JournalReplay({ f -> FakeMuxer(f).also { configure(muxers.size, it); muxers.add(it) } }, FakeFormats())
        return Rig(ClipRecovery(replay, verifier, rename), muxers, verifier)
    }

    // --- the verification rule ---

    @Test
    fun `verification accepts a clip with the right tracks, samples and duration`() {
        assertNull(ClipVerification.check(ClipExpectation(2, 60_000_000), ClipProbe(2, 2, 60_500_000)))
    }

    @Test
    fun `verification rejects a wrong track count or a track with no samples`() {
        assertEquals(
            "expected 2 tracks, found 1",
            ClipVerification.check(ClipExpectation(2, 60_000_000), ClipProbe(1, 1, 60_000_000))
        )
        assertEquals(
            "only 1 of 2 tracks yield samples",
            ClipVerification.check(ClipExpectation(2, 60_000_000), ClipProbe(2, 1, 60_000_000))
        )
    }

    @Test
    fun `verification rejects a duration far from the journal's, either way`() {
        val expect = ClipExpectation(1, 100_000_000) // tolerance 10 s
        assertNull(ClipVerification.check(expect, ClipProbe(1, 1, 109_000_000)))
        assertNull(ClipVerification.check(expect, ClipProbe(1, 1, 91_000_000)))
        assertNotNull(ClipVerification.check(expect, ClipProbe(1, 1, 80_000_000)))
        assertNotNull(ClipVerification.check(expect, ClipProbe(1, 1, 120_000_000)))
        assertEquals("no duration", ClipVerification.check(expect, ClipProbe(1, 1, null)))
    }

    @Test
    fun `short clips get a three second tolerance and very short ones are not duration-checked`() {
        assertEquals(3_000_000L, ClipVerification.toleranceUs(5_000_000))
        assertEquals(10_000_000L, ClipVerification.toleranceUs(100_000_000))
        assertNull(ClipVerification.check(ClipExpectation(1, 5_000_000), ClipProbe(1, 1, 7_900_000)))
        assertNotNull(ClipVerification.check(ClipExpectation(1, 5_000_000), ClipProbe(1, 1, 8_100_000)))
        assertNull(ClipVerification.check(ClipExpectation(1, 400_000), ClipProbe(1, 1, null)))
    }

    @Test
    fun `the tail check accepts any sample near the end, not only an exact seek hit`() {
        assertTrue(ClipVerification.tailReached(57_500_000, 60_000_000))
        assertTrue(ClipVerification.tailReached(60_000_000, 60_000_000))
        assertFalse("nothing read at all", ClipVerification.tailReached(-1, 60_000_000))
        assertFalse("the index stops half way", ClipVerification.tailReached(30_000_000, 60_000_000))
        assertTrue("unknown duration only needs a sample", ClipVerification.tailReached(0, null))
    }

    // --- the ladder ---

    @Test
    fun `a journal is rebuilt with every track, verified, and only then deleted`() {
        writeJournal()
        val r = rig()

        val result = r.recovery.recover(journal(), target())

        assertEquals(ClipRecovery.Outcome.REBUILT, result.outcome)
        assertTrue(result.hadAudioTrack)
        assertEquals(30, result.videoSamples)
        assertEquals(30, result.audioSamples)
        val m = r.muxers.single()
        assertEquals("video then audio track", 2, m.tracks.size)
        assertTrue(m.stopped)
        assertEquals((0 until 30).map { it * 100_000L }, m.video().map { it.relPtsUs })
        assertEquals("keyframes keep their flag", listOf(0, 10, 20), m.video().withIndex().filter { it.value.key }.map { it.index })
        assertEquals(30, m.audio().size)
        assertEquals("verified against the journal's own span", 2, r.verifier.calls.single().tracks)
        assertEquals(2_950_000L, r.verifier.calls.single().spanUs)
        assertTrue("the rebuilt file is in place", target().exists())
        assertFalse(scratch().exists())
        assertFalse("verified, so the journal goes", journal().exists())
    }

    @Test
    fun `the replay keeps the order and drops a sample that goes backwards in time`() {
        val w = ClipJournalWriter(journal(), JournalHeader(1L, video, null))
        w.append(true, 0, true, ByteArray(1))
        w.append(true, 100, false, ByteArray(1))
        w.append(true, 100, false, ByteArray(1))
        w.append(true, 50, false, ByteArray(1))
        w.append(true, 200, false, ByteArray(1))
        w.close()
        val r = rig()

        val result = r.recovery.recover(journal(), target())

        assertEquals(listOf(0L, 100L, 200L), r.muxers.single().video().map { it.relPtsUs })
        assertEquals(3, result.videoSamples)
    }

    @Test
    fun `if the full rebuild cannot be finalised the second step is video only`() {
        writeJournal()
        val r = rig(configure = { index, m -> if (index == 0) m.failStop = IllegalStateException("Failed to stop the muxer") })

        val result = r.recovery.recover(journal(), target())

        assertEquals(ClipRecovery.Outcome.REBUILT_VIDEO_ONLY, result.outcome)
        assertFalse(result.hadAudioTrack)
        assertEquals(0, result.audioSamples)
        assertEquals(30, result.videoSamples)
        assertEquals(2, r.muxers.size)
        assertEquals(1, r.muxers[1].tracks.size)
        assertTrue(result.error!!.contains("with audio"))
        assertEquals("the second verification expects one track", 1, r.verifier.calls.single().tracks)
        assertEquals("and the video span only", 2_900_000L, r.verifier.calls.single().spanUs)
        assertFalse(journal().exists())
        assertTrue(target().exists())
    }

    @Test
    fun `a rebuild that fails verification is discarded and the next step tried`() {
        writeJournal()
        val r = rig(FakeVerifier("duration 1 ms, journal covers 2950 ms", null))

        val result = r.recovery.recover(journal(), target())

        assertEquals(ClipRecovery.Outcome.REBUILT_VIDEO_ONLY, result.outcome)
        assertEquals(listOf(2, 1), r.verifier.calls.map { it.tracks })
        assertTrue(result.error!!.contains("failed verification"))
        assertFalse(journal().exists())
    }

    @Test
    fun `when every step fails only the journal is left`() {
        writeJournal()
        target().writeText("the broken original")
        val r = rig(FakeVerifier("bad", "still bad"))

        val result = r.recovery.recover(journal(), target())

        assertEquals(ClipRecovery.Outcome.FAILED, result.outcome)
        assertFalse(result.rebuilt)
        assertTrue("the journal is the raw data and stays", journal().exists())
        assertFalse(scratch().exists())
        assertEquals("the original is not replaced by an unverified rebuild", "the broken original", target().readText())
        assertEquals(2, r.muxers.size)
        assertNotNull(result.scan)
    }

    @Test
    fun `a verified rebuild that cannot be moved into place is kept, nothing is deleted, and it is reported`() {
        writeJournal()
        target().writeText("the broken original")
        val r = rig(rename = { _, _ -> false })

        val result = r.recovery.recover(journal(), target())

        assertEquals(ClipRecovery.Outcome.FAILED, result.outcome)
        assertTrue(result.error!!.contains("could not be moved into place"))
        assertEquals("the target is untouched", "the broken original", target().readText())
        assertTrue("the verified rebuild is kept", scratch().exists())
        assertTrue("and so is the journal", journal().exists())
        assertEquals("no further rung is tried", 1, r.muxers.size)
    }

    @Test
    fun `a target that blocks the rename is moved aside, replaced, and restored if that fails`() {
        // A filesystem that will not rename over an existing file (as Windows does not).
        val noOverwrite = { from: File, to: File -> if (to.exists()) false else from.renameTo(to) }
        writeJournal()
        target().writeText("the broken original")
        val ok = rig(rename = noOverwrite)

        assertEquals(ClipRecovery.Outcome.REBUILT, ok.recovery.recover(journal(), target()).outcome)
        assertEquals("mp4", target().readText())
        assertFalse(File(tempFolder.root, "e1_clip.mp4.orig").exists())

        writeJournal()
        target().writeText("the broken original")
        var calls = 0
        // The first rename (temp over target) and the one into place after the backup both fail.
        val failsIntoPlace = { from: File, to: File ->
            calls++
            if (from.name.endsWith(".rebuild")) false else from.renameTo(to)
        }
        val bad = rig(rename = failsIntoPlace)

        assertEquals(ClipRecovery.Outcome.FAILED, bad.recovery.recover(journal(), target()).outcome)
        assertEquals("the original is back", "the broken original", target().readText())
        assertTrue(journal().exists())
    }

    @Test
    fun `a muxer that fails at every step leaves the journal and no scratch file`() {
        writeJournal()
        val r = rig(configure = { _, m -> m.failStart = java.io.IOException("open failed") })

        val result = r.recovery.recover(journal(), target())

        assertEquals(ClipRecovery.Outcome.FAILED, result.outcome)
        assertTrue(result.error!!.contains("open failed"))
        assertTrue(journal().exists())
        assertFalse(scratch().exists())
        assertFalse(target().exists())
    }

    @Test
    fun `a journal with an audio header but no audio samples is rebuilt in one video-only step`() {
        writeJournal(audioSamples = false)
        val r = rig()

        val result = r.recovery.recover(journal(), target())

        assertEquals(ClipRecovery.Outcome.REBUILT_VIDEO_ONLY, result.outcome)
        assertEquals(1, r.muxers.size)
        assertEquals("no empty audio track is added", 1, r.muxers[0].tracks.size)
        assertEquals(1, r.verifier.calls.size)
    }

    @Test
    fun `a journal without an audio track is rebuilt video only`() {
        writeJournal(withAudioHeader = false, audioSamples = false)
        val r = rig()

        assertEquals(ClipRecovery.Outcome.REBUILT_VIDEO_ONLY, r.recovery.recover(journal(), target()).outcome)
        assertEquals(1, r.muxers.single().tracks.size)
    }

    @Test
    fun `a header-only journal cannot be rebuilt and is kept`() {
        writeJournal(audioSamples = false, videoSamples = false)
        val r = rig()

        val result = r.recovery.recover(journal(), target())

        assertEquals(ClipRecovery.Outcome.FAILED, result.outcome)
        assertEquals("journal holds no video", result.error)
        assertTrue(r.muxers.isEmpty())
        assertTrue(journal().exists())
    }

    @Test
    fun `an unreadable journal fails without touching anything`() {
        journal().writeBytes(ByteArray(50) { 9 })
        val r = rig()

        val result = r.recovery.recover(journal(), target())

        assertEquals(ClipRecovery.Outcome.FAILED, result.outcome)
        assertNull(result.scan)
        assertEquals("journal unreadable", result.error)
        assertTrue(journal().exists())
    }

    @Test
    fun `a torn tail still rebuilds everything before it`() {
        writeJournal(audioSamples = false)
        val full = journal().readBytes()
        journal().writeBytes(full.copyOf(full.size - 5))
        val r = rig()

        val result = r.recovery.recover(journal(), target())

        assertEquals(ClipRecovery.Outcome.REBUILT_VIDEO_ONLY, result.outcome)
        assertEquals(29, result.videoSamples)
    }
}
