package com.zektopic.cctvapp

import android.app.Instrumentation
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.Executor

/**
 * The real-muxer half of clip recovery, which the JVM tests can only fake: encode video and audio
 * with the device's own encoders, journal them, rebuild MP4s from the journals with a real
 * MediaMuxer, and check the real MediaExtractor-based verifier accepts good files and rejects
 * damaged ones. Also stages the crash-orphan case end to end against a temporary EventStore.
 * Everything lives under the app's cacheDir; the real media directory is never touched.
 *
 * Needs a device with H.264 and AAC encoders. NOT run in CI (CI only compiles instrumented tests).
 *
 * WARNING, if you run this on frankie: `am instrument` force-stops the app, and START_STICKY does
 * NOT restart a force-stopped package, so the camera stream stays down until the app is started
 * again with
 *
 *     am start -n com.zektopic.cctvapp/.MainActivity -a com.zektopic.cctvapp.ACTION_START_SERVER_FROM_BOOT -f 0x14000000
 */
@RunWith(AndroidJUnit4::class)
class ClipRecoveryInstrumentedTest {

    companion object {
        private const val TAG = "ClipRecoveryIT"
    }

    private lateinit var dir: File

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        dir = File(context.cacheDir, "clip-recovery-test").also { it.deleteRecursively(); it.mkdirs() }
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private class Encoded(val format: MediaFormat, val samples: List<Triple<ByteArray, Long, Boolean>>) {
        val payloadBytes: Long get() = samples.sumOf { it.first.size.toLong() }
        val spanUs: Long get() = samples.last().second - samples.first().second
    }

    /**
     * Feeds [frames] buffers (filled by [fill], which returns the byte count) to an encoder for
     * [mime] and returns what it produced, without the codec-config buffer.
     */
    private fun encode(
        mime: String, format: MediaFormat, frames: Int, ptsOf: (Int) -> Long, fill: (ByteBuffer, Int) -> Int
    ): Encoded {
        val codec = MediaCodec.createEncoderByType(mime)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
        val info = MediaCodec.BufferInfo()
        val samples = mutableListOf<Triple<ByteArray, Long, Boolean>>()
        var outFormat: MediaFormat? = null
        var queued = 0
        var done = false
        val deadline = SystemClock.elapsedRealtime() + 30_000
        try {
            while (!done && SystemClock.elapsedRealtime() < deadline) {
                if (queued <= frames) {
                    val i = codec.dequeueInputBuffer(10_000)
                    if (i >= 0) {
                        if (queued == frames) {
                            codec.queueInputBuffer(i, 0, 0, ptsOf(queued), MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        } else {
                            val buffer = codec.getInputBuffer(i)!!
                            buffer.clear()
                            val size = fill(buffer, queued)
                            codec.queueInputBuffer(i, 0, size, ptsOf(queued), 0)
                        }
                        queued++
                    }
                }
                val o = codec.dequeueOutputBuffer(info, 10_000)
                if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    outFormat = codec.outputFormat
                } else if (o >= 0) {
                    val buffer = codec.getOutputBuffer(o)!!
                    if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                        val data = ByteArray(info.size)
                        buffer.position(info.offset)
                        buffer.get(data)
                        samples.add(Triple(data, info.presentationTimeUs, info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0))
                    }
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) done = true
                    codec.releaseOutputBuffer(o, false)
                }
            }
        } finally {
            codec.stop()
            codec.release()
        }
        assertTrue("$mime encoder produced output", samples.size > 10)
        return Encoded(outFormat!!, samples)
    }

    /** Sixty flat frames, 30 fps, a keyframe each second. */
    private fun encodeVideo(): Encoded {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, 320, 240).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            setInteger(MediaFormat.KEY_BIT_RATE, 500_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, 30)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        val size = 320 * 240 * 3 / 2
        return encode(MediaFormat.MIMETYPE_VIDEO_AVC, format, 60, { it * 33_333L }) { buffer, n ->
            buffer.put(ByteArray(size) { (n * 4).toByte() })
            size
        }
    }

    /** About two seconds of silence, 48 kHz mono AAC-LC. */
    private fun encodeAudio(): Encoded {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, 48_000, 1).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 64_000)
        }
        return encode(MediaFormat.MIMETYPE_AUDIO_AAC, format, 100, { it * 1024L * 1_000_000L / 48_000L }) { buffer, _ ->
            val size = minOf(2048, buffer.remaining())
            buffer.put(ByteArray(size))
            size
        }
    }

    private fun recovery(verifier: ClipVerifier = MediaExtractorVerifier()) = ClipRecovery(
        JournalReplay({ Mp4ClipMuxer(it.path) }, AndroidClipFormats()), verifier
    )

    private fun journalOf(video: Encoded, audio: Encoded? = null, name: String = "e1_clip.journal"): File {
        val formats = AndroidClipFormats()
        val file = File(dir, name)
        val header = JournalHeader(1L, formats.videoInfo(video.format), audio?.let { formats.audioInfo(it.format) })
        val writer = ClipJournalWriter(file, header)
        // Interleave by time, as the recorder would see them.
        val all = video.samples.map { Triple(true, it, it.second) } + (audio?.samples ?: emptyList()).map { Triple(false, it, it.second) }
        for ((isVideo, sample, _) in all.sortedBy { it.third }) writer.append(isVideo, sample.second, sample.third, sample.first)
        writer.close()
        return file
    }

    /** A rebuilt, verified video-only MP4 plus what the verifier is told to expect of it. */
    private fun goodMp4(video: Encoded): Pair<File, ClipExpectation> {
        val target = File(dir, "good.mp4")
        val result = recovery().recover(journalOf(video, name = "good.journal"), target)
        assertEquals(result.error, ClipRecovery.Outcome.REBUILT_VIDEO_ONLY, result.outcome)
        return target to ClipExpectation(1, video.spanUs, video.payloadBytes)
    }

    // --- the rebuild and the verifier ---

    @Test
    fun journalIsRebuiltIntoAnMp4TheRealVerifierAccepts() {
        val video = encodeVideo()
        val journal = journalOf(video)
        val target = File(dir, "e1_clip.mp4")

        val result = recovery().recover(journal, target)

        assertEquals(result.error, ClipRecovery.Outcome.REBUILT_VIDEO_ONLY, result.outcome)
        assertEquals(video.samples.size, result.videoSamples)
        assertEquals(video.payloadBytes, result.payloadBytes)
        assertTrue(target.length() >= video.payloadBytes)
        assertFalse("verified, so the journal is deleted", journal.exists())
        assertNull(MediaExtractorVerifier().verify(target, ClipExpectation(1, video.spanUs, video.payloadBytes)))
    }

    @Test
    fun aJournalWithAudioIsReplayedIntoAnMp4WithTwoVerifiedTracks() {
        val video = encodeVideo()
        val audio = encodeAudio()
        val journal = journalOf(video, audio)
        val target = File(dir, "e1_clip.mp4")

        val result = recovery().recover(journal, target)

        assertEquals(result.error, ClipRecovery.Outcome.REBUILT, result.outcome)
        assertTrue(result.hadAudioTrack)
        assertEquals(audio.samples.size, result.audioSamples)
        assertFalse(journal.exists())
        val span = maxOf(video.samples.last().second, audio.samples.last().second) - minOf(video.samples.first().second, audio.samples.first().second)
        assertNull(
            "both tracks are there, yield data, and reach the end",
            MediaExtractorVerifier().verify(target, ClipExpectation(2, span, video.payloadBytes + audio.payloadBytes))
        )
        assertNotNull("and claiming one track is wrong", MediaExtractorVerifier().verify(target, ClipExpectation(1, span)))
    }

    // --- reporting that does not depend on logcat ---

    /** Every case's observed answer, in order: "ACCEPTED" or the verifier's reason (which names the check). */
    private val observed = linkedMapOf<String, String>()

    private fun observe(case: String, answer: String?) {
        observed[case] = answer ?: "ACCEPTED"
        Log.i(TAG, "$case -> ${observed[case]}")
    }

    /**
     * Sends every observed answer through the instrumentation status stream, so `am instrument -w -r`
     * prints them (logcat's main buffer is only 64 KiB and is gone in minutes), and appends them to a
     * results file in the test package's own cache directory.
     */
    private fun publish(title: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val text = observed.entries.joinToString("\n") { "${it.key}: ${it.value}" }
        val bundle = Bundle()
        for ((case, answer) in observed) bundle.putString("$title.${case.replace(Regex("[^A-Za-z0-9]+"), "_")}", answer)
        // Reporting must never fail a case: the file is a convenience (its directory does not exist on a
        // fresh install), and an IO error is itself reported through the status stream.
        try {
            val resultsDir = instrumentation.context.cacheDir
            resultsDir.mkdirs()
            File(resultsDir, "clip-recovery-results.txt").appendText("== $title ==\n$text\n")
        } catch (e: Exception) {
            bundle.putString("$title.results_file_error", "${e.javaClass.simpleName}: ${e.message}")
        }
        bundle.putString(Instrumentation.REPORT_KEY_STREAMRESULT, "\n== $title ==\n$text\n")
        try {
            instrumentation.sendStatus(0, bundle)
        } catch (e: Exception) {
            Log.w(TAG, "Could not send the status stream: ${e.message}")
        }
    }

    @Test
    fun theVerifierRejectsDamagedFilesAndAcceptsTheGoodOne() {
        val video = encodeVideo()
        val (good, expect) = goodMp4(video)
        val verifier = MediaExtractorVerifier()
        val bytes = good.readBytes()
        val mdat = indexOf(bytes, "mdat".toByteArray())
        assertTrue("the file has a media data box", mdat >= 0)
        val problems = mutableListOf<String>()

        fun variant(name: String, content: ByteArray) = File(dir, name).also { it.writeBytes(content) }
        // One failing case must not hide the others: note it and carry on.
        fun mustReject(case: String, file: File, expected: ClipExpectation = expect) {
            val answer = verifier.verify(file, expected)
            observe(case, answer)
            if (answer == null) problems.add("$case was ACCEPTED")
        }

        val control = verifier.verify(good, expect)
        observe("untouched file (must be accepted)", control)
        if (control != null) problems.add("the untouched file was REJECTED: $control")

        mustReject("cut at 50%", variant("cut50.mp4", bytes.copyOf(bytes.size / 2)))
        mustReject("cut at 90%", variant("cut90.mp4", bytes.copyOf(bytes.size * 9 / 10)))
        mustReject("cut right after the front of the media data", variant("front.mp4", bytes.copyOf(mdat + 16)))
        mustReject("last 100 bytes removed", variant("minus100.mp4", bytes.copyOf(bytes.size - 100)))
        mustReject("last 1 byte removed", variant("minus1.mp4", bytes.copyOf(bytes.size - 1)))
        mustReject("100 garbage bytes appended", variant("plus100.mp4", bytes + ByteArray(100) { 0x5A }))
        mustReject("zero-length file", variant("empty.mp4", ByteArray(0)))
        mustReject("not an MP4 at all", variant("junk.mp4", ByteArray(2_000) { 5 }))
        mustReject("wrong track count", good, ClipExpectation(2, expect.spanUs, expect.minBytes))

        publish("damaged-files")
        assertTrue(
            "${problems.size} verifier gap(s):\n${problems.joinToString("\n")}\n\nAll answers:\n" +
                observed.entries.joinToString("\n") { "${it.key}: ${it.value}" },
            problems.isEmpty()
        )
    }

    @Test
    fun aGarbageTailOfTheRightSizeIsReportedNotAsserted() {
        // The container cannot tell good H.264 from garbage: the extractor reads whatever bytes the
        // sample table points at. Whether this is caught depends on the extractor's NAL handling, so
        // this records the outcome on the device (in the status stream and the results file) rather
        // than demanding one. What must hold is that the verifier answers instead of crashing.
        val video = encodeVideo()
        val (good, expect) = goodMp4(video)
        val bytes = good.readBytes()
        val garbled = bytes.copyOf()
        for (i in bytes.size * 7 / 10 until bytes.size) garbled[i] = 0x5A
        val file = File(dir, "garbage.mp4").also { it.writeBytes(garbled) }
        assertEquals(bytes.size.toLong(), file.length())

        observe("garbage tail of the correct size (last 30%)", MediaExtractorVerifier().verify(file, expect))

        publish("garbage-tail")
    }
    // --- format conversion through the real MediaFormat ---

    @Test
    fun frameRateIsReadWhetherTheFormatHoldsAnIntAFloatOrNothing() {
        val formats = AndroidClipFormats()
        fun base() = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, 320, 240)

        val asInt = base().apply { setInteger(MediaFormat.KEY_FRAME_RATE, 30) }
        val asFloat = base().apply { setFloat(MediaFormat.KEY_FRAME_RATE, 29.97f) }
        val absent = base()

        assertEquals(30, formats.videoInfo(asInt).frameRate)
        assertEquals(30, formats.videoInfo(asFloat).frameRate)
        assertEquals(-1, formats.videoInfo(absent).frameRate)
        assertEquals(320, formats.videoInfo(absent).width)
        assertEquals(240, formats.videoInfo(absent).height)
        val back = formats.video(formats.videoInfo(asFloat))
        assertEquals(320, back.getInteger(MediaFormat.KEY_WIDTH))
        assertEquals(30, back.getInteger(MediaFormat.KEY_FRAME_RATE))
        assertFalse(formats.video(formats.videoInfo(absent)).containsKey(MediaFormat.KEY_FRAME_RATE))
    }

    // --- a crash, staged ---

    @Test
    fun aClipAnEarlierRunDiedInTheMiddleOfIsRebuiltAtServiceStartAndItsJournalGoesOnlyAfterVerification() {
        val video = encodeVideo()
        val formats = AndroidClipFormats()
        val store = EventStore(File(dir, "store").also { it.mkdirs() })
        val event = store.createDetectionEvent("person", 0.9, null, recording = true)
        val journal = store.journalFileFor(event.id)

        // As the recorder would have left it when the process was killed: the header and a stretch
        // of samples journaled and flushed, no close, and an MP4 with no index.
        val partway = video.samples.take(40)
        val writer = ClipJournalWriter(journal, JournalHeader(1_700_000_000_000L, formats.videoInfo(video.format), null))
        for ((data, pts, key) in partway) writer.append(true, pts, key, data)
        writer.flush()
        store.clipFileFor(event.id).writeBytes(ByteArray(4_096))

        // --- what CctvServerService.onCreate does ---
        assertEquals("the interrupted clip has a journal, so it is not simply cleared", 0, store.recoverInterrupted())
        store.deleteOrphanJournals()
        val pending = store.pendingJournalRebuilds()
        assertEquals(listOf(event.id), pending.map { it.id })
        assertEquals("still recording: the archive skips it", ArchiveResult.RECORDING, store.archiveEvent(event.id))

        var journalExistedWhenVerified = false
        val spy = object : ClipVerifier {
            override fun verify(file: File, expect: ClipExpectation): String? {
                journalExistedWhenVerified = journal.exists()
                return MediaExtractorVerifier().verify(file, expect)
            }
        }
        val failures = mutableListOf<ClipFailure>()
        val recorder = ClipRecorder(
            onClipFinished = { clip ->
                store.attachClip(
                    clip.eventId, clip.file, System.currentTimeMillis(), clip.durationUs / 1000, clip.clipStartMs,
                    null, null, held = false,
                    clipError = clip.recoveredFrom?.toClipError(),
                    recovered = clip.recoveredFrom != null && !clip.unverified
                )
            },
            onRollover = { null },
            onClipFailed = { failures.add(it) },
            safety = ClipSafety(formats, spy, executor = Executor { it.run() })
        )
        for (e in pending) recorder.rebuildInterrupted(e.id, store.clipFileFor(e.id), store.journalFileFor(e.id))
        writer.close()

        assertTrue("rebuild reported failures: ${failures.map { it.message }}", failures.isEmpty())
        assertTrue("the journal was still there when the MP4 was verified", journalExistedWhenVerified)
        assertFalse("and is gone once it passed", journal.exists())
        val stored = store.getEvent(event.id)!!
        assertFalse(stored.recording)
        assertTrue(stored.clipRecovered)
        assertEquals("INTERRUPTED", stored.clipError?.reason)
        assertNotNull(stored.clipFileName)
        val span = partway.last().second - partway.first().second
        val clip = store.clipFileFor(event.id)
        assertTrue(clip.length() >= partway.sumOf { it.first.size.toLong() })
        assertNull(MediaExtractorVerifier().verify(clip, ClipExpectation(1, span, partway.sumOf { it.first.size.toLong() })))
        assertTrue(store.pendingJournalRebuilds().isEmpty())
    }

    private fun indexOf(haystack: ByteArray, needle: ByteArray): Int {
        outer@ for (i in 0..haystack.size - needle.size) {
            for (j in needle.indices) if (haystack[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }
}
