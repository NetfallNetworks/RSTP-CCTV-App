package com.zektopic.cctvapp

import android.media.MediaCodec
import android.media.MediaFormat
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
     * A clip that failed to start (trigger/startManual/rollover) or finalise (finish,
     * keep=true only -- Discard is intentional and the caller already has the event id
     * from [discard]'s return), with why and what had been written ([ClipFailure]). Also
     * called with a [FinishedClip.continued] part's own id when [onRollover] declined or
     * threw, since that recording has now ended after all. Called from inside [lock]; must
     * not call back into this ClipRecorder -- except for a clip whose rebuild from its journal
     * ended with only the journal left ([ClipFailure.journalKept]), which arrives from the
     * recovery thread instead, as does [onClipFinished] for a rebuilt clip. Both are safe to
     * post elsewhere. Default keeps other call sites (tests) compiling.
     */
    private val onClipFailed: (ClipFailure) -> Unit = {},
    /** Diagnostic only (see [AvSkewMonitor]); never influences timestamps or recording. */
    val avSkew: AvSkewMonitor = AvSkewMonitor(),
    /** Opens the file a clip is written to; tests substitute a fake. */
    private val muxerFactory: (File) -> ClipMuxer = { Mp4ClipMuxer(it.path) },
    /**
     * Journals every clip beside its MP4 and, when the MP4 cannot be finalised or fails
     * verification, rebuilds it from the journal on [ClipSafety.executor]. Null: no journal,
     * and a finalise failure loses the clip as it always did.
     */
    private val safety: ClipSafety? = null
) : RecordController {

    companion object {
        private const val TAG = "ClipRecorder"
        private const val AV_SKEW_LOG_INTERVAL_US = 30_000_000L
        /**
         * Ten seconds, so a clip that starts late (a slow detection pass, a busy device) still
         * opens before the subject arrived. About 1.5 MB of RAM at frankie's stream bitrate.
         */
        const val DEFAULT_PRE_ROLL_US = 10_000_000L

        /**
         * Long, because animals move slowly and in bursts -- a cat can sit still for half
         * a minute between steps. Ending a clip in that pause cuts the part worth seeing.
         */
        const val DEFAULT_POST_ROLL_US = 60_000_000L

        /** Per file, not per event: activity beyond it continues in a rollover clip. */
        const val DEFAULT_MAX_CLIP_US = 600_000_000L

        /** Ceiling for the pre-roll; several times what 10 s at stream bitrates needs. */
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
         * Deliberately its own figure, not the pre-roll: it was once defined as the pre-roll,
         * and lengthening the pre-roll would have silently made a quiet microphone count as
         * alive for twice as long. Ordinary microphone/encoder init latency is near
         * instant, so this never trips in the healthy case, while a real failure -- audio
         * expected but never actually arriving, or having silently stopped -- now costs at
         * most a few seconds of clips instead of the recording silently disappearing.
         */
        const val AUDIO_WAIT_TIMEOUT_US = 5_000_000L
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
        val continued: Boolean = false,
        val hadAudioTrack: Boolean = false, val videoSamples: Int = 0, val audioSamples: Int = 0,
        /**
         * Set when the clip did not finalise cleanly and was rebuilt from its journal (this
         * is then the cause), or [damaged]. Such a clip is reported from the recovery thread.
         */
        val recoveredFrom: ClipFailure? = null,
        /**
         * The MP4 finalised but failed verification and could not be rebuilt either: it is
         * attached as it is, and its journal (the raw data) is kept beside it.
         */
        val damaged: Boolean = false
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
        val muxer: ClipMuxer,
        val videoTrack: Int,
        /** Null when this clip has no audio track (audio not expected for this stream). */
        val audioTrack: Int?,
        val window: ClipWindow,
        /** Wall-clock time of the first frame: now minus the pre-roll already buffered. */
        val clipStartMs: Long,
        /** Null when journaling is off or its file could not be opened. */
        val journalFile: File? = null,
        var journal: ClipJournalWriter? = null
    ) {
        var lastVideoPtsUs = Long.MIN_VALUE
        var lastAudioPtsUs = Long.MIN_VALUE
        /** Samples actually handed to the muxer, for a failure report. */
        var videoSamples = 0
        var audioSamples = 0
        /** A journal write failed: the journal stops short of the clip and cannot vouch for it. */
        var journalBroken = false

        /** How much of the clip's timeline has been written; 0 before the first sample. */
        fun recordedUs(): Long {
            val last = maxOf(lastVideoPtsUs, lastAudioPtsUs)
            return if (last == Long.MIN_VALUE) 0 else last - window.startUs
        }
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

    private var clipsStarted = 0L
    private var clipsFinalised = 0L
    private var clipFailures = 0L
    private var lastFailure: ClipFailure? = null
    private var maxStartHoldMs = 0L
    private var clipsRecovered = 0L

    private val recovery: ClipRecovery? =
        safety?.let { ClipRecovery(JournalReplay(muxerFactory, it.formats), it.verifier) }

    /** Counters and the last lost clip, for /status. */
    fun stats(): ClipStats = synchronized(lock) {
        ClipStats(clipsStarted, clipsFinalised, clipFailures, lastFailure, maxStartHoldMs, clipsRecovered)
    }

    /**
     * Runs a clip start (the caller's event creation included) and keeps the longest time it
     * took, since all of it happens inside [lock] and the encoder callbacks wait on that lock.
     * Caller must hold [lock].
     */
    private inline fun <T> timedStart(block: () -> T): T {
        val startedNs = System.nanoTime()
        try {
            return block()
        } finally {
            val heldMs = (System.nanoTime() - startedNs) / 1_000_000
            if (heldMs > maxStartHoldMs) maxStartHoldMs = heldMs
        }
    }

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
        return timedStart {
            val target = open() ?: return@timedStart unavailable
            // A failed start has already been reported through onClipFailed.
            if (startClip(target, nowUs)) Trigger(Outcome.STARTED, target.eventId) else unavailable
        }
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
        return timedStart {
            val target = open() ?: return@timedStart Trigger(Outcome.UNAVAILABLE)
            // A manual clip's detection end is "now": the hold is what keeps it running.
            if (startClip(target, nowUs - postRollUs, untilUs)) Trigger(Outcome.STARTED, target.eventId)
            else Trigger(Outcome.UNAVAILABLE)
        }
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
     * ([AudioHealth.flowing]) and there is at least one audio frame to start it with, not
     * merely when it's expected: a track added to a MediaMuxer but never given a single
     * sample throws on stop() (see [finish]), so a clip started while the microphone has
     * gone quiet just gets video, the same as a clip started when audio was never expected
     * at all. [audioExpected] itself is left
     * alone here -- this is a per-clip decision, not a verdict that audio is gone for the
     * rest of the session (that's [maybeDegradeAudio]'s job).
     */
    private fun startClip(target: ClipTarget, triggeredAtUs: Long, holdUntilUs: Long = Long.MIN_VALUE): Boolean {
        val format = videoFormat
        val frames = buffer.framesFromKeyFrame()
        if (format == null || !tracksReady() || frames.isEmpty()) {
            fail(target.eventId, ClipFailureReason.START_FAILED, "nothing to start a clip from", 0, false, 0, 0)
            return false
        }
        val nowUs = buffer.newestPtsUs ?: frames.last().ptsUs
        val startUs = frames.first().ptsUs
        // Worked out before the track is added: alive audio can still have nothing at or after
        // the video pre-roll's first frame (just after a stream restart, or audio running late),
        // and an audio track that never gets a sample makes stop() throw, losing the whole clip.
        val audioFrames =
            if (audioExpected && AudioHealth.flowing(audioPreRoll.newestPtsUs, nowUs, AUDIO_WAIT_TIMEOUT_US)) {
                audioPreRoll.framesFrom(startUs)
            } else {
                emptyList()
            }

        var muxer: ClipMuxer? = null
        var journal: ClipJournalWriter? = null
        var audioAdded = false
        return try {
            muxer = muxerFactory(target.file)
            val videoTrack = muxer.addTrack(format)
            val audioTrack = if (audioFrames.isNotEmpty()) muxer.addTrack(audioFormat!!) else null
            audioAdded = audioTrack != null
            muxer.start()
            val clipStartMs = System.currentTimeMillis() - (nowUs - startUs) / 1000
            journal = openJournal(target, format, if (audioTrack != null) audioFormat else null, clipStartMs)
            val clip = ActiveClip(
                target, muxer, videoTrack, audioTrack,
                ClipWindow(postRollUs, maxClipUs, startUs, triggeredAtUs),
                clipStartMs,
                journalFile = journal?.file, journal = journal
            )
            for (frame in frames) writeVideo(clip, frame)
            if (audioTrack != null) {
                for (frame in audioFrames) writeAudio(clip, frame)
            }
            clip.window.hold(holdUntilUs)
            active = clip
            clipsStarted++
            Log.i(
                TAG,
                "Clip ${target.eventId} started with ${frames.size} pre-roll frames" +
                    if (audioTrack != null) " (+audio)" else ""
            )
            true
        } catch (e: Exception) {
            Log.e(TAG, "Could not start clip ${target.eventId}", e)
            try { muxer?.release() } catch (_: Exception) {}
            // A clip that never started has nothing to rebuild: no journal either.
            journal?.close()
            if (safety != null) journalFileFor(target.file).delete()
            target.file.delete()
            fail(target.eventId, ClipFailureReason.START_FAILED, ClipFailure.detailOf(e), 0, audioAdded, 0, 0)
            false
        }
    }

    /**
     * Opens the clip's journal with its track description, or null if journaling is off or the
     * file cannot be created (the clip then records as it always did, without a safety net).
     */
    private fun openJournal(
        target: ClipTarget, video: MediaFormat, audio: MediaFormat?, clipStartMs: Long
    ): ClipJournalWriter? {
        val s = safety ?: return null
        return try {
            ClipJournalWriter(
                journalFileFor(target.file),
                JournalHeader(clipStartMs, s.formats.videoInfo(video), audio?.let { s.formats.audioInfo(it) })
            )
        } catch (e: Exception) {
            Log.e(TAG, "Could not open the journal for clip ${target.eventId}; recording without one", e)
            journalFileFor(target.file).delete()
            null
        }
    }

    /**
     * Counts a lost clip and records why, without telling [onClipFailed]. Caller must hold [lock].
     */
    private fun recordFailure(
        eventId: String, reason: ClipFailureReason, message: String?, recordedMs: Long,
        hadAudioTrack: Boolean, videoSamples: Int, audioSamples: Int
    ): ClipFailure {
        val failure = ClipFailure(eventId, reason, message, recordedMs, hadAudioTrack, videoSamples, audioSamples)
        clipFailures++
        lastFailure = failure
        Log.w(TAG, "Clip $eventId lost: $reason after ${recordedMs} ms ($message)")
        return failure
    }

    /** [recordFailure], then reports it ([onClipFailed]). Caller must hold [lock]. */
    private fun fail(
        eventId: String, reason: ClipFailureReason, message: String?, recordedMs: Long,
        hadAudioTrack: Boolean, videoSamples: Int, audioSamples: Int
    ) {
        onClipFailed(recordFailure(eventId, reason, message, recordedMs, hadAudioTrack, videoSamples, audioSamples))
    }

    override fun recordVideo(videoBuffer: ByteBuffer, videoInfo: MediaCodec.BufferInfo) {
        val arrivalUs = System.nanoTime() / 1000
        if (videoInfo.size <= 0 || videoInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) return
        // Same read as RootEncoder's own muxer controller: the whole buffer, from zero.
        val source = videoBuffer.duplicate().apply { rewind() }
        val data = ByteArray(source.remaining())
        source.get(data)
        val frame = PreRollBuffer.Frame(data, videoInfo.presentationTimeUs, isKeyFrame(data, videoInfo))
        avSkew.onVideo(videoInfo.presentationTimeUs, arrivalUs)?.let { Log.i("AvSkew", "AvSkew session ${it.toLogLine()}") }
        logAvSkewIfDue(arrivalUs)
        onVideoFrame(frame)
    }

    /** A video frame from the encoder, parsed; split from [recordVideo] so tests can drive it. */
    internal fun onVideoFrame(frame: PreRollBuffer.Frame) {
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
                finish(clip, writeError = e)
            }
        }
    }

    override fun recordAudio(audioBuffer: ByteBuffer, audioInfo: MediaCodec.BufferInfo) {
        val arrivalUs = System.nanoTime() / 1000
        if (audioInfo.size <= 0 || audioInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) return
        val source = audioBuffer.duplicate().apply { rewind() }
        val data = ByteArray(source.remaining())
        source.get(data)
        val frame = AudioPreRollBuffer.Frame(data, audioInfo.presentationTimeUs)
        avSkew.onAudio(audioInfo.presentationTimeUs, arrivalUs)?.let { Log.i("AvSkew", "AvSkew session ${it.toLogLine()}") }
        onAudioFrame(frame)
    }

    /** An audio frame from the encoder, parsed; split from [recordAudio] so tests can drive it. */
    internal fun onAudioFrame(frame: AudioPreRollBuffer.Frame) {
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
                finish(clip, writeError = e)
            }
        }
    }

    @Volatile private var lastAvSkewLogUs = 0L

    /** One "AvSkew" log line per 30 s of arrival time, driven by the video callback. */
    private fun logAvSkewIfDue(arrivalUs: Long) {
        val last = lastAvSkewLogUs
        if (last != 0L && arrivalUs - last < AV_SKEW_LOG_INTERVAL_US) return
        lastAvSkewLogUs = arrivalUs
        if (last != 0L) Log.i("AvSkew", avSkew.snapshot().toLogLine())
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

    /**
     * Appends to the clip's journal, if it has one. A journal that cannot be written must never
     * cost the clip: it is dropped (and can then no longer vouch for the MP4) and recording goes on.
     */
    private fun journalSample(clip: ActiveClip, isVideo: Boolean, relPtsUs: Long, keyFrame: Boolean, data: ByteArray) {
        val journal = clip.journal ?: return
        try {
            journal.append(isVideo, relPtsUs, keyFrame, data)
        } catch (e: Exception) {
            Log.e(TAG, "Journal write failed for clip ${clip.target.eventId}; continuing without it", e)
            clip.journalBroken = true
            clip.journal = null
            journal.close()
        }
    }

    private fun writeVideo(clip: ActiveClip, frame: PreRollBuffer.Frame) {
        // MediaMuxer rejects non-increasing timestamps, per track.
        if (frame.ptsUs <= clip.lastVideoPtsUs) return
        // Journal first: if the muxer write throws, the sample is still in the journal.
        journalSample(clip, true, frame.ptsUs - clip.window.startUs, frame.isKeyFrame, frame.data)
        clip.muxer.writeSample(clip.videoTrack, frame.data, frame.ptsUs - clip.window.startUs, frame.isKeyFrame)
        clip.lastVideoPtsUs = frame.ptsUs
        clip.videoSamples++
    }

    private fun writeAudio(clip: ActiveClip, frame: AudioPreRollBuffer.Frame) {
        val track = clip.audioTrack ?: return
        // Same non-decreasing rule as video, tracked independently per track.
        if (frame.ptsUs <= clip.lastAudioPtsUs) return
        journalSample(clip, false, frame.ptsUs - clip.window.startUs, false, frame.data)
        clip.muxer.writeSample(track, frame.data, frame.ptsUs - clip.window.startUs, false)
        clip.lastAudioPtsUs = frame.ptsUs
        clip.audioSamples++
    }

    /**
     * Continues a clip that hit the length cap while still wanted, in a new file that
     * runs to where the old one would have ended. Caller must hold [lock].
     */
    private fun rollOver(previous: FinishedClip, window: ClipWindow) = timedStart<Unit> {
        var declinedBecause = "no continuation target"
        val target = try {
            onRollover(previous)
        } catch (e: Exception) {
            Log.e(TAG, "Rollover after ${previous.eventId} failed", e)
            declinedBecause = ClipFailure.detailOf(e)
            null
        }
        if (target == null) {
            // No continuation after all: [previous] was reported continued (held), so it
            // must be reported again or its recording would never end.
            fail(
                previous.eventId, ClipFailureReason.ROLLOVER_DECLINED, declinedBecause,
                previous.durationUs / 1000, previous.hadAudioTrack, previous.videoSamples, previous.audioSamples
            )
        } else {
            // A continuation that cannot be opened reports itself, from startClip.
            startClip(target, window.detectionEndUs - postRollUs, window.holdUntilUs)
        }
    }

    /**
     * Ends [clip]. keep=false deletes the file (and journal) instead of reporting it (Discard).
     * [continued]: the caller will open a continuation part next (see
     * [FinishedClip.continued]); only the natural end at the per-file cap passes true.
     * Returns the finished clip only when kept and finalised and (with a journal) verified.
     * A clip that did not finalise, or failed verification, is handed to [rebuild] when it has
     * a journal and is reported later from the recovery thread; with no journal it is lost, as
     * before. Caller must hold [lock].
     */
    private fun finish(
        clip: ActiveClip, keep: Boolean = true, continued: Boolean = false, writeError: Exception? = null
    ): FinishedClip? {
        active = null
        // Video remains the clip's canonical clock for window/rollover timing, but the
        // reported duration is the MP4's actual duration -- the max across tracks, the same
        // way a real MP4's overall duration is the longest of its per-track durations.
        // lastAudioPtsUs is Long.MIN_VALUE when there is no audio track (or it never got a
        // sample), which never wins against a real video pts.
        val durationUs = maxOf(clip.lastVideoPtsUs, clip.lastAudioPtsUs) - clip.window.startUs
        val journalSpanUs = clip.journal?.spanUs ?: 0L
        clip.journal?.close()
        val stopError: Exception? = try {
            clip.muxer.stop()
            null
        } catch (e: Exception) {
            Log.e(TAG, "Could not finalise clip ${clip.target.eventId}", e)
            e
        } finally {
            try { clip.muxer.release() } catch (_: Exception) {}
        }
        val journalFile = clip.journalFile
        if (!keep) {
            // Discard is intentional and the caller already has the event id from discard()'s
            // return, so there is nothing to report and nothing worth rebuilding.
            clip.target.file.delete()
            journalFile?.delete()
            return null
        }
        // The journal is the source of truth: it goes only once the MP4 has been reopened and
        // matches it. A journal that stopped short (its own write failed) cannot vouch for the MP4.
        val usableJournal = journalFile != null && safety != null && !clip.journalBroken
        var verifyError: String? = null
        if (stopError == null && usableJournal) {
            verifyError = safety!!.verifier.verify(
                clip.target.file, ClipExpectation(if (clip.audioTrack != null) 2 else 1, journalSpanUs)
            )
            Log.i(TAG, "Clip ${clip.target.eventId} verification: ${verifyError ?: "ok"}")
        }
        if (stopError == null && verifyError == null) {
            journalFile?.delete()
            Log.i(TAG, "Clip ${clip.target.eventId} finished, ${durationUs / 1000} ms")
            clipsFinalised++
            val finished = FinishedClip(
                clip.target.eventId, clip.target.file, durationUs, clip.clipStartMs, continued,
                clip.audioTrack != null, clip.videoSamples, clip.audioSamples
            )
            onClipFinished(finished)
            return finished
        }

        // A write error that started this is the root cause, so it is what gets reported; had
        // the file still finalised and verified, the partial clip would have been kept instead.
        val reason = if (writeError != null) ClipFailureReason.WRITE_FAILED else ClipFailureReason.FINALISE_FAILED
        val message = (writeError ?: stopError)?.let { ClipFailure.detailOf(it) } ?: "verification failed: $verifyError"
        val failure = recordFailure(
            clip.target.eventId, reason, message, clip.recordedUs() / 1000,
            clip.audioTrack != null, clip.videoSamples, clip.audioSamples
        )
        if (stopError != null) clip.target.file.delete()
        if (journalFile == null || safety == null) {
            onClipFailed(failure)
            return null
        }
        // The journal holds every sample: rebuild from it off this thread, and keep the event
        // recording (so the archive skips it) until that has been decided.
        rebuild(failure, journalFile, clip.target.file, clip.clipStartMs, durationUs, originalUsable = stopError == null)
        return null
    }

    /**
     * Rebuilds a clip that failed to finalise or verify from its journal on [ClipSafety.executor],
     * then reports the outcome: [onClipFinished] with [FinishedClip.recoveredFrom] when a rebuilt
     * (or, if the rebuild failed, the unverified original with [FinishedClip.damaged]) clip is
     * attached, or [onClipFailed] with [ClipFailure.journalKept] when only the journal is left.
     */
    private fun rebuild(
        failure: ClipFailure, journal: File, target: File, clipStartMs: Long, originalDurationUs: Long,
        originalUsable: Boolean
    ) {
        val s = safety ?: return
        val recovery = recovery ?: return
        val originalAudio = failure.hadAudioTrack
        val originalVideoSamples = failure.videoSamples
        val originalAudioSamples = failure.audioSamples
        s.executor.execute {
            try {
                val result = recovery.recover(journal, target)
                val scan = result.scan
                if (result.rebuilt && scan != null) {
                    synchronized(lock) { clipsRecovered++ }
                    onClipFinished(
                        FinishedClip(
                            failure.eventId, target,
                            if (result.hadAudioTrack) scan.lastPtsUs else scan.videoLastPtsUs, clipStartMs,
                            hadAudioTrack = result.hadAudioTrack,
                            videoSamples = result.videoSamples, audioSamples = result.audioSamples,
                            recoveredFrom = failure
                        )
                    )
                } else if (originalUsable) {
                    // Finalised but unverifiable and unrebuildable: better attached than gone.
                    onClipFinished(
                        FinishedClip(
                            failure.eventId, target, originalDurationUs, clipStartMs,
                            hadAudioTrack = originalAudio,
                            videoSamples = originalVideoSamples, audioSamples = originalAudioSamples,
                            recoveredFrom = failure, damaged = true
                        )
                    )
                } else {
                    onClipFailed(failure.withJournalKept())
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Recovery of clip ${failure.eventId} crashed; its journal is kept", t)
                onClipFailed(failure.withJournalKept())
            }
        }
    }

    /**
     * Rebuilds the clip an interrupted run left behind: [journal] is the only copy of it. Runs on
     * [ClipSafety.executor] and reports like a live failure would (reason [ClipFailureReason.INTERRUPTED]).
     * A journal that cannot be read is deleted; anything else it cannot rebuild stays on disk.
     */
    fun rebuildInterrupted(eventId: String, target: File, journal: File) {
        val s = safety ?: return
        val recovery = recovery ?: return
        s.executor.execute {
            try {
                val result = recovery.recover(journal, target)
                val scan = result.scan
                val usable = scan != null && scan.videoSamples > 0
                val failure = ClipFailure(
                    eventId, ClipFailureReason.INTERRUPTED,
                    "recording was interrupted (process or device stopped)",
                    (scan?.spanUs ?: 0L) / 1000, scan?.header?.audio != null,
                    scan?.videoSamples ?: 0, scan?.audioSamples ?: 0
                )
                synchronized(lock) {
                    clipFailures++
                    lastFailure = failure
                    if (result.rebuilt) clipsRecovered++
                }
                if (result.rebuilt && scan != null) {
                    onClipFinished(
                        FinishedClip(
                            eventId, target, if (result.hadAudioTrack) scan.lastPtsUs else scan.videoLastPtsUs,
                            scan.header.clipStartMs, hadAudioTrack = result.hadAudioTrack,
                            videoSamples = result.videoSamples, audioSamples = result.audioSamples,
                            recoveredFrom = failure
                        )
                    )
                } else if (usable) {
                    onClipFailed(failure.withJournalKept())
                } else {
                    journal.delete()
                    onClipFailed(failure)
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Recovery of interrupted clip $eventId crashed; its journal is kept", t)
                onClipFailed(ClipFailure(eventId, ClipFailureReason.INTERRUPTED, ClipFailure.detailOf(t), 0, false, 0, 0).withJournalKept())
            }
        }
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
