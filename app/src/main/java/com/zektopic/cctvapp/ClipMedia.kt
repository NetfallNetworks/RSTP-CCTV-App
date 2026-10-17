package com.zektopic.cctvapp

import android.media.MediaExtractor
import android.media.MediaFormat
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
        mime = format.getString(MediaFormat.KEY_MIME) ?: MediaFormat.MIMETYPE_VIDEO_AVC,
        width = format.getInteger(MediaFormat.KEY_WIDTH),
        height = format.getInteger(MediaFormat.KEY_HEIGHT),
        frameRate = intOrDefault(format, MediaFormat.KEY_FRAME_RATE),
        bitrate = intOrDefault(format, MediaFormat.KEY_BIT_RATE),
        csd0 = bytesOf(format, "csd-0"),
        csd1 = bytesOf(format, "csd-1")
    )

    override fun audioInfo(format: MediaFormat) = JournalAudioInfo(
        mime = format.getString(MediaFormat.KEY_MIME) ?: MediaFormat.MIMETYPE_AUDIO_AAC,
        sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE),
        channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT),
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

    private fun intOrDefault(format: MediaFormat, key: String): Int =
        if (format.containsKey(key)) format.getInteger(key) else -1

    private fun bytesOf(format: MediaFormat, key: String): ByteArray? {
        if (!format.containsKey(key)) return null
        val buffer = format.getByteBuffer(key)?.duplicate() ?: return null
        buffer.rewind()
        return ByteArray(buffer.remaining()).also { buffer.get(it) }
    }
}

/**
 * Reopens an MP4 with MediaExtractor: counts its tracks, checks each yields a sample at the start
 * and near the end (so the index covers the whole clip), and reads its duration. It reads
 * container metadata and single sample times, never sample data, so it is cheap on a big file.
 */
class MediaExtractorVerifier : ClipVerifier {

    override fun verify(file: File, expect: ClipExpectation): String? {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.path)
            val tracks = extractor.trackCount
            var withSamples = 0
            var durationUs: Long? = null
            for (i in 0 until tracks) {
                val format = extractor.getTrackFormat(i)
                val trackDuration = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else null
                if (trackDuration != null) durationUs = maxOf(durationUs ?: 0L, trackDuration)
                extractor.selectTrack(i)
                var ok = extractor.sampleTime >= 0
                if (ok && trackDuration != null && trackDuration > 2_000_000L) {
                    extractor.seekTo(trackDuration - 1_000_000L, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                    ok = extractor.sampleTime >= 0
                }
                extractor.unselectTrack(i)
                if (ok) withSamples++
            }
            return ClipVerification.check(expect, ClipProbe(tracks, withSamples, durationUs))
        } catch (e: Exception) {
            return "could not open: ${ClipFailure.detailOf(e)}"
        } finally {
            try { extractor.release() } catch (_: Exception) {}
        }
    }
}
