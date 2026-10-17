package com.zektopic.cctvapp

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.util.zip.CRC32

/*
 * A crash-safe record of every sample a clip writes.
 *
 * An MP4 from MediaMuxer only gets its index on stop(), so a finalise failure, a crash, an OOM
 * kill or a reboot loses the whole file. The journal sits beside it (`<eventId>_clip.journal`)
 * and is append-only: a header describing the tracks, then one record per sample in exactly the
 * order it was handed to the muxer. If the MP4 cannot be finalised, the journal can be replayed
 * into a fresh muxer (see [ClipRecovery]). It is deleted once the MP4 has been verified.
 *
 * Plain Kotlin with no Android types, so it is covered by JVM unit tests. All timestamps are the
 * clip's own timeline (microseconds from its first frame), the same values the muxer is given.
 */

class JournalVideoInfo(
    val mime: String, val width: Int, val height: Int,
    /** -1 when the format did not say. */
    val frameRate: Int, val bitrate: Int,
    val csd0: ByteArray?, val csd1: ByteArray?
)

class JournalAudioInfo(val mime: String, val sampleRate: Int, val channels: Int, val csd0: ByteArray?)

class JournalHeader(
    /** Wall-clock time of the clip's first frame, so a rebuilt orphan keeps its start time. */
    val clipStartMs: Long,
    val video: JournalVideoInfo,
    /** Null when the clip has no audio track. */
    val audio: JournalAudioInfo?
)

class JournalSample(val isVideo: Boolean, val ptsUs: Long, val keyFrame: Boolean, val data: ByteArray)

/** What a journal holds, found by reading it through once. */
class JournalScan(
    val header: JournalHeader,
    val videoSamples: Int,
    val audioSamples: Int,
    val firstPtsUs: Long,
    val lastPtsUs: Long,
    val videoFirstPtsUs: Long = firstPtsUs,
    val videoLastPtsUs: Long = lastPtsUs
) {
    val spanUs: Long get() = if (videoSamples + audioSamples == 0) 0 else lastPtsUs - firstPtsUs

    /** The span of the video track alone: what a video-only rebuild covers. */
    val videoSpanUs: Long get() = if (videoSamples == 0) 0 else videoLastPtsUs - videoFirstPtsUs
}

/** `<eventId>_clip.mp4` is journaled in `<eventId>_clip.journal`, in the same directory. */
fun journalFileFor(clipFile: File): File = File(clipFile.parentFile, clipFile.nameWithoutExtension + ".journal")

/** "CJ02": version 2 added a CRC32 after every record. Version 1 journals are refused. */
private const val MAGIC = 0x434A3032
/** type (1) + ptsUs (8) + flags (1) + length (4): the part of a record the CRC covers before the payload. */
private const val RECORD_HEAD_BYTES = 14
private const val TYPE_VIDEO = 1
private const val TYPE_AUDIO = 2
private const val FLAG_KEY = 1
private const val MAX_SAMPLE_BYTES = 16 * 1024 * 1024
private const val MAX_CSD_BYTES = 1024 * 1024

/**
 * Appends samples through a small buffer (32 KiB, so memory cost is negligible) that is flushed
 * to the file about every [flushIntervalMs], so a process death loses at most that much. The
 * disk cost is one more write of the stream bitrate.
 */
open class ClipJournalWriter(
    val file: File,
    header: JournalHeader,
    private val flushIntervalMs: Long = 1_000L,
    private val clock: () -> Long = System::currentTimeMillis
) : Closeable {
    private val fileStream = FileOutputStream(file)
    private val out = DataOutputStream(BufferedOutputStream(fileStream, 32 * 1024))
    private var lastFlushMs = clock()
    private var closed = false

    var videoSamples = 0
        private set
    var audioSamples = 0
        private set
    private var firstPtsUs = Long.MAX_VALUE
    private var lastPtsUs = Long.MIN_VALUE

    /** Microseconds from the first to the last sample appended; 0 when none. */
    val spanUs: Long get() = if (videoSamples + audioSamples == 0) 0 else lastPtsUs - firstPtsUs

    init {
        try {
            out.writeInt(MAGIC)
            writeString(header.video.mime)
            out.writeLong(header.clipStartMs)
            out.writeInt(header.video.width)
            out.writeInt(header.video.height)
            out.writeInt(header.video.frameRate)
            out.writeInt(header.video.bitrate)
            writeBytes(header.video.csd0)
            writeBytes(header.video.csd1)
            out.writeBoolean(header.audio != null)
            header.audio?.let {
                writeString(it.mime)
                out.writeInt(it.sampleRate)
                out.writeInt(it.channels)
                writeBytes(it.csd0)
            }
            flush()
        } catch (e: IOException) {
            close()
            throw e
        }
    }

    @Synchronized
    open fun append(isVideo: Boolean, ptsUs: Long, keyFrame: Boolean, data: ByteArray) {
        val head = ByteBuffer.allocate(RECORD_HEAD_BYTES)
            .put((if (isVideo) TYPE_VIDEO else TYPE_AUDIO).toByte())
            .putLong(ptsUs)
            .put((if (keyFrame) FLAG_KEY else 0).toByte())
            .putInt(data.size)
            .array()
        // The CRC covers the whole record, so what a power cut leaves behind (a torn or
        // zero-filled tail, garbage where a record should be) is never replayed as a sample.
        val crc = CRC32().apply { update(head); update(data) }
        out.write(head)
        out.write(data)
        out.writeInt(crc.value.toInt())
        if (isVideo) videoSamples++ else audioSamples++
        if (ptsUs < firstPtsUs) firstPtsUs = ptsUs
        if (ptsUs > lastPtsUs) lastPtsUs = ptsUs
        if (clock() - lastFlushMs >= flushIntervalMs) flush()
    }

    /** Pushes buffered samples to the file. */
    @Synchronized
    fun flush() {
        out.flush()
        lastFlushMs = clock()
    }

    /**
     * Flushes and closes. Not fsync'd: that would make the encoder thread wait for the disk at
     * every clip end, and the journal's job is to survive the process dying (the OS has the
     * bytes), the same bar the MP4 itself is held to.
     */
    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        try {
            out.flush()
        } catch (_: IOException) {
        } finally {
            try { out.close() } catch (_: IOException) {}
        }
    }

    private fun writeString(s: String) = out.writeUTF(s)

    private fun writeBytes(b: ByteArray?) {
        if (b == null) {
            out.writeInt(-1)
        } else {
            out.writeInt(b.size)
            out.write(b)
        }
    }
}

/**
 * Reads a journal one sample at a time, so a rebuild never holds the clip in memory. A torn or
 * zero-filled tail (the process died mid-record) ends the stream cleanly: [next] returns null.
 */
class ClipJournalReader private constructor(
    private val input: DataInputStream,
    val header: JournalHeader
) : Closeable {

    companion object {
        /** The journal's header, ready to read samples from; null if it is not a readable journal. */
        fun open(file: File): ClipJournalReader? {
            val input = try {
                DataInputStream(BufferedInputStream(FileInputStream(file), 64 * 1024))
            } catch (_: IOException) {
                return null
            }
            return try {
                if (input.readInt() != MAGIC) throw IOException("not a clip journal")
                val videoMime = input.readUTF()
                val clipStartMs = input.readLong()
                val width = input.readInt()
                val height = input.readInt()
                val frameRate = input.readInt()
                val bitrate = input.readInt()
                val csd0 = readBytes(input)
                val csd1 = readBytes(input)
                val audio = if (input.readBoolean()) {
                    JournalAudioInfo(input.readUTF(), input.readInt(), input.readInt(), readBytes(input))
                } else {
                    null
                }
                ClipJournalReader(
                    input,
                    JournalHeader(clipStartMs, JournalVideoInfo(videoMime, width, height, frameRate, bitrate, csd0, csd1), audio)
                )
            } catch (_: IOException) {
                try { input.close() } catch (_: IOException) {}
                null
            }
        }

        /** Counts and the time span of what [file] holds; null if its header is unreadable. */
        fun scan(file: File): JournalScan? {
            val reader = open(file) ?: return null
            reader.use {
                var video = 0
                var audio = 0
                var first = Long.MAX_VALUE
                var last = Long.MIN_VALUE
                var videoFirst = Long.MAX_VALUE
                var videoLast = Long.MIN_VALUE
                while (true) {
                    val s = reader.next() ?: break
                    if (s.isVideo) {
                        video++
                        if (s.ptsUs < videoFirst) videoFirst = s.ptsUs
                        if (s.ptsUs > videoLast) videoLast = s.ptsUs
                    } else {
                        audio++
                    }
                    if (s.ptsUs < first) first = s.ptsUs
                    if (s.ptsUs > last) last = s.ptsUs
                }
                return JournalScan(reader.header, video, audio, first, last, videoFirst, videoLast)
            }
        }

        private fun readBytes(input: DataInputStream): ByteArray? {
            val n = input.readInt()
            if (n == -1) return null
            if (n < 0 || n > MAX_CSD_BYTES) throw IOException("bad csd length $n")
            return ByteArray(n).also { input.readFully(it) }
        }
    }

    private var ended = false

    private fun stop(): JournalSample? {
        ended = true
        return null
    }

    /** The next sample, or null at the end of the journal, at a torn record, or at the first record that fails its CRC. */
    fun next(): JournalSample? {
        if (ended) return null
        try {
            val head = ByteArray(RECORD_HEAD_BYTES)
            input.readFully(head)
            val h = ByteBuffer.wrap(head)
            val type = h.get().toInt() and 0xFF
            if (type != TYPE_VIDEO && type != TYPE_AUDIO) return stop() // zero fill or garbage
            val ptsUs = h.getLong()
            val flags = h.get().toInt() and 0xFF
            val length = h.getInt()
            if (length < 0 || length > MAX_SAMPLE_BYTES) return stop()
            val data = ByteArray(length)
            input.readFully(data)
            val stored = input.readInt()
            val crc = CRC32().apply { update(head); update(data) }
            if (crc.value.toInt() != stored) return stop() // damaged record: nothing after it can be trusted
            return JournalSample(type == TYPE_VIDEO, ptsUs, flags and FLAG_KEY != 0, data)
        } catch (_: EOFException) {
            return stop()
        } catch (_: IOException) {
            return stop()
        }
    }

    override fun close() {
        try { input.close() } catch (_: IOException) {}
    }
}
