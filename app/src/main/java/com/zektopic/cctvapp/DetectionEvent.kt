package com.zektopic.cctvapp

import org.json.JSONObject

data class DetectionEvent(
    val id: String,
    val type: String,
    val score: Double?,
    val startTimeMs: Long,
    val endTimeMs: Long?,
    val snapshotFileName: String?,
    val clipFileName: String?,
    val createdAtMs: Long,
    /**
     * Optional one-line description of the snapshot, produced on-device by Gemini Nano.
     * Null on the great majority of devices, which have no AICore -- treat it as a
     * garnish that may simply be absent, never as something to depend on.
     */
    val caption: String? = null,
    /**
     * Every label seen while this event's clip recorded ("motion", "animal", "person"),
     * so a clip that began as plain motion can still be found by what turned up in it.
     */
    val tags: List<String> = emptyList(),
    /** Length of the recorded clip. Not end minus start: a clip includes its pre-roll. */
    val clipDurationMs: Long? = null,
    /** True while this event's clip is still being written; the archive skips these. */
    val recording: Boolean = false,
    /** Final clip size, so a copy can be verified. */
    val clipBytes: Long? = null,
    /** Wall-clock time of the clip's first frame (it opens with pre-roll, before [startTimeMs]). */
    val clipStartMs: Long? = null,
    /** JSON array of detections during the clip: {t, label, score, box}. See ClipTimeline. */
    val detectionsJson: String? = null,
    /** JSON object {start_ms, step_ms, permille[]}: motion per detection pass. */
    val activityJson: String? = null,
    /**
     * The id of the first part of the recording this clip belongs to, set on every part
     * including the first. A recording longer than the per-file cap is saved as consecutive
     * parts, each its own event; this is what ties them together, so Discard removes them
     * all and the archive takes them only once the recording has ended. Null on
     * snapshot-only events.
     */
    val visitId: String? = null,
    /**
     * Why this event's clip is missing or cut short, when [ClipRecorder] lost it. Null on every
     * event that recorded normally, and on events written before this was kept.
     */
    val clipError: ClipError? = null,
    /** The clip failed to finalise cleanly and was rebuilt from its journal; [clipError] is why. */
    val clipRecovered: Boolean = false,
    /**
     * The raw data of a clip that could not be finalised or rebuilt is kept in its journal
     * (`<id>_clip.journal`). Such an event is never archived, which would delete it.
     */
    val clipJournalKept: Boolean = false
) {
    companion object {
        /** Most specific first; an event's [type] is the highest of its tags. */
        val TYPE_PRIORITY = listOf("person", "animal", "manual", "motion", "test")

        fun fromJsonObject(obj: JSONObject): DetectionEvent {
            val tagsArray = obj.optJSONArray("tags")
            val tags = if (tagsArray == null) emptyList()
                else (0 until tagsArray.length()).map { tagsArray.optString(it) }
            return DetectionEvent(
                id = obj.getString("id"),
                type = obj.optString("type", "unknown"),
                score = if (obj.has("score")) obj.optDouble("score") else null,
                startTimeMs = obj.optLong("start_time", 0L),
                endTimeMs = if (obj.has("end_time")) obj.optLong("end_time") else null,
                snapshotFileName = if (obj.has("snapshot")) obj.optString("snapshot") else null,
                clipFileName = if (obj.has("clip")) obj.optString("clip") else null,
                createdAtMs = obj.optLong("created_at", System.currentTimeMillis()),
                caption = if (obj.has("caption")) obj.optString("caption") else null,
                tags = tags,
                clipDurationMs = if (obj.has("clip_duration_ms")) obj.optLong("clip_duration_ms") else null,
                recording = obj.optBoolean("recording", false),
                clipBytes = if (obj.has("clip_bytes")) obj.optLong("clip_bytes") else null,
                clipStartMs = if (obj.has("clip_start_ms")) obj.optLong("clip_start_ms") else null,
                detectionsJson = obj.optJSONArray("detections")?.toString(),
                activityJson = obj.optJSONObject("activity")?.toString(),
                visitId = if (obj.has("visit_id")) obj.optString("visit_id") else null,
                clipError = if (obj.has("clip_error")) ClipError(
                    reason = obj.optString("clip_error"),
                    detail = if (obj.has("clip_error_detail")) obj.optString("clip_error_detail") else null,
                    recordedMs = obj.optLong("clip_error_duration_ms", 0L),
                    audioTrack = obj.optBoolean("clip_error_audio", false),
                    videoSamples = obj.optInt("clip_error_video_samples", 0),
                    audioSamples = obj.optInt("clip_error_audio_samples", 0)
                ) else null,
                clipRecovered = obj.optBoolean("clip_recovered", false),
                clipJournalKept = obj.optBoolean("clip_journal_kept", false)
            )
        }
    }

    /** This event with [tag] added, its [type] raised if [tag] is more specific. */
    fun withTag(tag: String): DetectionEvent {
        if (tag in tags && type == tag) return this
        val newTags = if (tag in tags) tags else tags + tag
        val rank = { t: String -> TYPE_PRIORITY.indexOf(t).let { if (it < 0) Int.MAX_VALUE else it } }
        val newType = if (rank(tag) < rank(type)) tag else type
        return copy(tags = newTags, type = newType)
    }

    fun toJsonObject(): JSONObject {
        val obj = JSONObject()
        obj.put("id", id)
        obj.put("type", type)
        if (score != null) obj.put("score", score)
        obj.put("start_time", startTimeMs)
        if (endTimeMs != null) obj.put("end_time", endTimeMs)
        if (snapshotFileName != null) obj.put("snapshot", snapshotFileName)
        if (clipFileName != null) obj.put("clip", clipFileName)
        obj.put("has_snapshot", snapshotFileName != null)
        obj.put("has_clip", clipFileName != null)
        if (caption != null) obj.put("caption", caption)
        obj.put("tags", org.json.JSONArray(tags))
        if (clipDurationMs != null) obj.put("clip_duration_ms", clipDurationMs)
        obj.put("recording", recording)
        if (clipBytes != null) obj.put("clip_bytes", clipBytes)
        if (clipStartMs != null) obj.put("clip_start_ms", clipStartMs)
        if (detectionsJson != null) obj.put("detections", org.json.JSONArray(detectionsJson))
        if (activityJson != null) obj.put("activity", JSONObject(activityJson))
        if (visitId != null) obj.put("visit_id", visitId)
        if (clipError != null) {
            obj.put("clip_error", clipError.reason)
            if (clipError.detail != null) obj.put("clip_error_detail", clipError.detail)
            obj.put("clip_error_duration_ms", clipError.recordedMs)
            obj.put("clip_error_audio", clipError.audioTrack)
            obj.put("clip_error_video_samples", clipError.videoSamples)
            obj.put("clip_error_audio_samples", clipError.audioSamples)
            // Derived, for the /clips page; not read back.
            obj.put("clip_error_summary", when {
                clipRecovered -> "recovered from journal after ${clipError.summary()}"
                clipJournalKept -> "clip damaged, raw data kept (${clipError.summary()})"
                else -> clipError.summary()
            })
        }
        if (clipRecovered) obj.put("clip_recovered", true)
        if (clipJournalKept) obj.put("clip_journal_kept", true)
        obj.put("created_at", createdAtMs)
        return obj
    }
}

/**
 * A lost clip, as stored on its event. [reason] is a [ClipFailureReason] name kept as text, so
 * an event written by a newer build still loads and shows.
 */
data class ClipError(
    val reason: String,
    val detail: String?,
    val recordedMs: Long,
    val audioTrack: Boolean,
    val videoSamples: Int,
    val audioSamples: Int
) {
    /** For example "finalise failed after 9m12s (audio track, 0 audio samples, 16000 video samples)". */
    fun summary(): String {
        val what = reason.lowercase().replace('_', ' ')
        val after = if (recordedMs > 0) " after ${formatDuration(recordedMs)}" else ""
        val tracks = if (audioTrack) "audio track, $audioSamples audio samples" else "no audio track"
        return "$what$after ($tracks, $videoSamples video samples)"
    }

    private fun formatDuration(ms: Long): String {
        val s = ms / 1000
        return if (s < 60) "${s}s" else "${s / 60}m${s % 60}s"
    }
}
