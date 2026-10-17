package com.zektopic.cctvapp

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ClipJournalTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val video = JournalVideoInfo("video/avc", 1600, 1200, 30, 1_300_000, byteArrayOf(0, 0, 0, 1, 0x67), byteArrayOf(0, 0, 0, 1, 0x68))
    private val audio = JournalAudioInfo("audio/mp4a-latm", 48_000, 1, byteArrayOf(0x11, 0x90.toByte()))

    private fun journalFile() = File(tempFolder.root, "e1_clip.journal")

    private fun write(
        header: JournalHeader,
        samples: List<Triple<Boolean, Long, Boolean>> = emptyList(),
        clock: () -> Long = { 0L }
    ): File {
        val w = ClipJournalWriter(journalFile(), header, clock = clock)
        for ((isVideo, pts, key) in samples) w.append(isVideo, pts, key, byteArrayOf(pts.toByte(), 7, 7))
        w.close()
        return journalFile()
    }

    private fun readAll(file: File): List<JournalSample> {
        val reader = ClipJournalReader.open(file)!!
        return reader.use { generateSequence { it.next() }.toList() }
    }

    @Test
    fun `header and samples round trip in order, video and audio interleaved`() {
        val file = write(
            JournalHeader(1_700_000_000_000L, video, audio),
            listOf(Triple(true, 0L, true), Triple(false, 5_000L, false), Triple(true, 33_333L, false), Triple(false, 26_333L, false))
        )

        val reader = ClipJournalReader.open(file)!!
        val h = reader.header
        assertEquals(1_700_000_000_000L, h.clipStartMs)
        assertEquals("video/avc", h.video.mime)
        assertEquals(1600, h.video.width)
        assertEquals(1200, h.video.height)
        assertEquals(30, h.video.frameRate)
        assertEquals(1_300_000, h.video.bitrate)
        assertArrayEquals(byteArrayOf(0, 0, 0, 1, 0x67), h.video.csd0)
        assertArrayEquals(byteArrayOf(0, 0, 0, 1, 0x68), h.video.csd1)
        assertEquals("audio/mp4a-latm", h.audio!!.mime)
        assertEquals(48_000, h.audio!!.sampleRate)
        assertEquals(1, h.audio!!.channels)
        assertArrayEquals(byteArrayOf(0x11, 0x90.toByte()), h.audio!!.csd0)
        reader.close()

        val samples = readAll(file)
        assertEquals(listOf(true, false, true, false), samples.map { it.isVideo })
        assertEquals(listOf(0L, 5_000L, 33_333L, 26_333L), samples.map { it.ptsUs })
        assertEquals(listOf(true, false, false, false), samples.map { it.keyFrame })
        assertArrayEquals(byteArrayOf(33_333L.toByte(), 7, 7), samples[2].data)
    }

    @Test
    fun `a journal without audio round trips with no audio header`() {
        val file = write(JournalHeader(5L, JournalVideoInfo("video/avc", 640, 480, -1, -1, null, null), null),
            listOf(Triple(true, 0L, true)))
        val h = ClipJournalReader.open(file)!!.use { it.header }
        assertNull(h.audio)
        assertNull(h.video.csd0)
        assertNull(h.video.csd1)
        assertEquals(-1, h.video.frameRate)
        assertEquals(1, readAll(file).size)
    }

    @Test
    fun `a header-only journal reads as empty`() {
        val file = write(JournalHeader(1L, video, null))
        assertTrue(readAll(file).isEmpty())
        val scan = ClipJournalReader.scan(file)!!
        assertEquals(0, scan.videoSamples)
        assertEquals(0L, scan.spanUs)
    }

    @Test
    fun `a journal with no audio samples scans with zero audio`() {
        val file = write(JournalHeader(1L, video, audio), listOf(Triple(true, 0L, true), Triple(true, 33_333L, false)))
        val scan = ClipJournalReader.scan(file)!!
        assertEquals(2, scan.videoSamples)
        assertEquals(0, scan.audioSamples)
        assertEquals(33_333L, scan.spanUs)
        assertNotNull(scan.header.audio)
    }

    @Test
    fun `a torn tail record is ignored and everything before it is kept`() {
        val file = write(JournalHeader(1L, video, audio), (0 until 10).map { Triple(true, it * 33_333L, it == 0) })
        // The process died partway through the next record: chop the file mid-record.
        val full = file.readBytes()
        for (cut in 1..20) {
            file.writeBytes(full.copyOf(full.size - cut))
            val samples = readAll(file)
            assertEquals("cut $cut", 9, samples.size)
            assertEquals(samples.indices.map { it * 33_333L }, samples.map { it.ptsUs })
        }
    }

    /** Bytes per record in these tests: 14 head + 3 payload + 4 CRC. */
    private val recordBytes = 21

    @Test
    fun `a damaged record in the middle ends the stream there, whatever field was hit`() {
        val file = write(JournalHeader(1L, video, audio), (0 until 10).map { Triple(true, it * 33_333L, it == 0) })
        val original = file.readBytes()
        // Record 4 (the fifth): flip a payload byte, then a pts byte, then a length byte, then the CRC itself.
        for (offsetInRecord in listOf(15, 3, 11, 19)) {
            val bytes = original.copyOf()
            val start = bytes.size - (10 - 4) * recordBytes
            bytes[start + offsetInRecord] = (bytes[start + offsetInRecord].toInt() xor 0x40).toByte()
            file.writeBytes(bytes)

            val samples = readAll(file)
            assertEquals("offset $offsetInRecord", 4, samples.size)
            assertEquals(samples.indices.map { it * 33_333L }, samples.map { it.ptsUs })
        }
    }

    @Test
    fun `a record that is only partly overwritten by garbage after power loss is not replayed`() {
        val file = write(JournalHeader(1L, video, null), (0 until 3).map { Triple(true, it * 33_333L, it == 0) })
        // A full-looking record with the right type byte and a plausible length but wrong contents.
        file.appendBytes(byteArrayOf(1, 0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 3, 9, 9, 9, 1, 2, 3, 4))
        assertEquals(3, readAll(file).size)
    }

    @Test
    fun `a journal in the old format without checksums is refused`() {
        val file = write(JournalHeader(1L, video, null), listOf(Triple(true, 0L, true)))
        val bytes = file.readBytes()
        // "CJ01" instead of "CJ02"
        bytes[3] = 0x31
        file.writeBytes(bytes)
        assertNull(ClipJournalReader.open(file))
        assertNull(ClipJournalReader.scan(file))
    }

    @Test
    fun `zero-filled tail from a power loss ends the stream cleanly`() {
        val file = write(JournalHeader(1L, video, null), listOf(Triple(true, 0L, true), Triple(true, 33_333L, false)))
        file.appendBytes(ByteArray(4096))
        assertEquals(2, readAll(file).size)
    }

    @Test
    fun `a record claiming an absurd length is treated as the end`() {
        val file = write(JournalHeader(1L, video, null), listOf(Triple(true, 0L, true)))
        file.appendBytes(byteArrayOf(1, 0, 0, 0, 0, 0, 0, 0, 9, 0, 0x7F, 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte()))
        assertEquals(1, readAll(file).size)
    }

    @Test
    fun `a file that is not a journal, or has a cut header, does not open`() {
        val junk = File(tempFolder.root, "junk")
        junk.writeBytes(ByteArray(100) { 3 })
        assertNull(ClipJournalReader.open(junk))
        assertNull(ClipJournalReader.scan(junk))
        assertNull(ClipJournalReader.open(File(tempFolder.root, "missing")))

        val file = write(JournalHeader(1L, video, audio))
        file.writeBytes(file.readBytes().copyOf(20))
        assertNull(ClipJournalReader.open(file))
    }

    @Test
    fun `scan reports counts and the time span`() {
        val file = write(
            JournalHeader(1L, video, audio),
            listOf(Triple(true, 100L, true), Triple(false, 120L, false), Triple(true, 1_000_100L, false), Triple(false, 1_000_000L, false))
        )
        val scan = ClipJournalReader.scan(file)!!
        assertEquals(2, scan.videoSamples)
        assertEquals(2, scan.audioSamples)
        assertEquals(100L, scan.firstPtsUs)
        assertEquals(1_000_100L, scan.lastPtsUs)
        assertEquals(1_000_000L, scan.spanUs)
    }

    @Test
    fun `the writer keeps its own counts and span`() {
        val w = ClipJournalWriter(journalFile(), JournalHeader(1L, video, audio))
        w.append(true, 0L, true, ByteArray(3))
        w.append(false, 50L, false, ByteArray(3))
        w.append(true, 2_000_000L, false, ByteArray(3))
        assertEquals(2, w.videoSamples)
        assertEquals(1, w.audioSamples)
        assertEquals(2_000_000L, w.spanUs)
        w.close()
        w.close() // idempotent
    }

    @Test
    fun `samples reach the file about once a second, not on every append`() {
        var now = 0L
        val w = ClipJournalWriter(journalFile(), JournalHeader(1L, video, null), clock = { now })
        val headerBytes = journalFile().length()

        w.append(true, 0L, true, ByteArray(100))
        now = 500
        w.append(true, 33_333L, false, ByteArray(100))
        assertEquals("buffered, nothing flushed yet", headerBytes, journalFile().length())

        now = 1_000
        w.append(true, 66_666L, false, ByteArray(100))
        assertTrue("flushed once a second has passed", journalFile().length() > headerBytes)
        assertEquals(3, readAll(journalFile()).size)
        w.close()
    }

    @Test
    fun `what was flushed survives the writer being abandoned without close`() {
        var now = 0L
        val w = ClipJournalWriter(journalFile(), JournalHeader(1L, video, null), clock = { now })
        w.append(true, 0L, true, ByteArray(10))
        now = 1_500
        w.append(true, 33_333L, false, ByteArray(10)) // flushes both
        now = 1_600
        w.append(true, 66_666L, false, ByteArray(10)) // still in the buffer: a crash loses this one

        assertFalse(readAll(journalFile()).size == 3)
        assertEquals(2, readAll(journalFile()).size)
    }
}
