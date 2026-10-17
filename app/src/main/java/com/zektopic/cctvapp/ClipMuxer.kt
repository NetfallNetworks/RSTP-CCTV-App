package com.zektopic.cctvapp

import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import java.nio.ByteBuffer

/**
 * The part of [MediaMuxer] that [ClipRecorder] uses, so its clip bookkeeping (what is
 * written, when a clip ends, what a failure reports) can be exercised on the JVM with a fake.
 * Timestamps are microseconds from the clip's first frame.
 */
interface ClipMuxer {
    fun addTrack(format: MediaFormat): Int
    fun start()
    fun writeSample(track: Int, data: ByteArray, ptsUs: Long, keyFrame: Boolean)
    fun stop()
    fun release()
}

/** The real thing: an MP4 file at [path]. */
class Mp4ClipMuxer(path: String) : ClipMuxer {
    private val muxer = MediaMuxer(path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

    override fun addTrack(format: MediaFormat): Int = muxer.addTrack(format)
    override fun start() = muxer.start()

    override fun writeSample(track: Int, data: ByteArray, ptsUs: Long, keyFrame: Boolean) {
        val info = MediaCodec.BufferInfo().apply {
            set(0, data.size, ptsUs, if (keyFrame) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
        }
        muxer.writeSampleData(track, ByteBuffer.wrap(data), info)
    }

    override fun stop() = muxer.stop()
    override fun release() = muxer.release()
}
