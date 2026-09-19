package com.zektopic.cctvapp

import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import com.pedro.common.AudioCodec
import com.pedro.common.VideoCodec
import com.pedro.library.base.recording.RecordController
import java.io.File
import java.io.FileDescriptor
import java.nio.ByteBuffer

/**
 * Saves event clips from the stream encoder's own output, without a second encoder.
 *
 * Installed with setRecordController(). RootEncoder hands every encoded stream frame to the
 * record controller whether or not anything is recording, so this sees exactly the H.264
 * that RTSP clients get -- same orientation and mirror fixes, no extra encode, no extra
 * heat. It keeps the last few seconds in a [PreRollBuffer]; [trigger] opens an MP4 that
 * starts with that buffer, and each further trigger pushes the end out (see [ClipWindow]).
 *
 * To RootEncoder this is always an idle controller -- [isRecording] and [isRunning] are
 * false -- so stopStream() still tears the encoders down as normal. Video only: the stream
 * carries no audio by default, and a clip does not need it to be useful.
 */
class ClipRecorder(
    preRollUs: Long = DEFAULT_PRE_ROLL_US,
    private val postRollUs: Long = DEFAULT_POST_ROLL_US,
    private val maxClipUs: Long = DEFAULT_MAX_CLIP_US,
    private val onClipFinished: (FinishedClip) -> Unit
) : RecordController {

    companion object {
        private const val TAG = "ClipRecorder"
        const val DEFAULT_PRE_ROLL_US = 5_000_000L
        const val DEFAULT_POST_ROLL_US = 10_000_000L
        const val DEFAULT_MAX_CLIP_US = 120_000_000L

        /** Ceiling for the pre-roll; several times what 7 s at stream bitrates needs. */
        private const val MAX_BUFFER_BYTES = 16L * 1024 * 1024
        private const val H264_NAL_IDR = 5
    }

    class ClipTarget(val eventId: String, val file: File)
    class FinishedClip(val eventId: String, val file: File, val durationUs: Long)

    enum class Trigger {
        /** A new clip opened; the caller's target is now being written. */
        STARTED,
        /** A clip was already open and now runs longer. */
        EXTENDED,
        /** No clip: nothing encoded yet, or the muxer could not be opened. */
        UNAVAILABLE
    }

    private class ActiveClip(
        val target: ClipTarget,
        val muxer: MediaMuxer,
        val track: Int,
        val window: ClipWindow
    ) {
        var lastPtsUs = Long.MIN_VALUE
    }

    private val lock = Any()
    private val buffer = PreRollBuffer(preRollUs, MAX_BUFFER_BYTES)
    private var videoFormat: MediaFormat? = null
    private var active: ActiveClip? = null
    private var videoCodec = VideoCodec.H264
    private var audioCodec = AudioCodec.AAC

    /**
     * Starts a clip, or extends the open one. [open] is called only when a new clip is
     * about to start, and supplies where it goes; returning null declines.
     */
    fun trigger(open: () -> ClipTarget?): Trigger = synchronized(lock) {
        val nowUs = buffer.newestPtsUs ?: return Trigger.UNAVAILABLE
        active?.let {
            it.window.extend(nowUs)
            return Trigger.EXTENDED
        }
        val format = videoFormat ?: return Trigger.UNAVAILABLE
        val frames = buffer.framesFromKeyFrame()
        if (frames.isEmpty()) return Trigger.UNAVAILABLE
        val target = open() ?: return Trigger.UNAVAILABLE

        var muxer: MediaMuxer? = null
        try {
            muxer = MediaMuxer(target.file.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val track = muxer.addTrack(format)
            muxer.start()
            val clip = ActiveClip(
                target, muxer, track,
                ClipWindow(postRollUs, maxClipUs, frames.first().ptsUs, nowUs)
            )
            for (frame in frames) write(clip, frame)
            active = clip
            Log.i(TAG, "Clip ${target.eventId} started with ${frames.size} pre-roll frames")
            Trigger.STARTED
        } catch (e: Exception) {
            Log.e(TAG, "Could not start clip ${target.eventId}", e)
            try { muxer?.release() } catch (_: Exception) {}
            target.file.delete()
            Trigger.UNAVAILABLE
        }
    }

    override fun recordVideo(videoBuffer: ByteBuffer, videoInfo: MediaCodec.BufferInfo) {
        if (videoInfo.size <= 0 || videoInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) return
        // Same read as RootEncoder's own muxer controller: the whole buffer, from zero.
        val source = videoBuffer.duplicate().apply { rewind() }
        val data = ByteArray(source.remaining())
        source.get(data)
        val frame = PreRollBuffer.Frame(data, videoInfo.presentationTimeUs, isKeyFrame(data, videoInfo))

        synchronized(lock) {
            buffer.add(frame)
            val clip = active ?: return
            if (clip.window.isOver(frame.ptsUs)) {
                finish(clip)
                return
            }
            try {
                write(clip, frame)
            } catch (e: Exception) {
                Log.e(TAG, "Write failed, closing clip ${clip.target.eventId}", e)
                finish(clip)
            }
        }
    }

    override fun setVideoFormat(videoFormat: MediaFormat) {
        synchronized(lock) {
            // A new format means a new encoder session; frames from the old one can
            // neither start nor continue a clip in it.
            active?.let { finish(it) }
            buffer.clear()
            this.videoFormat = videoFormat
        }
    }

    /** Called by RootEncoder when the stream stops. */
    override fun resetFormats() {
        synchronized(lock) {
            active?.let { finish(it) }
            buffer.clear()
            videoFormat = null
        }
    }

    private fun write(clip: ActiveClip, frame: PreRollBuffer.Frame) {
        // MediaMuxer rejects non-increasing timestamps.
        if (frame.ptsUs <= clip.lastPtsUs) return
        val info = MediaCodec.BufferInfo().apply {
            set(
                0, frame.data.size, frame.ptsUs - clip.window.startUs,
                if (frame.isKeyFrame) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
            )
        }
        clip.muxer.writeSampleData(clip.track, ByteBuffer.wrap(frame.data), info)
        clip.lastPtsUs = frame.ptsUs
    }

    /** Caller must hold [lock]. */
    private fun finish(clip: ActiveClip) {
        active = null
        val durationUs = clip.lastPtsUs - clip.window.startUs
        val ok = try {
            clip.muxer.stop()
            true
        } catch (e: Exception) {
            Log.e(TAG, "Could not finalise clip ${clip.target.eventId}", e)
            false
        } finally {
            try { clip.muxer.release() } catch (_: Exception) {}
        }
        if (!ok) {
            clip.target.file.delete()
            return
        }
        Log.i(TAG, "Clip ${clip.target.eventId} finished, ${durationUs / 1000} ms")
        onClipFinished(FinishedClip(clip.target.eventId, clip.target.file, durationUs))
    }

    private fun isKeyFrame(data: ByteArray, info: MediaCodec.BufferInfo): Boolean {
        if (info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0) return true
        // Annex-B start code then the NAL header, as RootEncoder's own check reads it.
        return videoCodec == VideoCodec.H264 && data.size > 4 &&
            (data[4].toInt() and 0x1F) == H264_NAL_IDR
    }

    // Everything below is RootEncoder's own recording API. This controller is only ever
    // driven through trigger(), so these are inert.

    override fun startRecord(path: String, listener: RecordController.Listener?, tracks: RecordController.RecordTracks) {
        Log.w(TAG, "startRecord($path) ignored: clips are started by detection")
    }

    override fun startRecord(fd: FileDescriptor, listener: RecordController.Listener?, tracks: RecordController.RecordTracks) {
        Log.w(TAG, "startRecord(fd) ignored: clips are started by detection")
    }

    override fun stopRecord() {}
    override fun recordAudio(audioBuffer: ByteBuffer, audioInfo: MediaCodec.BufferInfo) {}
    override fun setAudioFormat(audioFormat: MediaFormat) {}
    override fun isRunning(): Boolean = false
    override fun isRecording(): Boolean = false
    override fun pauseRecord() {}
    override fun resumeRecord() {}
    override fun setRequestKeyFrame(requestKeyFrame: RecordController.RequestKeyFrame?) {}
    override fun getStatus(): RecordController.Status = RecordController.Status.STOPPED

    override fun setVideoCodec(videoCodec: VideoCodec) {
        this.videoCodec = videoCodec
    }

    override fun setAudioCodec(audioCodec: AudioCodec) {
        this.audioCodec = audioCodec
    }

    override fun updateInfo(videoCodec: VideoCodec, audioCodec: AudioCodec) {
        this.videoCodec = videoCodec
        this.audioCodec = audioCodec
    }

    override fun getVideoCodec(): VideoCodec = videoCodec
    override fun getAudioCodec(): AudioCodec = audioCodec
}
