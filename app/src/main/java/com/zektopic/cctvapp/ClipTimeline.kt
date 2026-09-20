package com.zektopic.cctvapp

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt

/**
 * What happened during one clip, for the review page: every person/animal detection
 * (click-to-jump markers) and the motion amount once a second (the faint activity strip,
 * which still shows where movement is when the detector saw nothing). Kept in memory
 * while the clip records and written once when it finishes. Detection thread only.
 */
class ClipTimeline(private val maxDetections: Int = 1000) {
    companion object {
        const val STEP_MS = 1000L

        /**
         * Ceiling on activity samples. Clips are at most DEFAULT_MAX_CLIP_US (600 s /
         * 10 min) from ClipRecorder, plus a little slack; a wall-clock jump (e.g. an NTP
         * correction after boot) must not be able to grow [permille] into the millions
         * and OOM the process.
         */
        const val MAX_ACTIVITY_SAMPLES = 660

        /** Rounds to 3 decimals -- Float.toDouble() otherwise writes e.g. 0.10000000149011612. */
        private fun round3(v: Double): Double = kotlin.math.round(v * 1000) / 1000
    }

    private val detections = JSONArray()
    private var activityStartMs: Long? = null
    private val permille = mutableListOf<Int>()

    fun addDetection(tMs: Long, label: String, score: Double, box: List<Float>?) {
        if (detections.length() >= maxDetections) return
        val d = JSONObject().put("t", tMs).put("label", label).put("score", round3(score))
        if (box != null) d.put("box", JSONArray(box.map { round3(it.toDouble()) }))
        detections.put(d)
    }

    fun addActivity(tMs: Long, ratio: Double) {
        val start = activityStartMs ?: tMs.also { activityStartMs = it }
        val index = ((tMs - start) / STEP_MS).toInt()
        if (index < 0 || index >= MAX_ACTIVITY_SAMPLES) return
        while (permille.size <= index) permille.add(0)
        val value = (ratio * 1000).roundToInt().coerceIn(0, 1000)
        permille[index] = maxOf(permille[index], value)
    }

    fun detectionsJson(): String = detections.toString()

    fun activityJson(): String? {
        val start = activityStartMs ?: return null
        return JSONObject().put("start_ms", start).put("step_ms", STEP_MS)
            .put("permille", JSONArray(permille)).toString()
    }
}
