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
