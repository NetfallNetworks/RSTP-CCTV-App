package com.zektopic.cctvapp

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClipTimelineTest {
    @Test
    fun `detections serialise with their box`() {
        val t = ClipTimeline()
        t.addDetection(1_000, "animal", 0.42, listOf(0.1f, 0.2f, 0.3f, 0.4f))
        val d = JSONArray(t.detectionsJson()).getJSONObject(0)
        assertEquals(1_000L, d.getLong("t"))
        assertEquals("animal", d.getString("label"))
        assertEquals(0.3, d.getJSONArray("box").getDouble(2), 1e-6)
    }

    @Test
    fun `detections stop at the cap`() {
        val t = ClipTimeline(maxDetections = 3)
        repeat(5) { t.addDetection(it.toLong(), "person", 0.9, null) }
        assertEquals(3, JSONArray(t.detectionsJson()).length())
    }

    @Test
    fun `activity is per second in thousandths, gaps as zero, peaks kept`() {
        val t = ClipTimeline()
        t.addActivity(10_000, 0.004)
        t.addActivity(10_400, 0.012)   // same second: keep the larger
        t.addActivity(13_100, 0.5)     // two seconds skipped
        val a = JSONObject(t.activityJson()!!)
        assertEquals(10_000L, a.getLong("start_ms"))
        assertEquals(1_000L, a.getLong("step_ms"))
        assertEquals("[12,0,0,500]", a.getJSONArray("permille").toString())
    }

    @Test
    fun `no activity means no activity object`() {
        assertNull(ClipTimeline().activityJson())
    }

    @Test
    fun `a sample far in the future is ignored and the array stays small`() {
        val t = ClipTimeline()
        t.addActivity(0, 0.1)
        t.addActivity(30L * 24 * 60 * 60 * 1000, 0.9) // +30 days: an NTP jump, not real activity
        val a = JSONObject(t.activityJson()!!)
        val permille = a.getJSONArray("permille")
        // Only the first, in-bounds sample landed; the far-future one (index ~2.59M) was
        // ignored rather than padding permille out to it.
        assertEquals(1, permille.length())
        assertTrue(permille.length() < ClipTimeline.MAX_ACTIVITY_SAMPLES)
    }

    @Test
    fun `detection score and box values are rounded to 3 decimals`() {
        val t = ClipTimeline()
        t.addDetection(0, "person", 0.1f.toDouble(), listOf(0.1f))
        val d = JSONArray(t.detectionsJson()).getJSONObject(0)
        assertEquals(0.1, d.getDouble("score"), 0.0)
        assertEquals(0.1, d.getJSONArray("box").getDouble(0), 0.0)
    }
}
