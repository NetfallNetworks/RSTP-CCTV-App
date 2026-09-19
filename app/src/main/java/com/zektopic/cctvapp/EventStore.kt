package com.zektopic.cctvapp

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

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
        const val DEFAULT_MAX_MEDIA_BYTES: Long = 1_500L * 1024L * 1024L

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

    fun createDetectionEvent(type: String, score: Double?, snapshotJpeg: ByteArray?): DetectionEvent {
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
            createdAtMs = now
        )
        addEvent(event)
        return event
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
     */
    fun attachClip(id: String, clipFile: File, endTimeMs: Long): Boolean {
        synchronized(lock) {
            val events = readEventsInternal()
            val index = events.indexOfFirst { it.id == id }
            if (index < 0) {
                clipFile.delete()
                return false
            }
            events[index] = events[index].copy(clipFileName = clipFile.name, endTimeMs = endTimeMs)
            writeEventsInternal(enforceMaxMediaBytes(events))
            return true
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
     */
    private fun enforceMaxMediaBytes(events: MutableList<DetectionEvent>): List<DetectionEvent> {
        val newestFirst = events.sortedByDescending { it.startTimeMs }
        var total = 0L
        var full = false
        val keep = mutableListOf<DetectionEvent>()
        for (event in newestFirst) {
            val size = mediaBytesOf(event)
            if (full || (keep.isNotEmpty() && total + size > maxMediaBytes)) {
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

    private fun getEvent(id: String): DetectionEvent? {
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
