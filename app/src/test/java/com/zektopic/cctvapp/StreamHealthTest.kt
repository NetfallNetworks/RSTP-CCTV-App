package com.zektopic.cctvapp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamHealthTest {

    @Test
    fun `starts out not streaming with no error`() {
        val health = StreamHealth()
        assertFalse(health.isStreaming)
        assertNull(health.lastError)
    }

    @Test
    fun `a completed start reports streaming with no error`() {
        val health = StreamHealth()
        health.markStarted()
        assertTrue(health.isStreaming)
        assertNull(health.lastError)
    }

    @Test
    fun `a failed start attempt never reports streaming -- this is the boot bug`() {
        // This is the exact scenario the camera-denied boot failure exercises: a start
        // was attempted and threw partway through. The status derived from this must
        // say false, unlike the RootEncoder library's own optimistic flag.
        val health = StreamHealth()
        health.markFailed("Video info is null")
        assertFalse(health.isStreaming)
        assertEquals("Video info is null", health.lastError)
    }

    @Test
    fun `a failed start after a previous success clears the streaming flag`() {
        val health = StreamHealth()
        health.markStarted()
        health.markFailed("camera access restricted")
        assertFalse(health.isStreaming)
        assertEquals("camera access restricted", health.lastError)
    }

    @Test
    fun `a deliberate stop clears streaming and does not record an error`() {
        val health = StreamHealth()
        health.markStarted()
        health.markStopped()
        assertFalse(health.isStreaming)
        assertNull(health.lastError)
    }

    @Test
    fun `a successful start after a failure clears the error`() {
        val health = StreamHealth()
        health.markFailed("boom")
        health.markStarted()
        assertTrue(health.isStreaming)
        assertNull(health.lastError)
    }

    @Test
    fun `a blank or null failure reason still records something rather than nothing`() {
        val health = StreamHealth()
        health.markFailed(null)
        assertEquals("unknown error", health.lastError)

        health.markFailed("   ")
        assertEquals("unknown error", health.lastError)
    }
}
