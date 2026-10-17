package com.zektopic.cctvapp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream

class Mp4BoxWalkTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun be32(v: Long) = byteArrayOf((v shr 24).toByte(), (v shr 16).toByte(), (v shr 8).toByte(), v.toByte())
    private fun be64(v: Long) = ByteArray(8) { (v shr (56 - 8 * it)).toByte() }

    /** A box with the usual 32-bit size. */
    private fun box(type: String, payload: ByteArray) = be32(8L + payload.size) + type.toByteArray() + payload

    /** A box with the 64-bit size form (size field 1). */
    private fun largeBox(type: String, payload: ByteArray) =
        be32(1) + type.toByteArray() + be64(16L + payload.size) + payload

    /** A box whose size field is 0: it runs to the end of the file. */
    private fun toEnd(type: String, payload: ByteArray) = be32(0) + type.toByteArray() + payload

    private fun join(vararg parts: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        parts.forEach { out.write(it) }
        return out.toByteArray()
    }

    private val ftyp = box("ftyp", "isom".toByteArray() + ByteArray(8))
    private val moov = box("moov", ByteArray(40) { 1 })
    private val mdat = box("mdat", ByteArray(60) { 2 })

    @Test
    fun `a whole file passes, with the index before or after the media data`() {
        assertNull(Mp4BoxWalk.check(join(ftyp, moov, mdat)))
        assertNull(Mp4BoxWalk.check(join(ftyp, mdat, moov)))
        assertNull(Mp4BoxWalk.check(join(ftyp, box("free", ByteArray(12)), moov, mdat)))
    }

    @Test
    fun `a file cut at any byte count is rejected`() {
        for (layout in listOf(join(ftyp, moov, mdat), join(ftyp, mdat, moov))) {
            for (cut in 0 until layout.size) {
                assertNotNull("cut at $cut of ${layout.size}", Mp4BoxWalk.check(layout.copyOf(cut)))
            }
        }
    }

    @Test
    fun `64-bit sizes are walked, and a cut one is rejected at every byte count`() {
        val file = join(ftyp, moov, largeBox("mdat", ByteArray(60) { 2 }))
        assertNull(Mp4BoxWalk.check(file))
        for (cut in 0 until file.size) assertNotNull("cut at $cut", Mp4BoxWalk.check(file.copyOf(cut)))
    }

    @Test
    fun `a 64-bit size that is huge or impossible is rejected`() {
        val huge = join(ftyp, moov, be32(1), "mdat".toByteArray(), be64(0x3F3F3F3F3F3F3F3FL), ByteArray(10))
        assertTrue(Mp4BoxWalk.check(huge)!!.contains("declares"))
        val negative = join(ftyp, moov, be32(1), "mdat".toByteArray(), be64(-1L), ByteArray(10))
        assertTrue(Mp4BoxWalk.check(negative)!!.contains("impossible"))
        val tiny = join(ftyp, moov, be32(1), "mdat".toByteArray(), be64(9L), ByteArray(10))
        assertNotNull(Mp4BoxWalk.check(tiny))
    }

    @Test
    fun `a last media box with size 0 runs to the end and is accepted when the index is there`() {
        assertNull(Mp4BoxWalk.check(join(ftyp, moov, toEnd("mdat", ByteArray(60) { 2 }))))
    }

    @Test
    fun `what a muxer that never ran stop leaves is rejected`() {
        // Media data with a zero size field and no index.
        assertEquals(
            "no 'moov' box: the file was never finalised",
            Mp4BoxWalk.check(join(ftyp, toEnd("mdat", ByteArray(500) { 3 })))
        )
        // Media data whose size field was never filled in, or is garbage.
        val garbled = join(ftyp, be32(0x3F3F3F3FL), "mdat".toByteArray(), ByteArray(500) { 3 })
        assertTrue(Mp4BoxWalk.check(garbled)!!.contains("declares"))
    }

    @Test
    fun `an mdat that declares more bytes than exist is rejected`() {
        val claims = join(ftyp, moov, be32(8L + 100), "mdat".toByteArray(), ByteArray(60))
        assertEquals(
            "box 'mdat' at ${ftyp.size + moov.size} declares 108 bytes but only 68 remain",
            Mp4BoxWalk.check(claims)
        )
    }

    @Test
    fun `bytes after the last box are rejected`() {
        val whole = join(ftyp, moov, mdat)
        assertNotNull("one stray byte", Mp4BoxWalk.check(join(whole, byteArrayOf(0))))
        assertNotNull("seven stray bytes", Mp4BoxWalk.check(join(whole, ByteArray(7))))
        assertNotNull("eight zero bytes read as a box with size 0", Mp4BoxWalk.check(join(whole, ByteArray(8))))
        assertNotNull("random-looking tail", Mp4BoxWalk.check(join(whole, ByteArray(300) { 0x5A })))
    }

    @Test
    fun `ftyp must come first, and moov and mdat must both be there`() {
        assertEquals("first box is 'moov', not 'ftyp'", Mp4BoxWalk.check(join(moov, ftyp, mdat)))
        assertEquals("no 'mdat' box", Mp4BoxWalk.check(join(ftyp, moov)))
        assertEquals("no 'moov' box: the file was never finalised", Mp4BoxWalk.check(join(ftyp, mdat)))
    }

    @Test
    fun `a box too small to hold its own header, or a sized-zero box that is not media data, is rejected`() {
        assertNotNull(Mp4BoxWalk.check(join(ftyp, be32(4), "moov".toByteArray(), mdat)))
        assertNotNull(Mp4BoxWalk.check(join(ftyp, toEnd("moov", ByteArray(20)), mdat)))
    }

    @Test
    fun `the file form reads the same answers as the array form`() {
        val whole = join(ftyp, moov, mdat)
        val good = tempFolder.newFile("good.mp4").also { it.writeBytes(whole) }
        val cut = tempFolder.newFile("cut.mp4").also { it.writeBytes(whole.copyOf(whole.size - 1)) }
        val empty = tempFolder.newFile("empty.mp4")
        assertNull(Mp4BoxWalk.check(good))
        assertEquals(Mp4BoxWalk.check(whole.copyOf(whole.size - 1)), Mp4BoxWalk.check(cut))
        assertEquals("the file is empty", Mp4BoxWalk.check(empty))
        assertTrue(Mp4BoxWalk.check(java.io.File(tempFolder.root, "missing.mp4"))!!.startsWith("could not read"))
    }
}
