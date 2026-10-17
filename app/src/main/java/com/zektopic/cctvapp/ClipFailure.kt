package com.zektopic.cctvapp

/** Why a clip was lost. Stored on the event by name, so only ever append values. */
enum class ClipFailureReason {
    /** The muxer could not be opened or the pre-roll could not be written. */
    START_FAILED,
    /** `MediaMuxer.stop()` threw, so the whole file (which has no index until then) was dropped. */
    FINALISE_FAILED,
    /** A sample write threw mid-clip. */
    WRITE_FAILED,
    /** The per-file cap was reached, the previous part was kept, and no continuation could be opened. */
    ROLLOVER_DECLINED,
    /** The process or device stopped mid-clip; found at the next start from the journal it left. */
    INTERRUPTED,
    /**
     * The file finalised but the verifier disagreed, and so did a rebuild from the journal. The
     * clip was kept as it is: not a loss, but not vouched for either.
     */
    UNVERIFIED
}

/**
 * What [ClipRecorder] knew about a clip at the moment it was lost: the only record of why
 * (the exception itself goes to logcat, whose ring buffer is gone within minutes).
 * [recordedMs] is how long the clip had been recording, and the sample counts show whether
 * anything reached the file before the failure -- notably an audio track with no audio
 * samples, which MediaMuxer refuses to finalise.
 */
class ClipFailure(
    val eventId: String,
    val reason: ClipFailureReason,
    /** Exception class and message, truncated; see [detailOf]. Null when there was no exception. */
    val message: String?,
    val recordedMs: Long,
    val hadAudioTrack: Boolean,
    val videoSamples: Int,
    val audioSamples: Int,
    val atMs: Long = System.currentTimeMillis(),
    /** The clip could not be rebuilt either, and its journal (the raw data) was left on disk. */
    val journalKept: Boolean = false,
    /**
     * The lost clip was a part of a recording that carries on in a continuation part (it hit the
     * per-file cap), so the recording has not ended and its earlier parts stay held.
     */
    val continued: Boolean = false
) {
    /** The same failure, now known to have ended with only the journal left. */
    fun withJournalKept() = ClipFailure(
        eventId, reason, message, recordedMs, hadAudioTrack, videoSamples, audioSamples, atMs,
        journalKept = true, continued = continued
    )

    fun toClipError() = ClipError(
        reason = reason.name, detail = message, recordedMs = recordedMs,
        audioTrack = hadAudioTrack, videoSamples = videoSamples, audioSamples = audioSamples
    )

    /** The /status `lastClipFailure` object. */
    fun toJson(): String = org.json.JSONObject()
        .put("atMs", atMs).put("eventId", eventId).put("reason", reason.name)
        .put("message", message ?: org.json.JSONObject.NULL)
        .put("recordedMs", recordedMs).put("audioTrack", hadAudioTrack)
        .put("videoSamples", videoSamples).put("audioSamples", audioSamples)
        .toString()

    companion object {
        const val MAX_DETAIL_CHARS = 200

        /** "IllegalStateException: Failed to stop the muxer", cut to [MAX_DETAIL_CHARS]. */
        fun detailOf(e: Throwable): String {
            val text = e.message?.let { "${e.javaClass.simpleName}: $it" } ?: e.javaClass.simpleName
            return if (text.length <= MAX_DETAIL_CHARS) text else text.substring(0, MAX_DETAIL_CHARS - 1) + "…"
        }
    }
}

/**
 * The safety net failing quietly would be worse than not having one: a clip that could not be
 * journaled (kind "open") or whose journal stopped short (kind "write") is counted and kept here.
 */
class JournalProblem(val atMs: Long, val eventId: String, val kind: String, val reason: String) {
    fun toJson(): String = org.json.JSONObject()
        .put("atMs", atMs).put("eventId", eventId).put("kind", kind).put("reason", reason).toString()
}

/** Counters and the last loss, for /status. Counts every clip part, not every visit. */
class ClipStats(
    /** Parts opened successfully, including rollover continuations. */
    val clipsStarted: Long = 0,
    /** Parts finalised and kept. A discarded part is neither this nor a failure. */
    val clipsFinalised: Long = 0,
    /** Parts lost: see [ClipFailureReason]. */
    val clipFailures: Long = 0,
    val lastFailure: ClipFailure? = null,
    /** Longest time one clip start held the recorder's lock, ms; the stall encoder callbacks saw. */
    val maxStartHoldMs: Long = 0,
    /** Failed clips that were then rebuilt from their journal and kept (also counted in [clipFailures]). */
    val clipsRecovered: Long = 0,
    /** Clips kept as they finalised although verification (and a rebuild) disagreed; see [ClipFailureReason.UNVERIFIED]. */
    val clipsUnverified: Long = 0,
    /** Clips recorded with no journal because it could not be opened (or its header built). */
    val journalOpenFailures: Long = 0,
    /** Clips whose journal stopped short because a write to it failed. */
    val journalWriteFailures: Long = 0,
    val lastJournalProblem: JournalProblem? = null
) {
    /** The /status keys, without surrounding braces, ready to splice into its object. */
    fun toJsonFields(): String =
        "\"clipsStarted\":$clipsStarted,\"clipsFinalised\":$clipsFinalised,\"clipFailures\":$clipFailures," +
            "\"clipsRecovered\":$clipsRecovered,\"clipsUnverified\":$clipsUnverified," +
            "\"journalOpenFailures\":$journalOpenFailures,\"journalWriteFailures\":$journalWriteFailures," +
            "\"lastJournalProblem\":${lastJournalProblem?.toJson() ?: "null"}," +
            "\"clipStartMaxHoldMs\":$maxStartHoldMs,\"lastClipFailure\":${lastFailure?.toJson() ?: "null"}"
}
