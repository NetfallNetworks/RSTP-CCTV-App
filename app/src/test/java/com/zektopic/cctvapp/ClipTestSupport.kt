package com.zektopic.cctvapp

import android.media.MediaFormat
import java.io.IOException
import java.nio.ByteBuffer

/**
 * A ClipMuxer that records what it is given and fails the way MediaMuxer does: stop() throws when
 * any added track never received a sample. Shared by the recorder and recovery tests. With a [file]
 * (in a directory that exists) a successful stop() leaves a stand-in MP4 there.
 */
internal class FakeMuxer(private val file: java.io.File? = null) : ClipMuxer {
    class Sample(val track: Int, val absPtsUs: Long, val relPtsUs: Long, val key: Boolean, val data: ByteArray)

    val tracks = mutableListOf<MediaFormat>()
    val samples = mutableListOf<Sample>()
    var started = false
    var stopped = false
    var failStart: Exception? = null
    var failStop: Exception? = null
    /** The write that would be sample number N (0-based) throws. */
    var failWriteAt = -1

    override fun addTrack(format: MediaFormat): Int {
        tracks.add(format)
        return tracks.size - 1
    }

    override fun start() {
        failStart?.let { throw it }
        started = true
    }

    override fun writeSample(track: Int, data: ByteArray, ptsUs: Long, keyFrame: Boolean) {
        if (samples.size == failWriteAt) throw IOException("No space left on device")
        // The frame's own absolute pts rides in its payload, so a test can see which
        // source frames a clip holds whatever its timeline is anchored at.
        samples.add(Sample(track, if (data.size >= 8) ByteBuffer.wrap(data).long else ptsUs, ptsUs, keyFrame, data))
    }

    override fun stop() {
        failStop?.let { throw it }
        if (tracks.indices.any { t -> samples.none { it.track == t } }) {
            throw IllegalStateException("Failed to stop the muxer")
        }
        stopped = true
        // A finalised MP4 exists on disk, if it has a directory to live in.
        if (file?.parentFile?.exists() == true) file.writeText("mp4")
    }

    override fun release() {}

    fun video() = samples.filter { it.track == 0 }
    fun audio() = samples.filter { it.track == 1 }
}

internal class FakeFormats(var failVideoInfo: Boolean = false) : ClipFormats {
    override fun videoInfo(format: MediaFormat): JournalVideoInfo {
        if (failVideoInfo) throw IllegalStateException("no such key: frame-rate")
        return JournalVideoInfo("video/avc", 1600, 1200, 30, 1_300_000, byteArrayOf(1), byteArrayOf(2))
    }

    override fun audioInfo(format: MediaFormat) = JournalAudioInfo("audio/mp4a-latm", 48_000, 1, byteArrayOf(3))
    override fun video(info: JournalVideoInfo) = MediaFormat()
    override fun audio(info: JournalAudioInfo) = MediaFormat()
}

/** Answers verify() from a queue (null = the file is fine); an empty queue means fine. */
internal class FakeVerifier(vararg answers: String?) : ClipVerifier {
    private val queue = ArrayDeque(answers.toList())
    val calls = mutableListOf<ClipExpectation>()
    /** Runs when verify() is called, e.g. to look at the journal while it still exists. */
    var onVerify: ((java.io.File, ClipExpectation) -> Unit)? = null

    override fun verify(file: java.io.File, expect: ClipExpectation): String? {
        calls.add(expect)
        onVerify?.invoke(file, expect)
        return queue.removeFirstOrNull()
    }
}
