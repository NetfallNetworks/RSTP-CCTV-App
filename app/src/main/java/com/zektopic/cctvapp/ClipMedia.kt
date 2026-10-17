package com.zektopic.cctvapp

import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Build
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.nio.ByteBuffer

/*
 * The Android-only half of clip recovery: turning MediaFormat into journal headers and back, and
 * reopening a finished MP4 with MediaExtractor. Everything that decides what to do with these
 * (ClipRecovery, ClipVerification, JournalReplay) is plain Kotlin and unit-tested with fakes; this
 * file is NOT exercised by the JVM tests and needs a run on a device.
 */

class AndroidClipFormats : ClipFormats {

    override fun videoInfo(format: MediaFormat) = JournalVideoInfo(
        mime = stringOrDefault(format, MediaFormat.KEY_MIME, MediaFormat.MIMETYPE_VIDEO_AVC),
        width = number(format, MediaFormat.KEY_WIDTH),
        height = number(format, MediaFormat.KEY_HEIGHT),
        frameRate = number(format, MediaFormat.KEY_FRAME_RATE),
        bitrate = number(format, MediaFormat.KEY_BIT_RATE),
        csd0 = bytesOf(format, "csd-0"),
        csd1 = bytesOf(format, "csd-1")
    )

    override fun audioInfo(format: MediaFormat) = JournalAudioInfo(
        mime = stringOrDefault(format, MediaFormat.KEY_MIME, MediaFormat.MIMETYPE_AUDIO_AAC),
        sampleRate = number(format, MediaFormat.KEY_SAMPLE_RATE),
        channels = number(format, MediaFormat.KEY_CHANNEL_COUNT),
        csd0 = bytesOf(format, "csd-0")
    )

    override fun video(info: JournalVideoInfo): MediaFormat =
        MediaFormat.createVideoFormat(info.mime, info.width, info.height).apply {
            if (info.frameRate > 0) setInteger(MediaFormat.KEY_FRAME_RATE, info.frameRate)
            if (info.bitrate > 0) setInteger(MediaFormat.KEY_BIT_RATE, info.bitrate)
            info.csd0?.let { setByteBuffer("csd-0", ByteBuffer.wrap(it)) }
            info.csd1?.let { setByteBuffer("csd-1", ByteBuffer.wrap(it)) }
        }

    override fun audio(info: JournalAudioInfo): MediaFormat =
        MediaFormat.createAudioFormat(info.mime, info.sampleRate, info.channels).apply {
            info.csd0?.let { setByteBuffer("csd-0", ByteBuffer.wrap(it)) }
        }

    private fun number(format: MediaFormat, key: String): Int =
        FormatNumbers.read(
            format.containsKey(key), { format.getInteger(key) }, { format.getFloat(key) }
        )

    private fun stringOrDefault(format: MediaFormat, key: String, default: String): String =
        try { format.getString(key) ?: default } catch (_: Exception) { default }

    private fun bytesOf(format: MediaFormat, key: String): ByteArray? {
        if (!format.containsKey(key)) return null
        return try {
            val buffer = format.getByteBuffer(key)?.duplicate() ?: return null
            buffer.rewind()
            ByteArray(buffer.remaining()).also { buffer.get(it) }
        } catch (_: Exception) {
            null
        }
    }
}

/**
 * Reopens an MP4 with MediaExtractor: counts its tracks, checks each yields a sample at the start
 * and near the end (so the index covers the whole clip), and reads its duration. It reads
 * container metadata and single sample times, never sample data, so it is cheap on a big file.
 */
class MediaExtractorVerifier : ClipVerifier {

    companion object {
        private const val TAG = "ClipVerifier"

        /** Where the tail walk starts, before the end of the track. */
        private const val TAIL_WALK_US = 5_000_000L

        /** Samples stepped over in the tail walk at most (a few seconds of video plus audio). */
        private const val MAX_TAIL_STEPS = 3_000

        /** Room for one sample; keyframes at this stream's bitrates are a fraction of it. */
        private const val INITIAL_READ_BUFFER_BYTES = 1024 * 1024
    }

    /** One verification's scratch space for sample bytes; per call, because verify() can run on two threads. */
    private class ReadBuffer {
        var buffer: ByteBuffer = ByteBuffer.allocate(INITIAL_READ_BUFFER_BYTES)
    }

    /** Reads the current sample's bytes into [scratch] (grown if the sample is bigger); -1 at the end. */
    private fun readSample(extractor: MediaExtractor, scratch: ReadBuffer): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val need = extractor.sampleSize
            if (need > scratch.buffer.capacity()) scratch.buffer = ByteBuffer.allocate(need.toInt())
        }
        scratch.buffer.clear()
        return extractor.readSampleData(scratch.buffer, 0)
    }

    override fun verify(file: File, expect: ClipExpectation): String? {
        val startedMs = SystemClock.elapsedRealtime()
        // First, the check that needs no extractor: a file shorter than the sample data written to
        // it is cut off, even if its index (reserved near the front by some muxers) reads fine.
        val fileBytes = file.length()
        ClipVerification.checkSize(expect, fileBytes)?.let {
            Log.i(TAG, "${file.name}: $it -> rejected")
            return it
        }
        val extractor = MediaExtractor()
        val scratch = ReadBuffer()
        var tracks = -1
        var withSamples = -1
        var durationUs: Long? = null
        var result: String? = null
        try {
            extractor.setDataSource(file.path)
            tracks = extractor.trackCount
            withSamples = 0
            for (i in 0 until tracks) {
                val format = extractor.getTrackFormat(i)
                val trackDuration = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else null
                if (trackDuration != null) durationUs = maxOf(durationUs ?: 0L, trackDuration)
                extractor.selectTrack(i)
                // Sample times come from the index alone, and some muxers keep the index near the
                // front, so a file whose media data is cut off still lists every sample. Read the
                // data itself: the first sample, then everything in the last few seconds.
                var ok = extractor.sampleTime >= 0 && readSample(extractor, scratch) >= 0
                if (ok) {
                    if (trackDuration != null) {
                        extractor.seekTo(maxOf(0L, trackDuration - TAIL_WALK_US), MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                    }
                    var last = -1L
                    var reads = 0
                    var steps = 0
                    while (steps++ < MAX_TAIL_STEPS) {
                        // A read of -1 before the duration is reached means the data ends early.
                        if (readSample(extractor, scratch) < 0) break
                        val t = extractor.sampleTime
                        if (t > last) last = t
                        reads++
                        if (!extractor.advance()) break
                    }
                    ok = reads > 0 && ClipVerification.tailReached(last, trackDuration)
                }
                extractor.unselectTrack(i)
                if (ok) withSamples++
            }
            result = ClipVerification.check(expect, ClipProbe(tracks, withSamples, durationUs, fileBytes))
        } catch (e: Exception) {
            result = "could not open: ${ClipFailure.detailOf(e)}"
        } finally {
            try { extractor.release() } catch (_: Exception) {}
            Log.i(
                TAG,
                "${file.name}: bytes=$fileBytes (payload ${expect.minBytes}) tracks=$tracks (expected ${expect.tracks}) withSamples=$withSamples " +
                    "duration=${durationUs?.div(1000)} ms (journal span ${expect.spanUs / 1000} ms) " +
                    "in ${SystemClock.elapsedRealtime() - startedMs} ms -> ${result ?: "ok"}"
            )
        }
        return result
    }
}
