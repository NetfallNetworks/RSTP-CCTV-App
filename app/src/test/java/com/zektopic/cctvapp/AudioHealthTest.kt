package com.zektopic.cctvapp

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioHealthTest {

    private val second = 1_000_000L

    // --- formatTimedOut ---

    @Test
    fun `not timed out before the bound`() {
        assertFalse(AudioHealth.formatTimedOut(elapsedSinceVideoStartUs = 4 * second, timeoutUs = 5 * second))
    }

    @Test
    fun `timed out exactly at the bound`() {
        assertTrue(AudioHealth.formatTimedOut(elapsedSinceVideoStartUs = 5 * second, timeoutUs = 5 * second))
    }

    @Test
    fun `timed out well past the bound`() {
        assertTrue(AudioHealth.formatTimedOut(elapsedSinceVideoStartUs = 60 * second, timeoutUs = 5 * second))
    }

    @Test
    fun `zero elapsed is never timed out`() {
        assertFalse(AudioHealth.formatTimedOut(elapsedSinceVideoStartUs = 0, timeoutUs = 5 * second))
    }

    // --- flowing ---

    @Test
    fun `never seen any audio is not flowing`() {
        assertFalse(AudioHealth.flowing(newestAudioPtsUs = null, nowUs = 10 * second, staleAfterUs = 5 * second))
    }

    @Test
    fun `a sample from a moment ago is flowing`() {
        assertTrue(AudioHealth.flowing(newestAudioPtsUs = 9 * second, nowUs = 10 * second, staleAfterUs = 5 * second))
    }

    @Test
    fun `a sample right at the staleness bound is not flowing`() {
        // nowUs - newestAudioPtsUs == staleAfterUs: the bound is exclusive, matching
        // formatTimedOut's inclusive bound being the failure side, not the success side.
        assertFalse(AudioHealth.flowing(newestAudioPtsUs = 5 * second, nowUs = 10 * second, staleAfterUs = 5 * second))
    }

    @Test
    fun `a stale sample from long ago is not flowing`() {
        assertFalse(AudioHealth.flowing(newestAudioPtsUs = 0, nowUs = 60 * second, staleAfterUs = 5 * second))
    }

    @Test
    fun `a sample from exactly now is flowing`() {
        assertTrue(AudioHealth.flowing(newestAudioPtsUs = 10 * second, nowUs = 10 * second, staleAfterUs = 5 * second))
    }
}
