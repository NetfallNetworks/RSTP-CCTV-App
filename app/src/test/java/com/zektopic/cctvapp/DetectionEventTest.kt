package com.zektopic.cctvapp

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DetectionEventTest {

    private fun event(
        score: Double? = 0.75,
        endTimeMs: Long? = 2_000L,
        snapshotFileName: String? = "abc_snapshot.jpg",
        clipFileName: String? = null
    ) = DetectionEvent(
        id = "abc",
        type = "person",
        score = score,
        startTimeMs = 1_000L,
        endTimeMs = endTimeMs,
        snapshotFileName = snapshotFileName,
        clipFileName = clipFileName,
        createdAtMs = 3_000L
    )

    @Test
    fun `round trips through json`() {
        val original = event()
        val restored = DetectionEvent.fromJsonObject(original.toJsonObject())
        assertEquals(original, restored)
    }

    @Test
    fun `round trips with every optional field absent`() {
        val original = event(score = null, endTimeMs = null, snapshotFileName = null)
        val restored = DetectionEvent.fromJsonObject(original.toJsonObject())

        assertEquals(original, restored)
        assertNull(restored.score)
        assertNull(restored.endTimeMs)
        assertNull(restored.snapshotFileName)
    }

    @Test
    fun `round trips with a clip`() {
        val original = event(clipFileName = "abc_clip.mp4")
        val restored = DetectionEvent.fromJsonObject(original.toJsonObject())
        assertEquals("abc_clip.mp4", restored.clipFileName)
        assertTrue(original.toJsonObject().getBoolean("has_clip"))
    }

    @Test
    fun `media flags reflect what is present`() {
        val json = event(snapshotFileName = "s.jpg", clipFileName = null).toJsonObject()
        assertTrue(json.getBoolean("has_snapshot"))
        assertFalse(json.getBoolean("has_clip"))
    }

    @Test
    fun `omits optional keys entirely rather than writing null`() {
        val json = event(score = null, endTimeMs = null, snapshotFileName = null).toJsonObject()
        assertFalse(json.has("score"))
        assertFalse(json.has("end_time"))
        assertFalse(json.has("snapshot"))
    }

    @Test
    fun `an entry missing its type falls back to unknown`() {
        val json = JSONObject().put("id", "x").put("start_time", 1L)
        assertEquals("unknown", DetectionEvent.fromJsonObject(json).type)
    }

    @Test
    fun `tags round trip`() {
        val original = event().copy(tags = listOf("motion", "animal"))
        assertEquals(original, DetectionEvent.fromJsonObject(original.toJsonObject()))
    }

    @Test
    fun `a more specific tag raises the type`() {
        val motion = event().copy(type = "motion", tags = listOf("motion"))
        val tagged = motion.withTag("animal")
        assertEquals("animal", tagged.type)
        assertEquals(listOf("motion", "animal"), tagged.tags)
    }

    @Test
    fun `a less specific tag is recorded but keeps the type`() {
        val animal = event().copy(type = "animal", tags = listOf("animal"))
        val tagged = animal.withTag("motion")
        assertEquals("animal", tagged.type)
        assertEquals(listOf("animal", "motion"), tagged.tags)
    }

    @Test
    fun `tagging twice is a no-op`() {
        val animal = event().copy(type = "animal", tags = listOf("animal"))
        assertEquals(animal, animal.withTag("animal"))
    }

    @Test
    fun `entries written before tags existed load with none`() {
        val json = JSONObject().put("id", "x").put("type", "motion").put("start_time", 1L)
        assertTrue(DetectionEvent.fromJsonObject(json).tags.isEmpty())
    }

    @Test(expected = org.json.JSONException::class)
    fun `an entry without an id is rejected`() {
        // EventStore relies on this to skip corrupt rows rather than serving a bad id.
        DetectionEvent.fromJsonObject(JSONObject().put("type", "motion"))
    }

    @Test
    fun `archive fields round trip`() {
        // org.json.JSONObject backs onto a plain HashMap (verified against the
        // 20250517 jar), so it does not preserve key insertion order. Seed `original`
        // with text already run through the same parser toJsonObject() uses, so the
        // round trip compares like for like instead of tripping over incidental key
        // order the data itself doesn't care about.
        val original = event().copy(
            recording = true, clipBytes = 1234L, clipStartMs = 999L,
            detectionsJson = org.json.JSONArray(
                """[{"t":1000,"label":"animal","score":0.5,"box":[0.1,0.2,0.3,0.4]}]"""
            ).toString(),
            activityJson = JSONObject(
                """{"start_ms":1000,"step_ms":1000,"permille":[0,5,12]}"""
            ).toString()
        )
        val json = original.toJsonObject()
        assertTrue(json.getBoolean("recording"))
        assertEquals("animal", json.getJSONArray("detections").getJSONObject(0).getString("label"))
        assertEquals(12, json.getJSONObject("activity").getJSONArray("permille").getInt(2))
        assertEquals(original, DetectionEvent.fromJsonObject(json))
    }

    @Test
    fun `visit id round trips and is omitted when absent`() {
        val part = event().copy(visitId = "first-part-id")
        val json = part.toJsonObject()
        assertEquals("first-part-id", json.getString("visit_id"))
        assertEquals(part, DetectionEvent.fromJsonObject(json))
        assertFalse(event().toJsonObject().has("visit_id"))
        assertNull(DetectionEvent.fromJsonObject(event().toJsonObject()).visitId)
    }

    @Test
    fun `manual ranks above motion but below animal`() {
        assertEquals("manual", event().copy(type = "motion", tags = listOf("motion")).withTag("manual").type)
        assertEquals("animal", event().copy(type = "manual", tags = listOf("manual")).withTag("animal").type)
    }

    private val finaliseError = ClipError(
        reason = "FINALISE_FAILED", detail = "IllegalStateException: Failed to stop the muxer",
        recordedMs = 552_000L, audioTrack = true, videoSamples = 16_000, audioSamples = 0
    )

    @Test
    fun `a clip error round trips and is flat in the json`() {
        val original = event().copy(clipError = finaliseError)
        val json = original.toJsonObject()
        assertEquals("FINALISE_FAILED", json.getString("clip_error"))
        assertEquals("IllegalStateException: Failed to stop the muxer", json.getString("clip_error_detail"))
        assertEquals(552_000L, json.getLong("clip_error_duration_ms"))
        assertTrue(json.getBoolean("clip_error_audio"))
        assertEquals(16_000, json.getInt("clip_error_video_samples"))
        assertEquals(0, json.getInt("clip_error_audio_samples"))
        assertEquals(original, DetectionEvent.fromJsonObject(json))
    }

    @Test
    fun `events written before clip errors existed load with none and write none`() {
        val json = JSONObject().put("id", "x").put("type", "person").put("start_time", 1L)
        assertNull(DetectionEvent.fromJsonObject(json).clipError)
        val written = event().toJsonObject()
        assertFalse(written.has("clip_error"))
        assertFalse(written.has("clip_error_summary"))
    }

    @Test
    fun `the json carries a ready-to-show summary the clips page prints after No clip`() {
        val json = event().copy(clipError = finaliseError).toJsonObject()
        assertEquals(
            "finalise failed after 9m12s (audio track, 0 audio samples, 16000 video samples)",
            json.getString("clip_error_summary")
        )
    }

    @Test
    fun `summary covers each reason, short durations and a clip with no audio`() {
        assertEquals(
            "start failed (no audio track, 0 video samples)",
            ClipError("START_FAILED", null, 0, false, 0, 0).summary()
        )
        assertEquals(
            "write failed after 42s (no audio track, 1260 video samples)",
            ClipError("WRITE_FAILED", null, 42_000, false, 1260, 0).summary()
        )
        assertEquals(
            "rollover declined after 2m0s (audio track, 5600 audio samples, 3600 video samples)",
            ClipError("ROLLOVER_DECLINED", null, 120_000, true, 3600, 5600).summary()
        )
    }

    @Test
    fun `recovery flags round trip, are omitted when false, and change the summary`() {
        val plain = event().copy(clipError = finaliseError)
        assertFalse(plain.toJsonObject().has("clip_recovered"))
        assertFalse(plain.toJsonObject().has("clip_journal_kept"))

        val recovered = plain.copy(clipRecovered = true)
        assertTrue(recovered.toJsonObject().getBoolean("clip_recovered"))
        assertEquals(recovered, DetectionEvent.fromJsonObject(recovered.toJsonObject()))
        assertEquals(
            "recovered from journal after finalise failed after 9m12s (audio track, 0 audio samples, 16000 video samples)",
            recovered.toJsonObject().getString("clip_error_summary")
        )

        val damaged = plain.copy(clipJournalKept = true)
        assertTrue(damaged.toJsonObject().getBoolean("clip_journal_kept"))
        assertEquals(damaged, DetectionEvent.fromJsonObject(damaged.toJsonObject()))
        assertEquals(
            "clip damaged, raw data kept (finalise failed after 9m12s (audio track, 0 audio samples, 16000 video samples))",
            damaged.toJsonObject().getString("clip_error_summary")
        )
    }

    @Test
    fun `events written before recovery existed load as neither recovered nor damaged`() {
        val json = JSONObject().put("id", "x").put("type", "person").put("start_time", 1L)
        val e = DetectionEvent.fromJsonObject(json)
        assertFalse(e.clipRecovered)
        assertFalse(e.clipJournalKept)
    }

    @Test
    fun `a reason from a newer build is shown as written`() {
        assertEquals(
            "disk exploded after 5s (no audio track, 3 video samples)",
            ClipError("DISK_EXPLODED", null, 5_000, false, 3, 0).summary()
        )
    }
}
