package com.zektopic.cctvapp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class EventStoreTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun store(
        retentionMs: Long = EventStore.DEFAULT_RETENTION_MS,
        maxEvents: Int = EventStore.DEFAULT_MAX_EVENTS
    ) = EventStore(tempFolder.root, retentionMs, maxEvents)

    private fun metadataFile() = File(File(tempFolder.root, "events"), "events.json")
    private fun mediaDir() = File(File(tempFolder.root, "events"), "media")

    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x00, 0x01)

    // --- captions (Gemini Nano) ---

    @Test
    fun `attaches a caption to a stored event and persists it`() {
        val eventStore = store()
        val event = eventStore.createDetectionEvent("person", 0.9, jpeg)
        assertNull("events start without a caption", event.caption)

        assertTrue(eventStore.setCaption(event.id, "a person near a door"))

        // Re-read from disk rather than trusting the in-memory value.
        val reloaded = EventStore(tempFolder.root).listRecentEvents(10)
            .firstOrNull { it.id == event.id }
        assertEquals("a person near a door", reloaded?.caption)
    }

    @Test
    fun `captioning an evicted event is reported rather than throwing`() {
        // Retention can drop an event between detection and the caption arriving,
        // because captioning is slow and deliberately runs afterwards.
        val eventStore = store()
        assertFalse(eventStore.setCaption("no-such-event-id", "anything"))
    }

    @Test
    fun `caption survives a json round trip and is omitted when absent`() {
        val withCaption = DetectionEvent(
            id = "a", type = "person", score = 0.5, startTimeMs = 1L, endTimeMs = 1L,
            snapshotFileName = null, clipFileName = null, createdAtMs = 1L,
            caption = "a dog on the lawn"
        )
        assertEquals(
            "a dog on the lawn",
            DetectionEvent.fromJsonObject(withCaption.toJsonObject()).caption
        )

        val without = withCaption.copy(caption = null)
        assertFalse(without.toJsonObject().has("caption"))
        assertNull(DetectionEvent.fromJsonObject(without.toJsonObject()).caption)
    }

    @Test
    fun `creates its directories and an empty index`() {
        store()
        assertTrue(metadataFile().exists())
        assertEquals("[]", metadataFile().readText())
    }

    @Test
    fun `stores an event and its snapshot`() {
        val eventStore = store()
        val event = eventStore.createDetectionEvent("motion", 0.42, jpeg)

        assertEquals("motion", event.type)
        assertEquals(0.42, event.score!!, 1e-9)
        assertNotNull(event.snapshotFileName)

        val snapshot = eventStore.getEventSnapshotFile(event.id)
        assertNotNull(snapshot)
        assertArrayEquals(jpeg, snapshot!!.readBytes())
    }

    private fun assertArrayEquals(expected: ByteArray, actual: ByteArray) {
        org.junit.Assert.assertArrayEquals(expected, actual)
    }

    @Test
    fun `event without a snapshot records no file`() {
        val eventStore = store()
        val event = eventStore.createDetectionEvent("motion", null, null)

        assertNull(event.snapshotFileName)
        assertNull(eventStore.getEventSnapshotFile(event.id))
    }

    @Test
    fun `empty snapshot byte array is treated as absent`() {
        val eventStore = store()
        val event = eventStore.createDetectionEvent("motion", 0.1, ByteArray(0))
        assertNull(event.snapshotFileName)
    }

    @Test
    fun `unknown ids resolve to null rather than throwing`() {
        val eventStore = store()
        assertNull(eventStore.getEventAsJson("does-not-exist"))
        assertNull(eventStore.getEventSnapshotFile("does-not-exist"))
        assertNull(eventStore.getEventClipFile("does-not-exist"))
    }

    @Test
    fun `events are listed newest first`() {
        val eventStore = store()
        val first = eventStore.createDetectionEvent("motion", 0.1, null)
        Thread.sleep(5)
        val second = eventStore.createDetectionEvent("person", 0.9, null)

        val listed = eventStore.listRecentEvents(10)
        assertEquals(listOf(second.id, first.id), listed.map { it.id })
    }

    @Test
    fun `listing respects the limit`() {
        val eventStore = store()
        repeat(5) { eventStore.createDetectionEvent("motion", 0.5, null) }
        assertEquals(3, eventStore.listRecentEvents(3).size)
    }

    @Test
    fun `cleanup removes events past the retention window and their media`() {
        // One hour of retention, and an event stamped two hours ago.
        val eventStore = store(retentionMs = 60 * 60 * 1000L)
        val stale = eventStore.createDetectionEvent("motion", 0.5, jpeg)
        val staleSnapshot = File(mediaDir(), stale.snapshotFileName!!)
        assertTrue(staleSnapshot.exists())

        val removed = eventStore.cleanupExpired(
            nowMs = System.currentTimeMillis() + 2 * 60 * 60 * 1000L
        )

        assertEquals(1, removed)
        assertTrue(eventStore.listRecentEvents(10).isEmpty())
        assertFalse("snapshot should be deleted with its event", staleSnapshot.exists())
    }

    @Test
    fun `cleanup keeps events inside the retention window`() {
        val eventStore = store(retentionMs = 60 * 60 * 1000L)
        eventStore.createDetectionEvent("motion", 0.5, null)

        assertEquals(0, eventStore.cleanupExpired())
        assertEquals(1, eventStore.listRecentEvents(10).size)
    }

    @Test
    fun `the event count is capped and the oldest are dropped`() {
        // Time-based retention alone cannot stop a busy camera filling the device.
        val eventStore = store(maxEvents = 3)
        val ids = (1..6).map {
            Thread.sleep(2)
            eventStore.createDetectionEvent("motion", 0.5, null).id
        }

        val remaining = eventStore.listRecentEvents(50).map { it.id }
        assertEquals(3, remaining.size)
        assertEquals(ids.takeLast(3).reversed(), remaining)
    }

    @Test
    fun `capping deletes the media of dropped events`() {
        val eventStore = store(maxEvents = 2)
        val first = eventStore.createDetectionEvent("motion", 0.5, jpeg)
        val firstSnapshot = File(mediaDir(), first.snapshotFileName!!)
        Thread.sleep(2)
        eventStore.createDetectionEvent("motion", 0.5, jpeg)
        Thread.sleep(2)
        eventStore.createDetectionEvent("motion", 0.5, jpeg)

        assertFalse("dropped event's snapshot should be deleted", firstSnapshot.exists())
    }

    @Test
    fun `a corrupt index degrades to empty instead of throwing`() {
        val eventStore = store()
        eventStore.createDetectionEvent("motion", 0.5, null)

        // Simulates the truncation that an in-place write left behind on a process kill.
        metadataFile().writeText("[{\"id\":\"broken\"")

        assertTrue(eventStore.listRecentEvents(10).isEmpty())
        // And the store must still be writable afterwards.
        val recovered = eventStore.createDetectionEvent("person", 0.8, null)
        assertEquals(listOf(recovered.id), eventStore.listRecentEvents(10).map { it.id })
    }

    @Test
    fun `malformed entries are skipped without discarding valid ones`() {
        val eventStore = store()
        val good = eventStore.createDetectionEvent("motion", 0.5, null)

        val text = metadataFile().readText()
        metadataFile().writeText(text.replaceFirst("[", "[{\"no_id\":true},"))

        val listed = eventStore.listRecentEvents(10)
        assertEquals(listOf(good.id), listed.map { it.id })
    }

    @Test
    fun `writes leave no temporary file behind`() {
        val eventStore = store()
        eventStore.createDetectionEvent("motion", 0.5, null)
        assertFalse(File(File(tempFolder.root, "events"), "events.json.tmp").exists())
    }

    @Test
    fun `listEventsAsJson reports a count and honours since`() {
        val eventStore = store()
        eventStore.createDetectionEvent("motion", 0.5, null)
        Thread.sleep(5)
        val cutoff = System.currentTimeMillis()
        Thread.sleep(5)
        eventStore.createDetectionEvent("person", 0.7, null)

        val all = org.json.JSONObject(eventStore.listEventsAsJson(null, 100))
        assertEquals(2, all.getInt("count"))

        val recent = org.json.JSONObject(eventStore.listEventsAsJson(cutoff, 100))
        assertEquals(1, recent.getInt("count"))
        assertEquals("person", recent.getJSONArray("events").getJSONObject(0).getString("type"))
    }

    @Test
    fun `a test event is stored like any other`() {
        val eventStore = store()
        val event = eventStore.createTestEvent(jpeg)
        assertEquals("test", event.type)
        assertNotNull(eventStore.getEventSnapshotFile(event.id))
    }

    // --- clips ---

    /** Creates an event and a clip file of [clipBytes] for it; returns the event id. */
    private fun eventWithClip(eventStore: EventStore, clipBytes: Int): String {
        val event = eventStore.createDetectionEvent("person", 0.9, jpeg)
        val clip = eventStore.clipFileFor(event.id)
        clip.writeBytes(ByteArray(clipBytes))
        assertTrue(eventStore.attachClip(event.id, clip, event.startTimeMs + 15_000, 20_000, null, null, null))
        // startTimeMs orders eviction; keep consecutive events distinct.
        Thread.sleep(3)
        return event.id
    }

    @Test
    fun `an attached clip is served and marked on the event`() {
        val eventStore = store()
        val id = eventWithClip(eventStore, 1_000)
        assertNotNull(eventStore.getEventClipFile(id))
        val json = org.json.JSONObject(eventStore.getEventAsJson(id)!!)
        assertTrue(json.getBoolean("has_clip"))
        assertEquals(json.getLong("start_time") + 15_000, json.getLong("end_time"))
        assertEquals(20_000L, json.getLong("clip_duration_ms"))
    }

    @Test
    fun `a clip for an event evicted mid-recording is deleted`() {
        val eventStore = store()
        val orphan = File(mediaDir(), "gone_clip.mp4").apply { writeBytes(ByteArray(10)) }
        assertFalse(eventStore.attachClip("gone", orphan, 0, null, null, null, null))
        assertFalse(orphan.exists())
    }

    @Test
    fun `media over the size cap evicts the oldest events first`() {
        val eventStore = EventStore(tempFolder.root, maxMediaBytes = 2_500)
        val oldest = eventWithClip(eventStore, 1_000)
        val middle = eventWithClip(eventStore, 1_000)
        val newest = eventWithClip(eventStore, 1_000)

        val remaining = eventStore.listRecentEvents(10).map { it.id }
        assertEquals(listOf(newest, middle), remaining)
        assertFalse(eventStore.clipFileFor(oldest).exists())
    }

    @Test
    fun `a motion clip becomes an animal event when one is seen`() {
        val eventStore = store()
        val event = eventStore.createDetectionEvent("motion", 0.01, jpeg)
        assertTrue(eventStore.tagEvent(event.id, "animal"))

        val reloaded = EventStore(tempFolder.root).listRecentEvents(10).single()
        assertEquals("animal", reloaded.type)
        assertEquals(listOf("motion", "animal"), reloaded.tags)
    }

    @Test
    fun `tagging an evicted event is reported rather than throwing`() {
        assertFalse(store().tagEvent("gone", "animal"))
    }

    @Test
    fun `the newest event is kept even if its clip alone is over the cap`() {
        val eventStore = EventStore(tempFolder.root, maxMediaBytes = 500)
        val id = eventWithClip(eventStore, 1_000)
        assertNotNull(eventStore.getEventClipFile(id))
    }

    // --- archive lifecycle ---

    @Test
    fun `archiving a recording event is refused`() {
        val eventStore = store()
        val event = eventStore.createDetectionEvent("motion", 0.01, jpeg, recording = true)
        assertEquals(ArchiveResult.RECORDING, eventStore.archiveEvent(event.id))
        assertNotNull(eventStore.getEvent(event.id))
    }

    @Test
    fun `archiving a finished event deletes it and its media`() {
        val eventStore = store()
        val id = eventWithClip(eventStore, 1_000)
        assertEquals(ArchiveResult.DELETED, eventStore.archiveEvent(id))
        assertNull(eventStore.getEvent(id))
        assertFalse(eventStore.clipFileFor(id).exists())
        assertEquals(ArchiveResult.NOT_FOUND, eventStore.archiveEvent(id))
    }

    @Test
    fun `attaching a clip records its metadata and ends recording`() {
        val eventStore = store()
        val event = eventStore.createDetectionEvent("animal", 0.5, jpeg, recording = true)
        val clip = eventStore.clipFileFor(event.id).apply { writeBytes(ByteArray(777)) }
        eventStore.attachClip(event.id, clip, event.startTimeMs + 5_000, 12_000, event.startTimeMs - 5_000, "[]", null)
        val stored = eventStore.getEvent(event.id)!!
        assertFalse(stored.recording)
        assertEquals(777L, stored.clipBytes)
        assertEquals(event.startTimeMs - 5_000, stored.clipStartMs)
    }

    @Test
    fun `an event left recording by a crash is recovered`() {
        val eventStore = store()
        val event = eventStore.createDetectionEvent("motion", 0.01, jpeg, recording = true)
        eventStore.clipFileFor(event.id).writeBytes(ByteArray(10))  // unfinalised MP4
        assertEquals(1, EventStore(tempFolder.root).recoverInterrupted())
        val stored = eventStore.getEvent(event.id)!!
        assertFalse(stored.recording)
        assertFalse(eventStore.clipFileFor(event.id).exists())
    }

    @Test
    fun `discarding removes the event and its media`() {
        val eventStore = store()
        val id = eventWithClip(eventStore, 1_000)
        assertTrue(eventStore.discardEvent(id))
        assertNull(eventStore.getEvent(id))
        assertFalse(eventStore.clipFileFor(id).exists())
    }

    @Test
    fun `clearRecording clears the flag and removes the partial clip`() {
        val eventStore = store()
        val event = eventStore.createDetectionEvent("motion", 0.01, jpeg, recording = true)
        eventStore.clipFileFor(event.id).writeBytes(ByteArray(10))  // a clip that never finalised

        assertTrue(eventStore.clearRecording(event.id))

        val stored = eventStore.getEvent(event.id)!!
        assertFalse(stored.recording)
        assertFalse(eventStore.clipFileFor(event.id).exists())
    }

    @Test
    fun `clearRecording on an unknown event is a no-op`() {
        assertFalse(store().clearRecording("no-such-event-id"))
    }

    @Test
    fun `clearRecording on an already-finished event is a no-op`() {
        val eventStore = store()
        val id = eventWithClip(eventStore, 1_000)  // attachClip already cleared recording
        assertFalse(eventStore.clearRecording(id))
        assertNotNull("the finished clip must be left alone", eventStore.getEventClipFile(id))
    }

    // --- why a clip was lost ---

    private val finaliseError = ClipError("FINALISE_FAILED", "IllegalStateException: boom", 552_000, true, 16_000, 0)

    @Test
    fun `a recorded clip failure survives clearing the recording and a reload`() {
        val eventStore = store()
        val event = eventStore.createDetectionEvent("person", 0.9, jpeg, recording = true)
        eventStore.clipFileFor(event.id).writeBytes(ByteArray(10))

        assertTrue(eventStore.recordClipFailure(event.id, finaliseError))
        assertTrue(eventStore.clearRecording(event.id))

        val stored = EventStore(tempFolder.root).getEvent(event.id)!!
        assertFalse(stored.recording)
        assertNull(stored.clipFileName)
        assertEquals(finaliseError, stored.clipError)
    }

    @Test
    fun `recording a clip failure on an unknown event is a no-op`() {
        assertFalse(store().recordClipFailure("no-such-event-id", finaliseError))
    }

    @Test
    fun `a failed continuation is recorded on the held part that kept its clip`() {
        val eventStore = store()
        val first = part(eventStore)
        attach(eventStore, first, held = true)

        eventStore.recordClipFailure(first.id, ClipError("ROLLOVER_DECLINED", null, 120_000, false, 3600, 0))

        val stored = eventStore.getEvent(first.id)!!
        assertNotNull("the finalised clip is still there", stored.clipFileName)
        assertEquals("ROLLOVER_DECLINED", stored.clipError?.reason)
    }

    // --- journals: the raw data behind a clip ---

    private fun journalOf(eventStore: EventStore, id: String, bytes: Int = 100): File =
        eventStore.journalFileFor(id).also { it.writeBytes(ByteArray(bytes)) }

    @Test
    fun `a visit has an open part while a later part is still being written or rebuilt`() {
        val eventStore = store()
        val first = part(eventStore)
        val second = part(eventStore, visitId = first.id)
        assertEquals("the continuation keeps the visit", first.id, eventStore.getEvent(second.id)!!.visitId)
        assertTrue(eventStore.visitHasOpenPart(first.id, exceptId = first.id))
        attach(eventStore, second, held = false)
        assertFalse(eventStore.visitHasOpenPart(first.id, exceptId = first.id))
        assertFalse("another visit is not this one's", eventStore.visitHasOpenPart("other", exceptId = first.id))
    }

    @Test
    fun `an interrupted clip with a journal stays recording for a rebuild, one without is cleared`() {
        val eventStore = store()
        val withJournal = eventStore.createDetectionEvent("person", 0.9, jpeg, recording = true)
        val without = eventStore.createDetectionEvent("motion", 0.1, jpeg, recording = true)
        journalOf(eventStore, withJournal.id)
        eventStore.clipFileFor(without.id).writeBytes(ByteArray(10))

        assertEquals("only the one with nothing to rebuild is recovered", 1, eventStore.recoverInterrupted())

        assertTrue("still recording: the archive skips it until the rebuild ends", eventStore.getEvent(withJournal.id)!!.recording)
        assertFalse(eventStore.getEvent(without.id)!!.recording)
        assertFalse(eventStore.clipFileFor(without.id).exists())
        assertEquals(listOf(withJournal.id), eventStore.pendingJournalRebuilds().map { it.id })
        assertEquals(ArchiveResult.RECORDING, eventStore.archiveEvent(withJournal.id))
        assertTrue("and its journal is untouched", eventStore.journalFileFor(withJournal.id).exists())
    }

    @Test
    fun `a rebuilt interrupted clip is attached like any finished one`() {
        val eventStore = store()
        val event = eventStore.createDetectionEvent("person", 0.9, jpeg, recording = true)
        journalOf(eventStore, event.id)
        eventStore.recoverInterrupted()
        val clip = eventStore.clipFileFor(event.id).also { it.writeBytes(ByteArray(500)) }
        val error = ClipError("INTERRUPTED", "recording was interrupted", 9_000, true, 270, 400)

        assertTrue(eventStore.attachClip(event.id, clip, 5_000, 9_000, 1L, null, null, clipError = error, recovered = true))

        val stored = eventStore.getEvent(event.id)!!
        assertFalse(stored.recording)
        assertTrue(stored.clipRecovered)
        assertFalse(stored.clipJournalKept)
        assertEquals(error, stored.clipError)
        assertTrue(eventStore.pendingJournalRebuilds().isEmpty())
        assertEquals(error, EventStore(tempFolder.root).getEvent(event.id)!!.clipError)
    }

    @Test
    fun `an event whose journal is all that is left of its clip is never archived`() {
        val eventStore = store()
        val event = eventStore.createDetectionEvent("person", 0.9, jpeg, recording = true)
        journalOf(eventStore, event.id)
        eventStore.recordClipFailure(event.id, finaliseError, journalKept = true)
        eventStore.clearRecording(event.id)

        assertTrue(eventStore.getEvent(event.id)!!.clipJournalKept)
        assertEquals(ArchiveResult.JOURNAL_KEPT, eventStore.archiveEvent(event.id))
        assertNotNull("still there", eventStore.getEvent(event.id))
        assertTrue(eventStore.journalFileFor(event.id).exists())
        assertEquals("a restart does not rebuild or drop it", 0, store().recoverInterrupted())
        assertTrue(eventStore.pendingJournalRebuilds().isEmpty())
    }

    @Test
    fun `orphan journals go, but a pending rebuild and kept raw data stay`() {
        val eventStore = store()
        val pending = eventStore.createDetectionEvent("person", 0.9, jpeg, recording = true)
        val kept = eventStore.createDetectionEvent("person", 0.9, jpeg, recording = true)
        val finished = eventStore.createDetectionEvent("person", 0.9, jpeg, recording = true)
        journalOf(eventStore, pending.id)
        journalOf(eventStore, kept.id)
        journalOf(eventStore, finished.id)
        eventStore.recordClipFailure(kept.id, finaliseError, journalKept = true)
        eventStore.clearRecording(kept.id)
        eventStore.clipFileFor(finished.id).writeBytes(ByteArray(10))
        attach(eventStore, finished, held = false)
        val gone = journalOf(eventStore, "no-such-event")
        eventStore.clipFileFor("e").let { File(it.parentFile, "x_clip.mp4.rebuild").writeBytes(ByteArray(3)) }

        assertEquals(2, eventStore.deleteOrphanJournals())

        assertFalse(gone.exists())
        assertFalse("a journal whose clip is attached and verified", eventStore.journalFileFor(finished.id).exists())
        assertTrue(eventStore.journalFileFor(pending.id).exists())
        assertTrue(eventStore.journalFileFor(kept.id).exists())
        assertFalse("a half-built scratch file", File(eventStore.clipFileFor("e").parentFile, "x_clip.mp4.rebuild").exists())
    }

    @Test
    fun `a clip waiting on a rebuild is not expired, evicted or dropped by the media cap`() {
        val eventStore = EventStore(tempFolder.root, retentionMs = 1_000L, maxEvents = 1, maxMediaBytes = 3_000L)
        val pending = eventStore.createDetectionEvent("person", 0.9, jpeg, recording = true)
        journalOf(eventStore, pending.id, bytes = 5_000)
        val plain = eventStore.createDetectionEvent("motion", 0.1, jpeg, recording = true)
        Thread.sleep(3)
        val newest = part(eventStore, clipBytes = 1_000)

        attach(eventStore, newest, held = false)   // media cap, max events
        eventStore.cleanupExpired(nowMs = System.currentTimeMillis() + 10_000_000L)

        assertNotNull("the pending clip survives the cap, the event limit and retention", eventStore.getEvent(pending.id))
        assertTrue(eventStore.journalFileFor(pending.id).exists())
        assertNull("an ordinary old event does not", eventStore.getEvent(plain.id))
    }

    @Test
    fun `a rebuild scratch file is swept only when nothing could still need it`() {
        val eventStore = store()
        val pending = eventStore.createDetectionEvent("person", 0.9, jpeg, recording = true)
        val kept = eventStore.createDetectionEvent("person", 0.9, jpeg, recording = true)
        val done = eventStore.createDetectionEvent("person", 0.9, jpeg, recording = true)
        for (e in listOf(pending, kept)) journalOf(eventStore, e.id)
        eventStore.recordClipFailure(kept.id, finaliseError, journalKept = true)
        eventStore.clearRecording(kept.id)
        eventStore.clipFileFor(done.id).writeBytes(ByteArray(10))
        attach(eventStore, done, held = false)
        fun scratch(id: String) = File(eventStore.clipFileFor(id).parentFile, "${id}_clip.mp4.rebuild")
        for (id in listOf(pending.id, kept.id, done.id, "gone")) scratch(id).writeBytes(ByteArray(3))

        eventStore.deleteOrphanJournals()

        assertTrue(scratch(pending.id).exists())
        assertTrue(scratch(kept.id).exists())
        assertFalse(scratch(done.id).exists())
        assertFalse(scratch("gone").exists())
    }

    @Test
    fun `a journal is deleted with its event however the event goes`() {
        val eventStore = store()
        val a = eventStore.createDetectionEvent("person", 0.9, jpeg, recording = true)
        val b = part(eventStore)
        val c = part(eventStore, visitId = b.id)
        val d = eventStore.createDetectionEvent("motion", 0.1, jpeg, recording = true)
        for (e in listOf(a, b, c, d)) journalOf(eventStore, e.id)
        attach(eventStore, d, held = false)

        eventStore.discardEvent(a.id)
        eventStore.discardVisit(b.id)
        eventStore.archiveEvent(d.id)

        for (e in listOf(a, b, c, d)) assertFalse(eventStore.journalFileFor(e.id).exists())
    }

    @Test
    fun `journals count toward the media cap, so old events are evicted to make room`() {
        val eventStore = EventStore(tempFolder.root, maxMediaBytes = 3_000L)
        val old = eventStore.createDetectionEvent("person", 0.9, jpeg, recording = true)
        eventStore.clipFileFor(old.id).writeBytes(ByteArray(1_000))
        journalOf(eventStore, old.id, bytes = 5_000)
        attach(eventStore, old, held = false)
        Thread.sleep(3)
        val newer = part(eventStore, clipBytes = 1_000)

        attach(eventStore, newer, held = false)

        assertNull("1 kB of clip alone would have fit; its 5 kB journal did not", eventStore.getEvent(old.id))
        assertFalse(eventStore.journalFileFor(old.id).exists())
        assertNotNull(eventStore.getEvent(newer.id))
    }

    // --- visits: a recording split into rollover parts ---

    /** Creates a recording part of [visitId] (null = a first part) with a clip file on disk. */
    private fun part(eventStore: EventStore, visitId: String? = null, clipBytes: Int = 1_000): DetectionEvent {
        val event = eventStore.createDetectionEvent("motion", 0.01, jpeg, recording = true, visitId = visitId)
        eventStore.clipFileFor(event.id).writeBytes(ByteArray(clipBytes))
        Thread.sleep(3)
        return event
    }

    private fun attach(eventStore: EventStore, event: DetectionEvent, held: Boolean) {
        assertTrue(
            eventStore.attachClip(
                event.id, eventStore.clipFileFor(event.id), event.startTimeMs + 1_000, 1_000,
                null, null, null, held = held
            )
        )
    }

    @Test
    fun `a first part is its own visit and a continuation carries it`() {
        val eventStore = store()
        val first = part(eventStore)
        assertEquals(first.id, first.visitId)
        assertEquals(first.id, eventStore.getEvent(first.id)!!.visitId)
        val second = part(eventStore, visitId = first.id)
        assertEquals(first.id, eventStore.getEvent(second.id)!!.visitId)
        assertNull("a snapshot-only event is no visit", eventStore.createDetectionEvent("person", 0.9, jpeg).visitId)
    }

    @Test
    fun `a held attach keeps the part recording and unarchivable`() {
        val eventStore = store()
        val first = part(eventStore, clipBytes = 555)
        attach(eventStore, first, held = true)

        val stored = eventStore.getEvent(first.id)!!
        assertTrue("held until the recording ends", stored.recording)
        assertEquals(eventStore.clipFileFor(first.id).name, stored.clipFileName)
        assertEquals(555L, stored.clipBytes)
        assertEquals(ArchiveResult.RECORDING, eventStore.archiveEvent(first.id))
        assertNotNull(eventStore.getEventClipFile(first.id))
    }

    @Test
    fun `releaseVisit releases every attached part of that visit and none of another`() {
        val eventStore = store()
        val a1 = part(eventStore)
        val a2 = part(eventStore, visitId = a1.id)
        val a3 = part(eventStore, visitId = a1.id)  // still recording, nothing attached yet
        val b1 = part(eventStore)
        attach(eventStore, a1, held = true)
        attach(eventStore, a2, held = true)
        attach(eventStore, b1, held = true)

        assertEquals(2, eventStore.releaseVisit(a1.id))

        assertFalse(eventStore.getEvent(a1.id)!!.recording)
        assertFalse(eventStore.getEvent(a2.id)!!.recording)
        assertTrue("an unattached part is still being written", eventStore.getEvent(a3.id)!!.recording)
        assertTrue("another visit stays held", eventStore.getEvent(b1.id)!!.recording)
        assertEquals(ArchiveResult.DELETED, eventStore.archiveEvent(a1.id))
        assertEquals(0, eventStore.releaseVisit("no-such-visit"))
    }

    @Test
    fun `discardVisit deletes every part of the visit and its media and leaves other visits alone`() {
        val eventStore = store()
        val a1 = part(eventStore)
        val a2 = part(eventStore, visitId = a1.id)
        val a3 = part(eventStore, visitId = a1.id)  // the open part: partial clip, no attach
        val b1 = part(eventStore)
        attach(eventStore, a1, held = true)
        attach(eventStore, a2, held = true)
        attach(eventStore, b1, held = false)

        assertEquals(3, eventStore.discardVisit(a1.id))

        for (e in listOf(a1, a2, a3)) {
            assertNull(eventStore.getEvent(e.id))
            assertFalse(eventStore.clipFileFor(e.id).exists())
            assertFalse(File(mediaDir(), e.snapshotFileName!!).exists())
        }
        assertNotNull(eventStore.getEvent(b1.id))
        assertNotNull(eventStore.getEventClipFile(b1.id))
        assertNotNull(eventStore.getEventSnapshotFile(b1.id))
        assertEquals(0, eventStore.discardVisit(a1.id))
    }

    @Test
    fun `recovery releases a held part and keeps its clip but still drops an unattached partial`() {
        val eventStore = store()
        val held = part(eventStore)
        attach(eventStore, held, held = true)
        val partial = part(eventStore, visitId = held.id)  // killed mid-write: unfinalised MP4

        assertEquals(2, EventStore(tempFolder.root).recoverInterrupted())

        val keptPart = eventStore.getEvent(held.id)!!
        assertFalse(keptPart.recording)
        assertNotNull("a finalised part survives a restart", eventStore.getEventClipFile(held.id))
        val dropped = eventStore.getEvent(partial.id)!!
        assertFalse(dropped.recording)
        assertNull(dropped.clipFileName)
        assertFalse(eventStore.clipFileFor(partial.id).exists())
    }

    @Test
    fun `over the media cap held parts are released rather than deleted`() {
        val eventStore = EventStore(tempFolder.root, maxMediaBytes = 2_500)
        val a1 = part(eventStore)
        val a2 = part(eventStore, visitId = a1.id)
        val a3 = part(eventStore, visitId = a1.id)
        attach(eventStore, a1, held = true)
        attach(eventStore, a2, held = true)
        assertTrue("under the cap nothing changes", eventStore.getEvent(a1.id)!!.recording)
        attach(eventStore, a3, held = true)  // 3 000 bytes: over the cap

        for (e in listOf(a1, a2, a3)) {
            val stored = eventStore.getEvent(e.id)
            assertNotNull("a held part is never evicted in the pass that releases it", stored)
            assertFalse("released so the archive can copy it", stored!!.recording)
            assertNotNull(eventStore.getEventClipFile(e.id))
        }
        assertEquals(ArchiveResult.DELETED, eventStore.archiveEvent(a1.id))
    }

    @Test
    fun `a released part is evicted by a later over-cap attach like any finished event`() {
        val eventStore = EventStore(tempFolder.root, maxMediaBytes = 2_500)
        val a1 = part(eventStore)
        val a2 = part(eventStore, visitId = a1.id)
        val a3 = part(eventStore, visitId = a1.id)
        attach(eventStore, a1, held = true)
        attach(eventStore, a2, held = true)
        attach(eventStore, a3, held = true)  // releases all three

        val later = eventWithClip(eventStore, 1_000)  // 4 000 bytes: evict oldest

        assertEquals(listOf(later, a3.id), eventStore.listRecentEvents(10).map { it.id })
        assertFalse(eventStore.clipFileFor(a1.id).exists())
        assertFalse(eventStore.clipFileFor(a2.id).exists())
    }

    @Test
    fun `clearRecording leaves a held part and its clip alone`() {
        // A rollover that could not open its next part reports the previous (held, attached)
        // part as failed; that must release it through releaseVisit, never delete its clip.
        val eventStore = store()
        val held = part(eventStore)
        attach(eventStore, held, held = true)
        assertFalse(eventStore.clearRecording(held.id))
        assertNotNull(eventStore.getEventClipFile(held.id))
        assertEquals(1, eventStore.releaseVisit(held.id))
    }
}
