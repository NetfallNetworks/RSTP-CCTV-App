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
 * (and, when the stream carries it, AAC) that RTSP clients get -- same orientation and
 * mirror fixes, no extra encode, no extra heat. It keeps the last few seconds of each track
 * in a [PreRollBuffer] / [AudioPreRollBuffer]; [trigger] opens an MP4 that starts with that
 * buffer, and each further trigger pushes the end out (see [ClipWindow]).
 *
 * Audio is opt-in at the stream level (see CctvServerService's audio setting, the
 * RECORD_AUDIO permission, and whether prepareAudio() actually succeeded -- permission alone
 * is not enough; see [setAudioExpected]'s KDoc), and RootEncoder only ever calls
 * [setAudioFormat] / [recordAudio] when the stream actually has an audio track.
 * [setAudioExpected] is how this class is told whether to wait for one before starting a
 * clip's muxer at all -- see [TrackReadiness]. When no audio is expected, clips record
 * exactly as before: video-only, no wait.
 *
 * Being told audio is coming does not guarantee it keeps coming -- a microphone can fail
 * silently after being reported ready, or the format callback can simply never fire despite
 * being asked for. [maybeDegradeAudio] and [AudioHealth] are the backstop for that: a
 * session that never gets an audio format within a bounded time falls back to recording
 * video-only rather than never starting a clip at all, and a clip only gets an audio track
 * when audio has actually been seen recently, not merely configured.
 *
 * To RootEncoder this is always an idle controller -- [isRecording] and [isRunning] are
 * false -- so stopStream() still tears the encoders down as normal.
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

        /** AAC at 64 kbps for a few seconds' pre-roll is tiny; this is generous headroom. */
        private const val MAX_AUDIO_BUFFER_BYTES = 2L * 1024 * 1024

        /**
         * Extra slack on top of [DEFAULT_PRE_ROLL_US] for the audio pre-roll window, to
         * match [PreRollBuffer]'s own worst case: it keeps preRollUs plus up to one whole
         * GOP (its KDoc), and the keyframe interval is user-configurable up to
         * EncoderProfile.MAX_KEYFRAME_INTERVAL_SECONDS. Without this slack,
         * [AudioPreRollBuffer.framesFrom] would come back empty for the first 0-2+ s of
         * every clip and rollover part whenever the video pre-roll's actual span (which
         * grows with the real GOP) exceeds what audio alone kept -- video with no audio at
         * the head. The byte ceiling ([MAX_AUDIO_BUFFER_BYTES]) still bounds actual memory.
         */
        private val AUDIO_PREROLL_SLACK_US = EncoderProfile.MAX_KEYFRAME_INTERVAL_SECONDS * 1_000_000L

        /**
         * How long to wait, after video starts, for an audio format before giving up and
         * recording video-only for this session (see [maybeDegradeAudio]). Also the
         * "is audio currently alive" freshness bound for adding a *new* clip's audio track
         * (see [AudioHealth.flowing] and its use in [startClip]): a MediaMuxer track added
         * but never given a single sample throws on stop(), so a clip should only get one
         * when audio looks alive right now, not merely "was configured".
         *
         * Same order as the pre-roll: ordinary microphone/encoder init latency is near
         * instant, so this never trips in the healthy case, while a real failure -- audio
         * expected but never actually arriving, or having silently stopped -- now costs at
         * most a few seconds of clips instead of the recording silently disappearing.
         */
        const val AUDIO_WAIT_TIMEOUT_US = DEFAULT_PRE_ROLL_US
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
        val videoTrack: Int,
        /** Null when this clip has no audio track (audio not expected for this stream). */
        val audioTrack: Int?,
        val window: ClipWindow,
        /** Wall-clock time of the first frame: now minus the pre-roll already buffered. */
        val clipStartMs: Long
    ) {
        var lastVideoPtsUs = Long.MIN_VALUE
        var lastAudioPtsUs = Long.MIN_VALUE
    }

    private val lock = Any()
    private val buffer = PreRollBuffer(preRollUs, MAX_BUFFER_BYTES)
    private val audioPreRoll = AudioPreRollBuffer(preRollUs + AUDIO_PREROLL_SLACK_US, MAX_AUDIO_BUFFER_BYTES)
    private var videoFormat: MediaFormat? = null
    private var audioFormat: MediaFormat? = null
    /**
     * Told explicitly by CctvServerService from the same decision that prepares (or
     * disables) audio on the stream itself -- see [setAudioExpected]. That decision is
     * intent plus a successful prepareAudio(), not just intent, but it can still go stale
     * mid-session if the microphone stops producing after the fact -- [maybeDegradeAudio]
     * is the backstop for that, not the primary mechanism: it only ever turns this false,
     * never guesses it true.
     */
    private var audioExpected = false
    /** Video pts of this encoder session's first frame; see [maybeDegradeAudio]. */
    private var videoSessionStartPtsUs: Long? = null
    /** So [maybeDegradeAudio] logs, and acts, only once per session. */
    private var audioDegraded = false
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
        if (!tracksReady() || buffer.framesFromKeyFrame().isEmpty()) return unavailable
        val target = open() ?: return unavailable
        if (startClip(target, nowUs)) return Trigger(Outcome.STARTED, target.eventId)
        onClipFailed(target.eventId)
        return unavailable
    }

    val activeEventId: String? get() = synchronized(lock) { active?.target?.eventId }

    /**
     * Declares whether this stream's audio will arrive at all, before any clip can start.
     * Call this with the *result* of prepareAudio(), not just the intent to use it --
     * RECORD_AUDIO being granted does not mean the microphone actually initialised
     * (Android 11's foreground-service while-in-use restriction can block it via the
     * app-op even with the permission present), and prepareAudio() reports that failure
     * in its return value. Passing intent alone here would leave [tracksReady] false
     * forever whenever the mic fails to come up, with no clip ever starting and nothing
     * else in the app noticing. Left at its default (false), clips record video-only,
     * exactly as before this class had audio support. See also [maybeDegradeAudio]: even a
     * `true` here is not trusted forever without evidence.
     */
    fun setAudioExpected(expected: Boolean) = synchronized(lock) {
        audioExpected = expected
    }

    /** See [TrackReadiness]. Caller must hold [lock]. */
    private fun tracksReady(): Boolean =
        TrackReadiness.ready(videoFormat != null, audioExpected, audioFormat != null)

    /**
     * The backstop for [audioExpected] being told true but never actually paying off --
     * prepareAudio() can succeed and the microphone still never yield a format (or a
     * session-long stream of samples). Once video has been running [AUDIO_WAIT_TIMEOUT_US]
     * with no audio format at all, give up on audio for this session: recording clips
     * video-only is strictly better than [tracksReady] staying false forever and no clip
     * ever starting. Only ever turns [audioExpected] off, once, and logs it once -- it does
     * not turn it back on; a genuinely new audio session arrives through [setAudioFormat]
     * or a fresh [setAudioExpected] call. Caller must hold [lock].
     */
    private fun maybeDegradeAudio(nowUs: Long) {
        if (!audioExpected || audioFormat != null || audioDegraded) return
        val startedUs = videoSessionStartPtsUs ?: return
        if (AudioHealth.formatTimedOut(nowUs - startedUs, AUDIO_WAIT_TIMEOUT_US)) {
            Log.w(
                TAG,
                "No audio format ${AUDIO_WAIT_TIMEOUT_US / 1000} ms after video started; " +
                    "recording video-only for this session"
            )
            audioExpected = false
            audioDegraded = true
        }
    }

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
        if (!tracksReady() || buffer.framesFromKeyFrame().isEmpty()) return Trigger(Outcome.UNAVAILABLE)
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
     *
     * The clip's whole presentation timeline is anchored at the video pre-roll's first
     * frame ([ClipWindow.startUs]) -- video and audio timestamps are both written as
     * `ptsUs - startUs`, one shared clock rather than independent per-track offsets, which
     * is what keeps the two from drifting apart across a long clip. Audio pre-roll is
     * trimmed to that same floor ([AudioPreRollBuffer.framesFrom]) so the clip never opens
     * with audio that precedes its first video frame.
     *
     * The audio track is only added when audio looks alive right now
     * ([AudioHealth.flowing]), not merely when it's expected: a track added to a
     * MediaMuxer but never given a single sample throws on stop() (see [finish]), so a
     * clip started while the microphone has gone quiet just gets video, the same as a
     * clip started when audio was never expected at all. [audioExpected] itself is left
     * alone here -- this is a per-clip decision, not a verdict that audio is gone for the
     * rest of the session (that's [maybeDegradeAudio]'s job).
     */
    private fun startClip(target: ClipTarget, triggeredAtUs: Long, holdUntilUs: Long = Long.MIN_VALUE): Boolean {
        val format = videoFormat ?: return false
        if (!tracksReady()) return false
        val frames = buffer.framesFromKeyFrame()
        if (frames.isEmpty()) return false
        val nowUs = buffer.newestPtsUs ?: frames.last().ptsUs
        val includeAudio = audioExpected && AudioHealth.flowing(audioPreRoll.newestPtsUs, nowUs, AUDIO_WAIT_TIMEOUT_US)

        var muxer: MediaMuxer? = null
        return try {
            muxer = MediaMuxer(target.file.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val videoTrack = muxer.addTrack(format)
            val audioTrack = if (includeAudio) muxer.addTrack(audioFormat!!) else null
            muxer.start()
            val startUs = frames.first().ptsUs
            val clipStartMs = System.currentTimeMillis() - (nowUs - startUs) / 1000
            val clip = ActiveClip(
                target, muxer, videoTrack, audioTrack,
                ClipWindow(postRollUs, maxClipUs, startUs, triggeredAtUs),
                clipStartMs
            )
            for (frame in frames) writeVideo(clip, frame)
            if (audioTrack != null) {
                for (frame in audioPreRoll.framesFrom(startUs)) writeAudio(clip, frame)
            }
            clip.window.hold(holdUntilUs)
            active = clip
            Log.i(
                TAG,
                "Clip ${target.eventId} started with ${frames.size} pre-roll frames" +
                    if (audioTrack != null) " (+audio)" else ""
            )
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
            if (videoSessionStartPtsUs == null) videoSessionStartPtsUs = frame.ptsUs
            maybeDegradeAudio(frame.ptsUs)
            val clip = active ?: return
            if (clip.window.isOver(frame.ptsUs)) {
                val finished = finish(clip, continued = clip.window.cutShort)
                if (finished != null && finished.continued) rollOver(finished, clip.window)
                return
            }
            try {
                writeVideo(clip, frame)
            } catch (e: Exception) {
                Log.e(TAG, "Write failed, closing clip ${clip.target.eventId}", e)
                finish(clip)
            }
        }
    }

    override fun recordAudio(audioBuffer: ByteBuffer, audioInfo: MediaCodec.BufferInfo) {
        if (audioInfo.size <= 0 || audioInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) return
        val source = audioBuffer.duplicate().apply { rewind() }
        val data = ByteArray(source.remaining())
        source.get(data)
        val frame = AudioPreRollBuffer.Frame(data, audioInfo.presentationTimeUs)

        synchronized(lock) {
            audioPreRoll.add(frame)
            // The window (end-of-clip, rollover) is driven entirely by video pts -- audio
            // just rides along on whichever clip video currently has open, or is dropped
            // (like a video frame arriving with nothing active) when there isn't one.
            val clip = active ?: return
            if (clip.audioTrack == null) return
            try {
                writeAudio(clip, frame)
            } catch (e: Exception) {
                Log.e(TAG, "Audio write failed, closing clip ${clip.target.eventId}", e)
                finish(clip)
            }
        }
    }

    override fun setVideoFormat(videoFormat: MediaFormat) {
        synchronized(lock) {
            // A new format means a new encoder session; frames from the old one can
            // neither start nor continue a clip in it. Audio goes with it too: a clip's
            // two tracks are added to one muxer together, so either format changing
            // invalidates whatever pre-roll is buffered for the other as well.
            active?.let { finish(it) }
            buffer.clear()
            audioPreRoll.clear()
            this.videoFormat = videoFormat
            // A new video session restarts maybeDegradeAudio's clock and gives audio a
            // fresh chance, rather than staying degraded from whatever the previous
            // session's mic did.
            videoSessionStartPtsUs = null
            audioDegraded = false
        }
    }

    override fun setAudioFormat(audioFormat: MediaFormat) {
        synchronized(lock) {
            active?.let { finish(it) }
            audioPreRoll.clear()
            this.audioFormat = audioFormat
            // A real format arriving is proof audio is available this session -- overrides
            // any earlier maybeDegradeAudio() guess that gave up too soon (a slow mic init
            // past AUDIO_WAIT_TIMEOUT_US, within the same video session). RootEncoder only
            // ever calls this when CctvServerService asked it to prepare audio in the first
            // place, so this can't contradict setAudioExpected(false).
            audioExpected = true
            audioDegraded = false
        }
    }

    /** Called by RootEncoder when the stream stops. */
    override fun resetFormats() {
        synchronized(lock) {
            active?.let { finish(it) }
            buffer.clear()
            audioPreRoll.clear()
            videoFormat = null
            audioFormat = null
            // Defensive: the next session's CctvServerService.setAudioExpected() call always
            // runs before any new format can arrive, so this shouldn't matter in practice --
            // but a stream that ends without a clean video format reset should not leave a
            // stale "expected" true wedging tracksReady() forever.
            audioExpected = false
            videoSessionStartPtsUs = null
            audioDegraded = false
        }
    }

    private fun writeVideo(clip: ActiveClip, frame: PreRollBuffer.Frame) {
        // MediaMuxer rejects non-increasing timestamps, per track.
        if (frame.ptsUs <= clip.lastVideoPtsUs) return
        val info = MediaCodec.BufferInfo().apply {
            set(
                0, frame.data.size, frame.ptsUs - clip.window.startUs,
                if (frame.isKeyFrame) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
            )
        }
        clip.muxer.writeSampleData(clip.videoTrack, ByteBuffer.wrap(frame.data), info)
        clip.lastVideoPtsUs = frame.ptsUs
    }

    private fun writeAudio(clip: ActiveClip, frame: AudioPreRollBuffer.Frame) {
        val track = clip.audioTrack ?: return
        // Same non-decreasing rule as video, tracked independently per track.
        if (frame.ptsUs <= clip.lastAudioPtsUs) return
        val info = MediaCodec.BufferInfo().apply {
            set(0, frame.data.size, frame.ptsUs - clip.window.startUs, 0)
        }
        clip.muxer.writeSampleData(track, ByteBuffer.wrap(frame.data), info)
        clip.lastAudioPtsUs = frame.ptsUs
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
        // Video remains the clip's canonical clock for window/rollover timing, but the
        // reported duration is the MP4's actual duration -- the max across tracks, the same
        // way a real MP4's overall duration is the longest of its per-track durations.
        // lastAudioPtsUs is Long.MIN_VALUE when there is no audio track (or it never got a
        // sample), which never wins against a real video pts.
        val durationUs = maxOf(clip.lastVideoPtsUs, clip.lastAudioPtsUs) - clip.window.startUs
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
