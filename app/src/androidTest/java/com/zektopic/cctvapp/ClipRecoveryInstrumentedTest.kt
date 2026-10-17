package com.zektopic.cctvapp

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The real-muxer half of clip recovery, which the JVM tests can only fake: encode a second of
 * H.264 with the device's own encoder, journal it, rebuild an MP4 from the journal with a real
 * MediaMuxer, and check the real MediaExtractor-based verifier accepts it and rejects junk.
 *
 * Needs a device with an H.264 encoder. NOT run in CI (CI only compiles instrumented tests).
 */
@RunWith(AndroidJUnit4::class)
class ClipRecoveryInstrumentedTest {

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

    private class Encoded(val format: MediaFormat, val samples: List<Triple<ByteArray, Long, Boolean>>)

    /** Encodes 60 flat frames (30 fps, a keyframe each second) and returns the encoder's output. */
    private fun encode(): Encoded {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, 320, 240).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            setInteger(MediaFormat.KEY_BIT_RATE, 500_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, 30)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
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
                if (queued <= 60) {
                    val i = codec.dequeueInputBuffer(10_000)
                    if (i >= 0) {
                        val pts = queued * 33_333L
                        if (queued == 60) {
                            codec.queueInputBuffer(i, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        } else {
                            val buffer = codec.getInputBuffer(i)!!
                            buffer.clear()
                            val size = 320 * 240 * 3 / 2
                            buffer.put(ByteArray(size) { (queued * 4).toByte() })
                            codec.queueInputBuffer(i, 0, size, pts, 0)
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
        assertTrue("encoder produced frames", samples.size > 30)
        return Encoded(outFormat!!, samples)
    }

    private fun journalOf(encoded: Encoded): File {
        val formats = AndroidClipFormats()
        val file = File(dir, "e1_clip.journal")
        val writer = ClipJournalWriter(file, JournalHeader(1L, formats.videoInfo(encoded.format), null))
        for ((data, pts, key) in encoded.samples) writer.append(true, pts, key, data)
        writer.close()
        return file
    }

    @Test
    fun journalIsRebuiltIntoAnMp4TheRealVerifierAccepts() {
        val encoded = encode()
        val journal = journalOf(encoded)
        val target = File(dir, "e1_clip.mp4")
        val recovery = ClipRecovery(
            JournalReplay({ Mp4ClipMuxer(it.path) }, AndroidClipFormats()),
            MediaExtractorVerifier()
        )

        val result = recovery.recover(journal, target)

        assertEquals(result.error, ClipRecovery.Outcome.REBUILT_VIDEO_ONLY, result.outcome)
        assertEquals(encoded.samples.size, result.videoSamples)
        assertTrue(target.length() > 0)
        assertFalse("verified, so the journal is deleted", journal.exists())
        val span = encoded.samples.last().second - encoded.samples.first().second
        assertEquals(null, MediaExtractorVerifier().verify(target, ClipExpectation(1, span)))
    }

    @Test
    fun theVerifierRejectsAFileThatIsNotAnMp4AndATruncatedOne() {
        val encoded = encode()
        val journal = journalOf(encoded)
        val target = File(dir, "e1_clip.mp4")
        ClipRecovery(JournalReplay({ Mp4ClipMuxer(it.path) }, AndroidClipFormats()), MediaExtractorVerifier())
            .recover(journal, target)
        val span = encoded.samples.last().second - encoded.samples.first().second
        val verifier = MediaExtractorVerifier()

        val junk = File(dir, "junk.mp4").also { it.writeBytes(ByteArray(2_000) { 5 }) }
        assertNotNull(verifier.verify(junk, ClipExpectation(1, span)))

        // A muxer killed before stop() leaves the media data with no index at the end.
        val truncated = File(dir, "cut.mp4").also { it.writeBytes(target.readBytes().copyOf(target.length().toInt() / 2)) }
        assertNotNull(verifier.verify(truncated, ClipExpectation(1, span)))

        assertNotNull("wrong track count", verifier.verify(target, ClipExpectation(2, span)))
    }
}
