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
    private val onClipFinished: (FinishedClip) -> Unit,
    /**
     * Where the next file goes when a clip hits [maxClipUs] while activity is still
     * extending it. It starts from the pre-roll buffer, so it overlaps the end of the
     * last one rather than leaving a gap. Returning null ends recording there.
     */
    private val onRollover: (FinishedClip) -> ClipTarget?,
    /**
     * The event behind a clip that failed to start (trigger/startManual/rollover) or
     * finalise (finish, keep=true only -- Discard is intentional and the caller already
     * has the event id from [discard]'s return). Also called with a [FinishedClip.continued]
     * part's own id when [onRollover] declined or threw, since that recording has now ended
     * after all. Called from inside [lock]; must not call back into this ClipRecorder.
     * Default keeps other call sites (tests) compiling.
     */
    private val onClipFailed: (eventId: String) -> Unit = {}
) : RecordController {

    companion object {
        private const val TAG = "ClipRecorder"
        const val DEFAULT_PRE_ROLL_US = 5_000_000L

        /**
         * Long, because animals move slowly and in bursts -- a cat can sit still for half
         * a minute between steps. Ending a clip in that pause cuts the part worth seeing.
         */
        const val DEFAULT_POST_ROLL_US = 60_000_000L

        /** Per file, not per event: activity beyond it continues in a rollover clip. */
        const val DEFAULT_MAX_CLIP_US = 600_000_000L

        /** Ceiling for the pre-roll; several times what 7 s at stream bitrates needs. */
        private const val MAX_BUFFER_BYTES = 16L * 1024 * 1024
        private const val H264_NAL_IDR = 5
    }

    class ClipTarget(val eventId: String, val file: File)
    /**
     * [continued]: this part ended at the per-file cap and a continuation part is about to
     * be opened, so the recording has not ended. If that continuation cannot be opened,
     * [onClipFailed] is called -- with the continuation's id when its event exists, or with
     * this part's own id when [onRollover] declined -- so the recording is still ended.
     */
    class FinishedClip(
        val eventId: String, val file: File, val durationUs: Long, val clipStartMs: Long,
        val continued: Boolean = false
    )

    enum class Outcome {
        /** A new clip opened; the caller's target is now being written. */
        STARTED,
        /** A clip was already open and now runs longer. */
        EXTENDED,
        /** No clip: nothing encoded yet, or the muxer could not be opened. */
        UNAVAILABLE
    }

    /** [eventId] is the event whose clip is recording, for STARTED and EXTENDED. */
    class Trigger(val outcome: Outcome, val eventId: String? = null)

    private class ActiveClip(
        val target: ClipTarget,
        val muxer: MediaMuxer,
        val track: Int,
        val window: ClipWindow,
        /** Wall-clock time of the first frame: now minus the pre-roll already buffered. */
        val clipStartMs: Long
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
        val unavailable = Trigger(Outcome.UNAVAILABLE)
        val nowUs = buffer.newestPtsUs ?: return unavailable
        active?.let {
            it.window.extend(nowUs)
            return Trigger(Outcome.EXTENDED, it.target.eventId)
        }
        if (videoFormat == null || buffer.framesFromKeyFrame().isEmpty()) return unavailable
        val target = open() ?: return unavailable
        if (startClip(target, nowUs)) return Trigger(Outcome.STARTED, target.eventId)
        onClipFailed(target.eventId)
        return unavailable
    }

    val activeEventId: String? get() = synchronized(lock) { active?.target?.eventId }

    /**
     * Manual "Record now": opens a clip held for [minutes], or holds the one already open.
     * [open] is called only when a new clip is about to start, and supplies where it goes;
     * returning null declines.
     */
    fun startManual(minutes: Int, open: () -> ClipTarget?): Trigger = synchronized(lock) {
        val nowUs = buffer.newestPtsUs ?: return Trigger(Outcome.UNAVAILABLE)
        val untilUs = nowUs + minutes * 60_000_000L
        active?.let {
            it.window.hold(untilUs)
            return Trigger(Outcome.EXTENDED, it.target.eventId)
        }
        if (videoFormat == null || buffer.framesFromKeyFrame().isEmpty()) return Trigger(Outcome.UNAVAILABLE)
        val target = open() ?: return Trigger(Outcome.UNAVAILABLE)
        // A manual clip's detection end is "now": the hold is what keeps it running.
        if (startClip(target, nowUs - postRollUs, untilUs)) return Trigger(Outcome.STARTED, target.eventId)
        onClipFailed(target.eventId)
        return Trigger(Outcome.UNAVAILABLE)
    }

    /**
     * Adds [minutes] to the open clip's hold, from the later of the current hold and now --
     * so repeated presses stack rather than one big hold suppressing a shorter later one.
     * False when nothing is recording.
     */
    fun hold(minutes: Int): Boolean = synchronized(lock) {
        val clip = active ?: return false
        val nowUs = buffer.newestPtsUs ?: return false
        clip.window.hold(maxOf(clip.window.holdUntilUs, nowUs) + minutes * 60_000_000L)
        true
    }

    /**
     * Ends the open clip and keeps it, without triggering a rollover. Returns true only when a
     * clip was open and finalised successfully; false when nothing was recording or the file
     * could not be finalised (and was deleted).
     */
    fun stop(): Boolean = synchronized(lock) {
        val clip = active ?: return false
        finish(clip) != null
    }

    /** Ends the open clip and deletes its file. [onClipFinished] is not called. Returns its event id. */
    fun discard(): String? = synchronized(lock) {
        val clip = active ?: return null
        finish(clip, keep = false)
        clip.target.eventId
    }

    class Status(val eventId: String, val elapsedMs: Long, val holdRemainingMs: Long?, val endsInMs: Long)

    fun status(): Status? = synchronized(lock) {
        val clip = active ?: return null
        val nowUs = buffer.newestPtsUs ?: return null
        val w = clip.window
        Status(
            eventId = clip.target.eventId,
            elapsedMs = (nowUs - w.startUs) / 1000,
            holdRemainingMs = if (w.holdUntilUs > nowUs) (w.holdUntilUs - nowUs) / 1000 else null,
            endsInMs = ((maxOf(w.detectionEndUs, w.holdUntilUs) - nowUs) / 1000).coerceAtLeast(0)
        )
    }

    /**
     * Opens [target] and writes the pre-roll into it, ending [postRollUs] after
     * [triggeredAtUs] unless extended. Caller must hold [lock].
     */
    private fun startClip(target: ClipTarget, triggeredAtUs: Long, holdUntilUs: Long = Long.MIN_VALUE): Boolean {
        val format = videoFormat ?: return false
        val frames = buffer.framesFromKeyFrame()
        if (frames.isEmpty()) return false

        var muxer: MediaMuxer? = null
        return try {
            muxer = MediaMuxer(target.file.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val track = muxer.addTrack(format)
            muxer.start()
            val newestUs = buffer.newestPtsUs ?: frames.last().ptsUs
            val clipStartMs = System.currentTimeMillis() - (newestUs - frames.first().ptsUs) / 1000
            val clip = ActiveClip(
                target, muxer, track,
                ClipWindow(postRollUs, maxClipUs, frames.first().ptsUs, triggeredAtUs),
                clipStartMs
            )
            for (frame in frames) write(clip, frame)
            clip.window.hold(holdUntilUs)
            active = clip
            Log.i(TAG, "Clip ${target.eventId} started with ${frames.size} pre-roll frames")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Could not start clip ${target.eventId}", e)
            try { muxer?.release() } catch (_: Exception) {}
            target.file.delete()
            false
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
                val finished = finish(clip, continued = clip.window.cutShort)
                if (finished != null && finished.continued) rollOver(finished, clip.window)
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

    /**
     * Continues a clip that hit the length cap while still wanted, in a new file that
     * runs to where the old one would have ended. Caller must hold [lock].
     */
    private fun rollOver(previous: FinishedClip, window: ClipWindow) {
        val target = try {
            onRollover(previous)
        } catch (e: Exception) {
            Log.e(TAG, "Rollover after ${previous.eventId} failed", e)
            null
        }
        if (target == null) {
            // No continuation after all: [previous] was reported continued (held), so it
            // must be reported again or its recording would never end.
            onClipFailed(previous.eventId)
            return
        }
        if (!startClip(target, window.detectionEndUs - postRollUs, window.holdUntilUs)) {
            onClipFailed(target.eventId)
        }
    }

    /**
     * Ends [clip]. keep=false deletes the file instead of reporting it (Discard).
     * [continued]: the caller will open a continuation part next (see
     * [FinishedClip.continued]); only the natural end at the per-file cap passes true.
     * Returns the finished clip only when kept and finalised. Caller must hold [lock].
     */
    private fun finish(clip: ActiveClip, keep: Boolean = true, continued: Boolean = false): FinishedClip? {
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
        if (!ok || !keep) {
            clip.target.file.delete()
            // Discard (keep=false) is intentional and the caller already has the event id
            // from discard()'s return; only a finalisation failure is a failure to report.
            if (!ok && keep) onClipFailed(clip.target.eventId)
            return null
        }
        Log.i(TAG, "Clip ${clip.target.eventId} finished, ${durationUs / 1000} ms")
        val finished = FinishedClip(clip.target.eventId, clip.target.file, durationUs, clip.clipStartMs, continued)
        onClipFinished(finished)
        return finished
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
