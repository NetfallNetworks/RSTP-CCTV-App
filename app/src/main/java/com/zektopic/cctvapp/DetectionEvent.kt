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
    val activityJson: String? = null
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
                activityJson = obj.optJSONObject("activity")?.toString()
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
        obj.put("created_at", createdAtMs)
        return obj
    }
}
