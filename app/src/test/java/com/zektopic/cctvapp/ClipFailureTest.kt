package com.zektopic.cctvapp

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClipFailureTest {

    @Test
    fun `detail is the exception class and message`() {
        assertEquals(
            "IllegalStateException: Failed to stop the muxer",
            ClipFailure.detailOf(IllegalStateException("Failed to stop the muxer"))
        )
    }

    @Test
    fun `detail without a message is just the class`() {
        assertEquals("RuntimeException", ClipFailure.detailOf(RuntimeException()))
    }

    @Test
    fun `a long message is truncated`() {
        val detail = ClipFailure.detailOf(RuntimeException("x".repeat(5_000)))
        assertEquals(ClipFailure.MAX_DETAIL_CHARS, detail.length)
        assertTrue(detail.startsWith("RuntimeException: xxx"))
    }

    @Test
    fun `status json carries the counters and the last failure, escaped`() {
        val failure = ClipFailure(
            "e1", ClipFailureReason.FINALISE_FAILED, "IOException: \"bad\" path\\n", 552_000, true, 16_000, 0, atMs = 1234L
        )
        val json = JSONObject("{" + ClipStats(5, 3, 1, failure, 364).toJsonFields() + "}")

        assertEquals(5L, json.getLong("clipsStarted"))
        assertEquals(3L, json.getLong("clipsFinalised"))
        assertEquals(1L, json.getLong("clipFailures"))
        assertEquals(364L, json.getLong("clipStartMaxHoldMs"))
        val last = json.getJSONObject("lastClipFailure")
        assertEquals(1234L, last.getLong("atMs"))
        assertEquals("FINALISE_FAILED", last.getString("reason"))
        assertEquals("IOException: \"bad\" path\\n", last.getString("message"))
        assertEquals(0, last.getInt("audioSamples"))
        assertTrue(last.getBoolean("audioTrack"))
    }

    @Test
    fun `status json carries the journal problems`() {
        val problem = JournalProblem(77L, "e1", "open", "could not be opened: EACCES")
        val json = JSONObject("{" + ClipStats(journalOpenFailures = 2, journalWriteFailures = 1, lastJournalProblem = problem).toJsonFields() + "}")
        assertEquals(2L, json.getLong("journalOpenFailures"))
        assertEquals(1L, json.getLong("journalWriteFailures"))
        assertEquals("open", json.getJSONObject("lastJournalProblem").getString("kind"))
        assertEquals("could not be opened: EACCES", json.getJSONObject("lastJournalProblem").getString("reason"))
        assertTrue(JSONObject("{" + ClipStats().toJsonFields() + "}").isNull("lastJournalProblem"))
    }

    @Test
    fun `status json has a null last failure before any`() {
        val json = JSONObject("{" + ClipStats().toJsonFields() + "}")
        assertTrue(json.isNull("lastClipFailure"))
        assertFalse(json.isNull("clipsStarted"))
    }
}
