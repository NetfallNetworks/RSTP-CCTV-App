package com.zektopic.cctvapp

import android.media.MediaFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.Executor

/**
 * ClipRecorder with its safety net on: every clip is journaled beside its MP4, the MP4 is
 * verified before the journal goes, and a clip that fails to finalise or verify is rebuilt from
 * the journal. Fake muxer, formats and verifier; rebuilds run inline here (on a background
 * thread in the app). The real MediaMuxer/MediaExtractor are not exercised.
 */
class ClipJournalRecorderTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val frameUs = 33_333L
    private val audioFrameUs = 21_333L
    private val baseUs = 1_000_000L

    private class Rig(
        val recorder: ClipRecorder,
        val muxers: MutableList<FakeMuxer>,
        val finished: MutableList<ClipRecorder.FinishedClip>,
        val failures: MutableList<ClipFailure>,
        val verifier: FakeVerifier
    )

    private var nextVideoFrame = 0
    private var nextAudioUs = 0L
    private var targets = 0
    private var dir: File = File("unset")
    private val noJournalNotes = mutableListOf<Pair<String, String>>()

    private fun rig(
        verifier: FakeVerifier = FakeVerifier(),
        targetDir: File = tempFolder.root,
        maxClipUs: Long = ClipRecorder.DEFAULT_MAX_CLIP_US,
        formats: FakeFormats = FakeFormats(),
        executor: Executor = Executor { it.run() },
        journalFactory: (File, JournalHeader) -> ClipJournalWriter = { f, h -> ClipJournalWriter(f, h) },
        configure: (Int, FakeMuxer) -> Unit = { _, _ -> }
    ): Rig {
        dir = targetDir
        nextVideoFrame = 0
        nextAudioUs = 0
        val muxers = mutableListOf<FakeMuxer>()
        val finished = mutableListOf<ClipRecorder.FinishedClip>()
        val failures = mutableListOf<ClipFailure>()
        val recorder = ClipRecorder(
            maxClipUs = maxClipUs,
            onClipFinished = { finished.add(it) },
            onRollover = { newTarget() },
            onClipFailed = { failures.add(it) },
            muxerFactory = { f -> FakeMuxer(f).also { configure(muxers.size, it); muxers.add(it) } },
            safety = ClipSafety(formats, verifier, journalFactory, executor),
            onNoJournal = { id, reason -> noJournalNotes.add(id to reason) }
        )
        noJournalNotes.clear()
        recorder.setVideoFormat(MediaFormat())
        recorder.setAudioFormat(MediaFormat())
        return Rig(recorder, muxers, finished, failures, verifier)
    }

    private fun newTarget(): ClipRecorder.ClipTarget {
        val n = targets++
        return ClipRecorder.ClipTarget("event-$n", File(dir, "event-${n}_clip.mp4"))
    }

    private fun frameBytes(ptsUs: Long): ByteArray = ByteBuffer.allocate(8).putLong(ptsUs).array()

    private fun advance(r: Rig, seconds: Double) {
        repeat((seconds * 1_000_000 / frameUs).toInt()) {
            val pts = baseUs + nextVideoFrame * frameUs
            while (baseUs + nextAudioUs <= pts) {
                r.recorder.onAudioFrame(AudioPreRollBuffer.Frame(frameBytes(baseUs + nextAudioUs), baseUs + nextAudioUs))
                nextAudioUs += audioFrameUs
            }
            r.recorder.onVideoFrame(PreRollBuffer.Frame(frameBytes(pts), pts, nextVideoFrame % 30 == 0))
            nextVideoFrame++
        }
    }

    /** Starts a clip with audio and records a few seconds of it. */
    private fun recordClip(r: Rig, seconds: Double = 4.0) {
        advance(r, 12.0)
        assertEquals(ClipRecorder.Outcome.STARTED, r.recorder.trigger { newTarget() }.outcome)
        advance(r, seconds)
    }

    private fun journals() = tempFolder.root.listFiles { f -> f.name.endsWith(".journal") }!!.toList()

    // --- the journal mirrors the clip ---

    @Test
    fun `every sample handed to the muxer is in the journal in the same order, and the journal goes once the MP4 verifies`() {
        var inJournal: JournalScan? = null
        var inJournalOrder: List<Pair<Boolean, Long>> = emptyList()
        val verifier = FakeVerifier()
        verifier.onVerify = { file, _ ->
            val journal = journalFileFor(file)
            inJournal = ClipJournalReader.scan(journal)
            inJournalOrder = ClipJournalReader.open(journal)!!.use { reader ->
                generateSequence { reader.next() }.map { it.isVideo to it.ptsUs }.toList()
            }
        }
        val r = rig(verifier)
        recordClip(r)

        assertTrue(r.recorder.stop())

        val m = r.muxers.single()
        val scan = inJournal!!
        assertEquals(m.video().size, scan.videoSamples)
        assertEquals(m.audio().size, scan.audioSamples)
        assertTrue("pre-roll audio and video were journaled", scan.audioSamples > 0 && scan.videoSamples > 100)
        assertEquals(
            "same order as the muxer saw them",
            m.samples.map { (it.track == 0) to it.relPtsUs },
            inJournalOrder
        )
        assertEquals(0L, scan.videoFirstPtsUs)
        val expectation = verifier.calls.single()
        assertEquals(2, expectation.tracks)
        assertEquals(m.samples.maxOf { it.relPtsUs } - m.samples.minOf { it.relPtsUs }, expectation.spanUs)
        assertEquals(
            "the floor for the file size is every payload byte given to the muxer, pre-roll included",
            m.samples.sumOf { it.data.size.toLong() }, expectation.minBytes
        )
        assertEquals(1, r.finished.size)
        assertNull(r.finished[0].recoveredFrom)
        assertTrue("verified, so the journal is deleted", journals().isEmpty())
        assertEquals(1L, r.recorder.stats().clipsFinalised)
    }

    // --- a clip that did not finalise, or did not verify, is rebuilt ---

    @Test
    fun `a clip whose MP4 fails verification is rebuilt from its journal and attached as recovered`() {
        val r = rig(FakeVerifier("duration 1 ms, journal covers 4000 ms", null))
        recordClip(r)

        assertFalse("the original finish reports nothing finished", r.recorder.stop())

        assertTrue(r.failures.isEmpty())
        val clip = r.finished.single()
        val cause = clip.recoveredFrom!!
        assertEquals(ClipFailureReason.FINALISE_FAILED, cause.reason)
        assertEquals("verification failed: duration 1 ms, journal covers 4000 ms", cause.message)
        assertFalse(clip.unverified)
        assertEquals("event-0", clip.eventId)
        assertTrue("both tracks came back", clip.hadAudioTrack && clip.audioSamples > 0)
        assertEquals(2, r.muxers.size)
        assertTrue(clip.file.exists())
        assertTrue("rebuilt and verified, so the journal goes", journals().isEmpty())
        val stats = r.recorder.stats()
        assertEquals(1L, stats.clipFailures)
        assertEquals(1L, stats.clipsRecovered)
        assertEquals(0L, stats.clipsFinalised)
    }

    @Test
    fun `an MP4 shorter than its payload bytes is rejected and rebuilt, on the live path and the rebuild path`() {
        val reported = ArrayDeque(listOf<Long>(-1, Long.MAX_VALUE)) // live file cut short, rebuilt one fine
        val seen = mutableListOf<ClipExpectation>()
        val verifier = FakeVerifier()
        verifier.onVerify = { _, expect -> seen.add(expect) }
        val sizeAware = object : ClipVerifier {
            override fun verify(file: File, expect: ClipExpectation): String? {
                verifier.verify(file, expect)
                val length = reported.removeFirst().let { if (it < 0) expect.minBytes - 1 else it }
                return ClipVerification.checkSize(expect, length)
            }
        }
        val r = rig(FakeVerifier())
        // Same rig, but with the size-aware verifier standing in.
        val recorder = ClipRecorder(
            onClipFinished = { r.finished.add(it) }, onRollover = { null },
            muxerFactory = { f -> FakeMuxer(f).also { r.muxers.add(it) } },
            safety = ClipSafety(FakeFormats(), sizeAware, executor = Executor { it.run() })
        )
        r.muxers.clear()
        r.finished.clear()
        recorder.setVideoFormat(MediaFormat())
        recorder.setAudioFormat(MediaFormat())
        val own = Rig(recorder, r.muxers, r.finished, r.failures, verifier)
        nextVideoFrame = 0
        nextAudioUs = 0
        recordClip(own)

        recorder.stop()

        assertEquals("live path then rebuild path", 2, seen.size)
        assertEquals(own.muxers[0].samples.sumOf { it.data.size.toLong() }, seen[0].minBytes)
        assertEquals(own.muxers[1].samples.sumOf { it.data.size.toLong() }, seen[1].minBytes)
        val clip = own.finished.single()
        assertTrue(clip.recoveredFrom!!.message!!.startsWith("verification failed: file is"))
    }

    @Test
    fun `a clip whose stop throws is rebuilt instead of lost`() {
        val r = rig { index, m -> if (index == 0) m.failStop = IllegalStateException("Failed to stop the muxer") }
        recordClip(r)
        val live = r.muxers.single()

        assertFalse(r.recorder.stop())

        val clip = r.finished.single()
        assertEquals(ClipFailureReason.FINALISE_FAILED, clip.recoveredFrom!!.reason)
        assertEquals("IllegalStateException: Failed to stop the muxer", clip.recoveredFrom!!.message)
        assertEquals("everything the live clip had", live.video().size, clip.videoSamples)
        assertEquals(live.audio().size, clip.audioSamples)
        assertTrue(r.failures.isEmpty())
        assertTrue(clip.file.exists())
        assertTrue(journals().isEmpty())
    }

    @Test
    fun `a write failure that also breaks stop is rebuilt, including the sample that failed to write`() {
        val r = rig { index, m ->
            if (index == 0) {
                m.failWriteAt = 1000 // past the ~800 pre-roll samples, so it fails mid-clip
                m.failStop = IllegalStateException("Failed to stop the muxer")
            }
        }
        recordClip(r, seconds = 6.0)

        val clip = r.finished.single()
        assertEquals(ClipFailureReason.WRITE_FAILED, clip.recoveredFrom!!.reason)
        assertEquals("IOException: No space left on device", clip.recoveredFrom!!.message)
        assertEquals(
            "the rebuild has the sample that failed to write too",
            r.muxers[0].samples.size + 1, r.muxers[1].samples.size
        )
    }

    @Test
    fun `when the rebuild fails too and the MP4 never finalised, only the journal is left and it is reported`() {
        val r = rig { index, m ->
            if (index == 0) m.failStop = IllegalStateException("Failed to stop the muxer")
            else m.failStart = IOException("open failed")
        }
        recordClip(r)

        assertFalse(r.recorder.stop())

        assertTrue(r.finished.isEmpty())
        val failure = r.failures.single()
        assertTrue(failure.journalKept)
        assertEquals(ClipFailureReason.FINALISE_FAILED, failure.reason)
        assertEquals("the raw data is on disk", 1, journals().size)
        assertEquals(1L, r.recorder.stats().clipFailures)
        assertEquals(0L, r.recorder.stats().clipsRecovered)
    }

    @Test
    fun `an MP4 that finalised but fails verification and a rebuild too is kept as a normal clip, not damaged`() {
        val r = rig(FakeVerifier("duration 1 ms, journal covers 4000 ms", "bad", "bad"))
        recordClip(r)

        assertFalse(r.recorder.stop())

        val clip = r.finished.single()
        assertTrue(clip.unverified)
        val note = clip.recoveredFrom!!
        assertEquals(ClipFailureReason.UNVERIFIED, note.reason)
        assertTrue(note.message!!.startsWith("verification failed: duration 1 ms"))
        assertTrue(note.message!!.contains("rebuild:"))
        assertTrue("the original MP4 is attached as it was", clip.file.exists())
        assertEquals("the original is not overwritten", "mp4", clip.file.readText())
        assertTrue("the journal goes, or the event could never be archived", journals().isEmpty())
        assertTrue(r.failures.isEmpty())
        val stats = r.recorder.stats()
        assertEquals(1L, stats.clipsUnverified)
        assertEquals("not a loss", 0L, stats.clipFailures)
        assertEquals(0L, stats.clipsRecovered)
        assertEquals(ClipFailureReason.UNVERIFIED, stats.lastFailure!!.reason)
    }

    @Test
    fun `a verification failure that the rebuild fixes is a recovery and counts as a failure once`() {
        val r = rig(FakeVerifier("bad", null))
        recordClip(r)

        r.recorder.stop()

        assertEquals(1L, r.recorder.stats().clipFailures)
        assertEquals(1L, r.recorder.stats().clipsRecovered)
        assertEquals(0L, r.recorder.stats().clipsUnverified)
    }
    // --- the safety net must never cost the clip, and must not vanish silently ---

    /** A journal writer whose append throws once [failAfter] samples are in. */
    private class FailingJournal(file: File, header: JournalHeader, private val failAfter: Int) :
        ClipJournalWriter(file, header) {
        private var n = 0
        override fun append(isVideo: Boolean, ptsUs: Long, keyFrame: Boolean, data: ByteArray) {
            if (n++ == failAfter) throw IOException("No space left on device")
            super.append(isVideo, ptsUs, keyFrame, data)
        }
    }

    @Test
    fun `a journal that cannot be opened is counted, remembered and noted, and the clip still records`() {
        val r = rig(journalFactory = { _, _ -> throw IOException("EACCES") })
        recordClip(r)

        assertTrue(r.recorder.stop())

        assertEquals(1, r.finished.size)
        val stats = r.recorder.stats()
        assertEquals(1L, stats.journalOpenFailures)
        assertEquals(0L, stats.journalWriteFailures)
        assertEquals("open", stats.lastJournalProblem!!.kind)
        assertEquals("event-0", stats.lastJournalProblem!!.eventId)
        assertTrue(stats.lastJournalProblem!!.reason.contains("EACCES"))
        assertEquals(listOf("event-0"), noJournalNotes.map { it.first })
        assertTrue(noJournalNotes[0].second.startsWith("could not be opened"))
        assertTrue(journals().isEmpty())
    }

    @Test
    fun `a header that cannot be built is the same quiet failure, not a lost clip`() {
        val r = rig(formats = FakeFormats(failVideoInfo = true))
        recordClip(r)

        assertTrue(r.recorder.stop())

        assertEquals(1, r.finished.size)
        assertEquals(1L, r.recorder.stats().journalOpenFailures)
        assertTrue(noJournalNotes.single().second.contains("no such key"))
    }

    @Test
    fun `a journal that fails mid-clip is counted and noted, the clip carries on and is not verified against it`() {
        val r = rig(journalFactory = { f, h -> FailingJournal(f, h, failAfter = 1000) })
        recordClip(r, seconds = 6.0)

        assertTrue(r.recorder.stop())

        assertEquals(1, r.finished.size)
        assertNull(r.finished[0].recoveredFrom)
        assertTrue("a journal that stopped short cannot vouch for the MP4", r.verifier.calls.isEmpty())
        val stats = r.recorder.stats()
        assertEquals(1L, stats.journalWriteFailures)
        assertEquals("write", stats.lastJournalProblem!!.kind)
        assertTrue(noJournalNotes.single().second.startsWith("stopped mid-clip"))
        assertTrue("the short journal is removed once the MP4 finalised", journals().isEmpty())
    }

    @Test
    fun `a clip whose journal stopped short and whose stop throws is rebuilt from what the journal has`() {
        val r = rig(journalFactory = { f, h -> FailingJournal(f, h, failAfter = 1000) }) { index, m ->
            if (index == 0) m.failStop = IllegalStateException("Failed to stop the muxer")
        }
        recordClip(r, seconds = 6.0)

        r.recorder.stop()

        val clip = r.finished.single()
        assertNotNull(clip.recoveredFrom)
        assertEquals("only what was journaled", 1000, clip.videoSamples + clip.audioSamples)
        assertEquals(1L, r.recorder.stats().journalWriteFailures)
    }

    @Test
    fun `a clip whose journal cannot be opened records and finishes as it always did`() {
        val missingDir = File(tempFolder.root, "no-such-dir")
        val r = rig(targetDir = missingDir)
        recordClip(r)

        assertTrue(r.recorder.stop())

        assertTrue("nothing to verify against", r.verifier.calls.isEmpty())
        assertEquals(1, r.finished.size)
        assertNull(r.finished[0].recoveredFrom)
    }

    @Test
    fun `without a journal a finalise failure is a plain loss, as before`() {
        val missingDir = File(tempFolder.root, "no-such-dir")
        val r = rig(targetDir = missingDir) { _, m -> m.failStop = IllegalStateException("Failed to stop the muxer") }
        recordClip(r)

        assertFalse(r.recorder.stop())

        val failure = r.failures.single()
        assertFalse(failure.journalKept)
        assertEquals(ClipFailureReason.FINALISE_FAILED, failure.reason)
        assertTrue(r.finished.isEmpty())
    }

    @Test
    fun `a clip that never starts leaves no journal behind`() {
        val r = rig { _, m -> m.failStart = IOException("open failed") }
        advance(r, 12.0)

        assertEquals(ClipRecorder.Outcome.UNAVAILABLE, r.recorder.trigger { newTarget() }.outcome)

        assertTrue(journals().isEmpty())
        assertEquals(ClipFailureReason.START_FAILED, r.failures.single().reason)
    }

    @Test
    fun `discarding a clip deletes its journal and does not verify or rebuild`() {
        val r = rig()
        recordClip(r)
        assertEquals("a journal exists while recording", 1, journals().size)

        assertNotNull(r.recorder.discard())

        assertTrue(journals().isEmpty())
        assertTrue(r.verifier.calls.isEmpty())
        assertTrue(r.finished.isEmpty() && r.failures.isEmpty())
    }

    @Test
    fun `a part lost at the cap is rebuilt and the recording continues in the next part`() {
        val r = rig(maxClipUs = 20_000_000L) { index, m ->
            if (index == 0) m.failStop = IllegalStateException("Failed to stop the muxer")
        }
        advance(r, 12.0)
        r.recorder.trigger { newTarget() }
        var seconds = 0
        while (r.finished.isEmpty() && seconds++ < 60) { advance(r, 1.0); r.recorder.trigger { newTarget() } }

        val rebuilt = r.finished.first()
        assertEquals("event-0", rebuilt.eventId)
        assertNotNull(rebuilt.recoveredFrom)
        assertTrue("held: the recording is not over", rebuilt.continued)
        assertEquals("the continuation part is open", "event-1", r.recorder.activeEventId)
        assertTrue(r.failures.isEmpty())
    }

    @Test
    fun `how long verification and a rollover hold the lock is measured`() {
        val verifier = FakeVerifier()
        verifier.onVerify = { _, _ -> Thread.sleep(30) }
        val r = rig(verifier, maxClipUs = 20_000_000L)
        advance(r, 12.0)
        r.recorder.trigger { newTarget() }
        var seconds = 0
        while (r.finished.isEmpty() && seconds++ < 60) { advance(r, 1.0); r.recorder.trigger { newTarget() } }

        val stats = r.recorder.stats()
        assertTrue("verify held ${stats.maxVerifyHoldMs} ms", stats.maxVerifyHoldMs >= 30)
        assertTrue("the rollover includes the verification: ${stats.maxRolloverHoldMs} ms", stats.maxRolloverHoldMs >= 30)
        assertTrue(stats.maxStartHoldMs < stats.maxRolloverHoldMs + 1)
    }

    @Test
    fun `each part of a rolled-over recording is journaled and verified on its own`() {
        val r = rig(maxClipUs = 20_000_000L)
        advance(r, 12.0)
        r.recorder.trigger { newTarget() }
        var seconds = 0
        while (r.finished.size < 2 && seconds++ < 60) {
            advance(r, 1.0)
            r.recorder.trigger { newTarget() }
        }

        assertTrue(r.finished.size >= 2)
        assertTrue(r.finished[0].continued)
        assertTrue(r.finished.all { it.recoveredFrom == null })
        assertEquals("only the open part has a journal now", 1, journals().size)
        assertEquals(r.finished.size, r.verifier.calls.size)
    }

    // --- an interrupted clip found at the next start ---

    private fun orphanJournal(name: String = "orphan_clip.journal"): File {
        val file = File(tempFolder.root, name)
        val w = ClipJournalWriter(
            file,
            JournalHeader(
                1_700_000_000_000L,
                JournalVideoInfo("video/avc", 1600, 1200, 30, -1, byteArrayOf(1), byteArrayOf(2)),
                JournalAudioInfo("audio/mp4a-latm", 48_000, 1, byteArrayOf(3))
            )
        )
        for (i in 0 until 90) {
            w.append(true, i * 33_333L, i % 30 == 0, ByteArray(5))
            w.append(false, i * 33_333L + 10_000L, false, ByteArray(3))
        }
        w.close()
        return file
    }

    @Test
    fun `a journal left by a dead process is rebuilt into its clip`() {
        val r = rig()
        val journal = orphanJournal()
        val target = File(tempFolder.root, "orphan_clip.mp4")

        r.recorder.rebuildInterrupted("orphan", target, journal)

        val clip = r.finished.single()
        assertEquals("orphan", clip.eventId)
        assertEquals(1_700_000_000_000L, clip.clipStartMs)
        assertEquals(ClipFailureReason.INTERRUPTED, clip.recoveredFrom!!.reason)
        assertEquals(90, clip.videoSamples)
        assertTrue(clip.hadAudioTrack)
        assertFalse(clip.continued)
        assertTrue(target.exists())
        assertFalse(journal.exists())
        assertEquals(1L, r.recorder.stats().clipsRecovered)
    }

    @Test
    fun `a second rebuild of the same event is not started while the first is queued or running`() {
        val queued = mutableListOf<Runnable>()
        val r = rig(executor = Executor { queued.add(it) })
        val journal = orphanJournal()
        val target = File(tempFolder.root, "orphan_clip.mp4")

        r.recorder.rebuildInterrupted("orphan-dup", target, journal)
        r.recorder.rebuildInterrupted("orphan-dup", target, journal) // e.g. the service restarted in this process
        assertEquals("one rebuild queued, the duplicate refused", 1, queued.size)

        queued.removeAt(0).run()
        assertEquals(1, r.finished.size)
        assertEquals(1L, r.recorder.stats().clipsRecovered)

        // Once it has finished the event may be rebuilt again (a new orphan journal, say).
        val again = orphanJournal()
        r.recorder.rebuildInterrupted("orphan-dup", target, again)
        assertEquals(1, queued.size)
        queued.removeAt(0).run()
        assertEquals(2, r.finished.size)
    }

    @Test
    fun `a rebuild that cannot be scheduled does not wedge the event`() {
        val r = rig(executor = Executor { throw java.util.concurrent.RejectedExecutionException("shut down") })
        val journal = orphanJournal()

        r.recorder.rebuildInterrupted("orphan-rej", File(tempFolder.root, "orphan_clip.mp4"), journal)

        assertTrue("the journal is untouched", journal.exists())
        val ok = rig()
        ok.recorder.rebuildInterrupted("orphan-rej", File(tempFolder.root, "orphan_clip.mp4"), journal)
        assertEquals("a later attempt is allowed", 1, ok.finished.size)
    }

    @Test
    fun `an interrupted clip that cannot be rebuilt keeps its journal and says so`() {
        val r = rig(FakeVerifier("bad", "bad"))
        val journal = orphanJournal()

        r.recorder.rebuildInterrupted("orphan", File(tempFolder.root, "orphan_clip.mp4"), journal)

        assertTrue(r.finished.isEmpty())
        val failure = r.failures.single()
        assertTrue(failure.journalKept)
        assertEquals(ClipFailureReason.INTERRUPTED, failure.reason)
        assertTrue(journal.exists())
    }

    @Test
    fun `an interrupted journal that is unreadable is deleted rather than kept`() {
        val r = rig()
        val journal = File(tempFolder.root, "orphan_clip.journal").also { it.writeBytes(ByteArray(40) { 7 }) }

        r.recorder.rebuildInterrupted("orphan", File(tempFolder.root, "orphan_clip.mp4"), journal)

        val failure = r.failures.single()
        assertFalse(failure.journalKept)
        assertFalse(journal.exists())
    }
}
