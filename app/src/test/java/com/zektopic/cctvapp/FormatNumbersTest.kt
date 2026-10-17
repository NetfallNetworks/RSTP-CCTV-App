package com.zektopic.cctvapp

import org.junit.Assert.assertEquals
import org.junit.Test

class FormatNumbersTest {

    @Test
    fun `an int is read as an int`() {
        assertEquals(30, FormatNumbers.read(true, { 30 }, { error("not asked") }))
    }

    @Test
    fun `a float where an int was expected is rounded, not thrown over`() {
        val asFloat = { 29.97f }
        assertEquals(30, FormatNumbers.read(true, { throw ClassCastException("it is a float") }, asFloat))
        assertEquals(30, FormatNumbers.read(true, { throw IllegalArgumentException() }, { 30.0f }))
    }

    @Test
    fun `an absent key is the default`() {
        assertEquals(-1, FormatNumbers.read(false, { error("absent") }, { error("absent") }))
        assertEquals(0, FormatNumbers.read(false, { error("absent") }, { error("absent") }, default = 0))
    }

    @Test
    fun `a value that is neither int nor float, or not a number, is the default`() {
        assertEquals(-1, FormatNumbers.read(true, { throw ClassCastException() }, { throw ClassCastException() }))
        assertEquals(-1, FormatNumbers.read(true, { throw ClassCastException() }, { Float.NaN }))
        assertEquals(-1, FormatNumbers.read(true, { throw ClassCastException() }, { Float.POSITIVE_INFINITY }))
    }
}