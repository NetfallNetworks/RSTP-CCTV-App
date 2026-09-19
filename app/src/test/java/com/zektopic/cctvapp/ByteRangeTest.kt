package com.zektopic.cctvapp

import org.junit.Assert.assertEquals
import org.junit.Test

class ByteRangeTest {

    @Test
    fun `no header serves the whole file`() {
        assertEquals(ByteRange.Whole, ByteRange.parse(null, 1000))
    }

    @Test
    fun `an open-ended range runs to the last byte`() {
        assertEquals(ByteRange.Span(0, 999), ByteRange.parse("bytes=0-", 1000))
        assertEquals(ByteRange.Span(500, 999), ByteRange.parse("bytes=500-", 1000))
    }

    @Test
    fun `a closed range is inclusive`() {
        assertEquals(ByteRange.Span(100, 199), ByteRange.parse("bytes=100-199", 1000))
    }

    @Test
    fun `an end past the file is clamped`() {
        assertEquals(ByteRange.Span(900, 999), ByteRange.parse("bytes=900-5000", 1000))
    }

    @Test
    fun `a suffix range is the last n bytes`() {
        assertEquals(ByteRange.Span(900, 999), ByteRange.parse("bytes=-100", 1000))
        assertEquals(ByteRange.Span(0, 999), ByteRange.parse("bytes=-5000", 1000))
    }

    @Test
    fun `a start past the end is unsatisfiable`() {
        assertEquals(ByteRange.Unsatisfiable, ByteRange.parse("bytes=1000-", 1000))
        assertEquals(ByteRange.Unsatisfiable, ByteRange.parse("bytes=500-100", 1000))
        assertEquals(ByteRange.Unsatisfiable, ByteRange.parse("bytes=-0", 1000))
    }

    @Test
    fun `multiple ranges fall back to the whole file`() {
        assertEquals(ByteRange.Whole, ByteRange.parse("bytes=0-10,20-30", 1000))
    }
}
