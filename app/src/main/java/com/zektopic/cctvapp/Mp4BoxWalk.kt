package com.zektopic.cctvapp

import java.io.File
import java.io.RandomAccessFile

/**
 * A structural check of an MP4 that needs no MediaExtractor: walk the top-level boxes from offset
 * 0 and require that each one fits in the file, that the walk ends exactly at the end of the file,
 * that `ftyp` comes first, and that both `moov` and `mdat` are present.
 *
 * Why it exists: an extractor trusts the sample table and, for a file whose index sits near the
 * front, reads a cut-off file happily. A file cut anywhere, a file whose `mdat` declares more
 * bytes than exist, and a file left by a muxer that never ran stop() (an `mdat` with size 0 or
 * garbage and no `moov`) all fail here. A handful of small reads, never the whole file.
 *
 * Plain Kotlin over [Source], so it is covered by JVM tests with synthetic byte arrays.
 */
object Mp4BoxWalk {

    /** Random access to the bytes being checked. */
    interface Source {
        val length: Long

        /** Fills [buffer] from [offset]; false if fewer than `buffer.size` bytes are there. */
        fun readFully(offset: Long, buffer: ByteArray): Boolean
    }

    private class ArraySource(private val bytes: ByteArray) : Source {
        override val length: Long get() = bytes.size.toLong()

        override fun readFully(offset: Long, buffer: ByteArray): Boolean {
            if (offset < 0 || offset + buffer.size > bytes.size) return false
            System.arraycopy(bytes, offset.toInt(), buffer, 0, buffer.size)
            return true
        }
    }

    /** Null when [file] is a structurally whole MP4; otherwise why not. */
    fun check(file: File): String? {
        return try {
            RandomAccessFile(file, "r").use { raf ->
                check(object : Source {
                    override val length: Long get() = raf.length()

                    override fun readFully(offset: Long, buffer: ByteArray): Boolean {
                        if (offset < 0 || offset + buffer.size > raf.length()) return false
                        raf.seek(offset)
                        raf.readFully(buffer)
                        return true
                    }
                })
            }
        } catch (e: Exception) {
            "could not read: ${ClipFailure.detailOf(e)}"
        }
    }

    fun check(bytes: ByteArray): String? = check(ArraySource(bytes))

    fun check(source: Source): String? {
        val length = source.length
        if (length == 0L) return "the file is empty"
        var pos = 0L
        var first = true
        var sawMoov = false
        var sawMdat = false
        val head = ByteArray(8)
        val large = ByteArray(8)
        while (pos < length) {
            val remaining = length - pos
            if (remaining < 8 || !source.readFully(pos, head)) {
                return "$remaining stray bytes at $pos after the last box"
            }
            val size32 = u32(head, 0)
            val type = String(head, 4, 4, Charsets.ISO_8859_1)
            var size = size32
            when {
                size32 == 1L -> {
                    if (remaining < 16 || !source.readFully(pos + 8, large)) {
                        return "box '$type' at $pos has no room for its 64-bit size"
                    }
                    size = u64(large)
                    if (size < 16) return "box '$type' at $pos declares an impossible size"
                }
                size32 == 0L -> {
                    // "Extends to the end of the file": only the media data box is written that way.
                    if (type != "mdat") return "box '$type' at $pos has size 0"
                    size = remaining
                }
                size32 < 8 -> return "box '$type' at $pos declares $size32 bytes"
            }
            if (size < 0 || size > remaining) {
                return "box '$type' at $pos declares ${if (size < 0) "an impossible size" else "$size bytes"} " +
                    "but only $remaining remain"
            }
            if (first && type != "ftyp") return "first box is '$type', not 'ftyp'"
            first = false
            if (type == "moov") sawMoov = true
            if (type == "mdat") sawMdat = true
            pos += size
        }
        if (!sawMoov) return "no 'moov' box: the file was never finalised"
        if (!sawMdat) return "no 'mdat' box"
        return null
    }

    private fun u32(b: ByteArray, at: Int): Long =
        ((b[at].toLong() and 0xFF) shl 24) or ((b[at + 1].toLong() and 0xFF) shl 16) or
            ((b[at + 2].toLong() and 0xFF) shl 8) or (b[at + 3].toLong() and 0xFF)

    /** Big-endian; a value above Long.MAX_VALUE comes out negative and is rejected by the caller. */
    private fun u64(b: ByteArray): Long {
        var v = 0L
        for (i in 0 until 8) v = (v shl 8) or (b[i].toLong() and 0xFF)
        return v
    }
}
