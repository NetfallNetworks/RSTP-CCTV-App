package com.zektopic.cctvapp

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

enum class ArchiveResult { DELETED, NOT_FOUND, RECORDING }

/**
 * On-disk store for detection events and their snapshots.
 *
 * Takes a plain directory rather than a Context so it can be exercised by JVM unit
 * tests; [forContext] is the production entry point.
 */
class EventStore(
    rootDir: File,
    private val retentionMs: Long = DEFAULT_RETENTION_MS,
    private val maxEvents: Int = DEFAULT_MAX_EVENTS,
    private val maxMediaBytes: Long = DEFAULT_MAX_MEDIA_BYTES
) {
    companion object {
        const val DEFAULT_RETENTION_MS: Long = 72L * 60L * 60L * 1000L

        /**
         * Hard ceiling on retained events. Time-based retention alone is not enough --
         * a camera pointed at a busy street can generate thousands of events well
         * inside the retention window and fill the device.
         */
        const val DEFAULT_MAX_EVENTS: Int = 2000

        /**
         * Ceiling on snapshots plus clips, oldest events evicted first. Clips are what
         * make this necessary: at stream bitrates a busy evening is hundreds of MB, and
         * the Echo Show 5 this runs on has under 3 GB free in total.
         */
        const val DEFAULT_MAX_MEDIA_BYTES: Long = 2_000L * 1024L * 1024L

        fun forContext(context: Context): EventStore = EventStore(context.filesDir)
    }

    private val lock = Any()
    private val eventsDir = File(rootDir, "events")
    private val metadataFile = File(eventsDir, "events.json")
    private val mediaDir = File(eventsDir, "media")

    init {
        if (!eventsDir.exists()) eventsDir.mkdirs()
        if (!mediaDir.exists()) mediaDir.mkdirs()
        if (!metadataFile.exists()) {
            metadataFile.writeText("[]")
        }
    }

    fun listEventsAsJson(sinceMs: Long?, limit: Int): String {
        val clampedLimit = limit.coerceIn(1, 500)
        val events = listEvents(sinceMs, clampedLimit)
        val array = JSONArray()
        for (event in events) {
            array.put(event.toJsonObject())
        }
        val root = JSONObject()
        root.put("events", array)
        root.put("count", events.size)
        return root.toString()
    }

    fun getEventAsJson(id: String): String? {
        return getEvent(id)?.toJsonObject()?.toString()
    }

    fun getEventSnapshotFile(id: String): File? {
        val event = getEvent(id) ?: return null
        val fileName = event.snapshotFileName ?: return null
        val file = File(mediaDir, fileName)
        return if (file.exists()) file else null
    }

    fun getEventClipFile(id: String): File? {
        val event = getEvent(id) ?: return null
        val fileName = event.clipFileName ?: return null
        val file = File(mediaDir, fileName)
        return if (file.exists()) file else null
    }

    fun createTestEvent(snapshotJpeg: ByteArray?): DetectionEvent {
        val now = System.currentTimeMillis()
        val id = UUID.randomUUID().toString()
        val snapshotFileName = if (snapshotJpeg != null && snapshotJpeg.isNotEmpty()) {
            val name = "${id}_snapshot.jpg"
            File(mediaDir, name).writeBytes(snapshotJpeg)
            name
        } else {
            null
        }

        val event = DetectionEvent(
            id = id,
            type = "test",
            score = 1.0,
            startTimeMs = now,
            endTimeMs = now,
            snapshotFileName = snapshotFileName,
            clipFileName = null,
            createdAtMs = now
        )
        addEvent(event)
        return event
    }

    fun createDetectionEvent(
        type: String,
        score: Double?,
        snapshotJpeg: ByteArray?,
        tags: List<String> = listOf(type),
        recording: Boolean = false,
        visitId: String? = null
    ): DetectionEvent {
        val now = System.currentTimeMillis()
        val id = UUID.randomUUID().toString()
        val snapshotFileName = if (snapshotJpeg != null && snapshotJpeg.isNotEmpty()) {
            val name = "${id}_snapshot.jpg"
            File(mediaDir, name).writeBytes(snapshotJpeg)
            name
        } else {
            null
        }

        val event = DetectionEvent(
            id = id,
            type = type,
            score = score,
            startTimeMs = now,
            endTimeMs = now,
            snapshotFileName = snapshotFileName,
            clipFileName = null,
            createdAtMs = now,
            tags = tags,
            recording = recording,
            // A clip event always belongs to a visit: the one it continues, or -- for a
            // first part -- its own, which only exists once the id above does.
            visitId = visitId ?: if (recording) id else null
        )
        addEvent(event)
        return event
    }

    /**
     * Adds [tag] to event [id], raising its type if the tag is more specific -- a clip
     * that began as motion becomes an "animal" event once an animal is seen in it.
     * Returns false if the event has been evicted.
     */
    fun tagEvent(id: String, tag: String): Boolean {
        synchronized(lock) {
            val events = readEventsInternal()
            val index = events.indexOfFirst { it.id == id }
            if (index < 0) return false
            val tagged = events[index].withTag(tag)
            if (tagged != events[index]) {
                events[index] = tagged
                writeEventsInternal(events)
            }
            return true
        }
    }

    fun cleanupExpired(nowMs: Long = System.currentTimeMillis()): Int {
        synchronized(lock) {
            val events = readEventsInternal()
            val cutoff = nowMs - retentionMs
            val keep = mutableListOf<DetectionEvent>()
            val remove = mutableListOf<DetectionEvent>()

            for (event in events) {
                val eventTime = event.endTimeMs ?: event.startTimeMs
                if (eventTime < cutoff) {
                    remove.add(event)
                } else {
                    keep.add(event)
                }
            }

            for (event in remove) {
                deleteMediaFor(event)
            }

            writeEventsInternal(enforceMaxEvents(keep))
            return remove.size
        }
    }

    /** Where the clip for event [id] is written while it records. */
    fun clipFileFor(id: String): File = File(mediaDir, "${id}_clip.mp4")

    /**
     * Records that event [id]'s clip is complete, then evicts the oldest events until
     * media fits under [maxMediaBytes]. Returns false, and deletes the clip, if the event
     * was evicted while its clip was still recording.
     *
     * [held]: this is a finished part of a recording that continues in another file. Its
     * clip is attached but it stays `recording`, so the archive skips it (and
     * archiveEvent refuses it) until [releaseVisit] ends the whole recording at once --
     * otherwise the puller could copy and delete an early part of a recording that is
     * later discarded.
     */
    fun attachClip(
        id: String, clipFile: File, endTimeMs: Long, durationMs: Long?,
        clipStartMs: Long?, detectionsJson: String?, activityJson: String?,
        held: Boolean = false
    ): Boolean {
        synchronized(lock) {
            val events = readEventsInternal()
            val index = events.indexOfFirst { it.id == id }
            if (index < 0) {
                clipFile.delete()
                return false
            }
            events[index] = events[index].copy(
                clipFileName = clipFile.name, endTimeMs = endTimeMs, clipDurationMs = durationMs,
                recording = held, clipBytes = clipFile.length(), clipStartMs = clipStartMs,
                detectionsJson = detectionsJson, activityJson = activityJson
            )
            writeEventsInternal(enforceMaxMediaBytes(events))
            return true
        }
    }

    /** Removes event [id] and its media: a discarded recording. */
    fun discardEvent(id: String): Boolean = removeEvent(id) != null

    /**
     * The recording [visitId] has ended: every part of it with a clip attached (the held
     * ones) stops reporting `recording`, so the archive can take them together. A part
     * still being written has no clip yet and is left alone. Returns parts released.
     */
    fun releaseVisit(visitId: String): Int {
        synchronized(lock) {
            val events = readEventsInternal()
            var released = 0
            for (i in events.indices) {
                val e = events[i]
                if (e.visitId != visitId || !e.recording || e.clipFileName == null) continue
                events[i] = e.copy(recording = false)
                released++
            }
            if (released > 0) writeEventsInternal(events)
            return released
        }
    }

    /**
     * Discard of a recording: removes every part of [visitId] and its media, including a
     * part still being written. Returns events removed. Parts already released under media
     * pressure (see [enforceMaxMediaBytes]) are deleted only if still on the device -- the
     * archive may already have taken them.
     */
    fun discardVisit(visitId: String): Int {
        synchronized(lock) {
            val events = readEventsInternal()
            val (drop, keep) = events.partition { it.visitId == visitId }
            if (drop.isEmpty()) return 0
            for (event in drop) {
                deleteMediaFor(event)
                clipFileFor(event.id).delete()
            }
            writeEventsInternal(keep)
            return drop.size
        }
    }

    /** The archive has a verified copy of [id]; drop the local one unless it is still recording. */
    fun archiveEvent(id: String): ArchiveResult {
        synchronized(lock) {
            val event = readEventsInternal().firstOrNull { it.id == id } ?: return ArchiveResult.NOT_FOUND
            if (event.recording) return ArchiveResult.RECORDING
            removeEvent(id)
            return ArchiveResult.DELETED
        }
    }

    /**
     * A process killed mid-clip leaves its event marked recording and an MP4 with no
     * index (MediaMuxer writes it on stop), which nothing can play. Clear the flag and
     * drop the file so the event archives as snapshot-only. Returns events recovered.
     *
     * An event still `recording` with its clip attached is different: a held, finalised
     * part of a recording that never got to end (see attachClip's `held`). Its clip is
     * complete, so it is released and kept -- the recording cannot continue after a
     * restart anyway.
     */
    fun recoverInterrupted(): Int {
        synchronized(lock) {
            val events = readEventsInternal()
            var recovered = 0
            for (i in events.indices) {
                if (!events[i].recording) continue
                if (events[i].clipFileName != null) {
                    events[i] = events[i].copy(recording = false)
                } else {
                    clipFileFor(events[i].id).delete()
                    events[i] = events[i].copy(recording = false, clipFileName = null)
                }
                recovered++
            }
            if (recovered > 0) writeEventsInternal(events)
            return recovered
        }
    }

    /**
     * Clears event [id]'s recording flag and drops its partial clip, the same recovery
     * [recoverInterrupted] does at startup -- but immediately, for a clip that failed to
     * start or finish mid-session, so the event does not stay stuck reporting RECORDING to
     * /events/<id>/archived until the next restart. Returns false, and changes nothing, if
     * the event does not exist, was not marked recording, or already has its clip attached
     * (a held part: its clip is complete, and [releaseVisit] is what ends it).
     */
    fun clearRecording(id: String): Boolean {
        synchronized(lock) {
            val events = readEventsInternal()
            val index = events.indexOfFirst { it.id == id }
            if (index < 0 || !events[index].recording || events[index].clipFileName != null) return false
            clipFileFor(id).delete()
            events[index] = events[index].copy(recording = false, clipFileName = null)
            writeEventsInternal(events)
            return true
        }
    }

    private fun removeEvent(id: String): DetectionEvent? {
        synchronized(lock) {
            val events = readEventsInternal()
            val event = events.firstOrNull { it.id == id } ?: return null
            deleteMediaFor(event)
            clipFileFor(id).delete()
            events.remove(event)
            writeEventsInternal(events)
            return event
        }
    }

    fun listRecentEvents(limit: Int = 100): List<DetectionEvent> {
        return listEvents(null, limit.coerceIn(1, 500))
    }

    /**
     * Attaches a caption to an already-stored event.
     *
     * Captions arrive seconds after the event itself, because on-device description is
     * far slower than detection and must not hold up storing the event. Returns false if
     * the event has since been evicted by retention, which is not an error.
     */
    fun setCaption(id: String, caption: String): Boolean {
        synchronized(lock) {
            val events = readEventsInternal()
            val index = events.indexOfFirst { it.id == id }
            if (index < 0) return false
            events[index] = events[index].copy(caption = caption)
            writeEventsInternal(events)
            return true
        }
    }

    private fun addEvent(event: DetectionEvent) {
        synchronized(lock) {
            val events = readEventsInternal()
            events.add(event)
            writeEventsInternal(enforceMaxEvents(events))
        }
    }

    /**
     * Drops the oldest events, and their media, once the store exceeds [maxEvents].
     * Caller must hold [lock].
     */
    private fun enforceMaxEvents(events: MutableList<DetectionEvent>): List<DetectionEvent> {
        if (events.size <= maxEvents) return events

        val sorted = events.sortedByDescending { it.startTimeMs }
        val keep = sorted.take(maxEvents)
        for (event in sorted.drop(maxEvents)) {
            deleteMediaFor(event)
        }
        return keep
    }

    /**
     * Drops the oldest events, and their media, until what remains fits in
     * [maxMediaBytes]. The newest event is always kept. Caller must hold [lock].
     *
     * Release under pressure: when media is over the cap, every held part (see
     * attachClip's `held`: still `recording`, clip attached) is first released --
     * `recording = false`, clip kept -- and is exempt from eviction in this same pass, so
     * the archive gets a chance to copy it. All held parts go at once rather than just
     * enough to fit, so a recording's parts still become archivable together. Released
     * parts still count toward the total, so older events are evicted to make room; on a
     * later over-cap pass they are ordinary finished events and are evicted like any other.
     * Without this, one long recording (roughly 4 h at frankie's ~1-1.35 Mbit/s, sooner
     * with un-archived clips present) would delete its own early parts unseen.
     *
     * The cost: Discard of a recording that long can no longer delete the parts already
     * released here -- once released they may already be archived, and discardVisit only
     * deletes what is still on the device.
     */
    private fun enforceMaxMediaBytes(events: MutableList<DetectionEvent>): List<DetectionEvent> {
        val released = mutableSetOf<String>()
        if (events.sumOf { mediaBytesOf(it) } > maxMediaBytes) {
            for (i in events.indices) {
                val e = events[i]
                if (!e.recording || e.clipFileName == null) continue
                events[i] = e.copy(recording = false)
                released += e.id
            }
        }
        val newestFirst = events.sortedByDescending { it.startTimeMs }
        var total = 0L
        var full = false
        val keep = mutableListOf<DetectionEvent>()
        for (event in newestFirst) {
            val size = mediaBytesOf(event)
            if (event.id in released) {
                total += size
                keep.add(event)
            } else if (full || (keep.isNotEmpty() && total + size > maxMediaBytes)) {
                // Everything older goes too, so eviction stays strictly oldest-first.
                full = true
                deleteMediaFor(event)
            } else {
                total += size
                keep.add(event)
            }
        }
        return keep
    }

    private fun mediaBytesOf(event: DetectionEvent): Long =
        listOfNotNull(event.snapshotFileName, event.clipFileName)
            .sumOf { File(mediaDir, it).length() }

    private fun deleteMediaFor(event: DetectionEvent) {
        event.snapshotFileName?.let { File(mediaDir, it).delete() }
        event.clipFileName?.let { File(mediaDir, it).delete() }
    }

    private fun listEvents(sinceMs: Long?, limit: Int): List<DetectionEvent> {
        synchronized(lock) {
            val events = readEventsInternal()
                .asSequence()
                .sortedByDescending { it.startTimeMs }
                .filter { sinceMs == null || it.startTimeMs >= sinceMs }
                .take(limit)
                .toList()
            return events
        }
    }

    fun getEvent(id: String): DetectionEvent? {
        synchronized(lock) {
            return readEventsInternal().firstOrNull { it.id == id }
        }
    }

    private fun readEventsInternal(): MutableList<DetectionEvent> {
        val text = try {
            metadataFile.readText()
        } catch (_: Exception) {
            "[]"
        }
        val arr = try {
            JSONArray(text)
        } catch (_: Exception) {
            JSONArray()
        }

        val result = mutableListOf<DetectionEvent>()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            try {
                result.add(DetectionEvent.fromJsonObject(obj))
            } catch (_: Exception) {
                // Skip malformed event entries instead of failing whole load.
            }
        }
        return result
    }

    /**
     * Writes the index atomically: a temp file that is then renamed over the real one.
     *
     * Writing in place meant a process kill partway through -- which for a foreground
     * camera service is routine -- left truncated JSON and lost every stored event.
     */
    private fun writeEventsInternal(events: List<DetectionEvent>) {
        val arr = JSONArray()
        for (event in events) {
            arr.put(event.toJsonObject())
        }

        val tempFile = File(eventsDir, "events.json.tmp")
        try {
            tempFile.writeText(arr.toString())
            if (!tempFile.renameTo(metadataFile)) {
                // Some filesystems refuse a rename onto an existing file.
                metadataFile.delete()
                if (!tempFile.renameTo(metadataFile)) {
                    metadataFile.writeText(arr.toString())
                }
            }
        } finally {
            if (tempFile.exists()) tempFile.delete()
        }
    }
}
