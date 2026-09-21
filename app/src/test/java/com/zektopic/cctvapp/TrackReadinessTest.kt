package com.zektopic.cctvapp

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackReadinessTest {

    @Test
    fun `no video format is never ready, audio expected or not`() {
        assertFalse(TrackReadiness.ready(hasVideoFormat = false, audioExpected = false, hasAudioFormat = false))
        assertFalse(TrackReadiness.ready(hasVideoFormat = false, audioExpected = true, hasAudioFormat = true))
    }

    @Test
    fun `video alone is ready when audio is not expected -- no waiting, no regression`() {
        assertTrue(TrackReadiness.ready(hasVideoFormat = true, audioExpected = false, hasAudioFormat = false))
    }

    @Test
    fun `video alone is NOT ready when audio is expected but hasn't arrived yet`() {
        assertFalse(TrackReadiness.ready(hasVideoFormat = true, audioExpected = true, hasAudioFormat = false))
    }

    @Test
    fun `ready once both formats are in, when audio is expected`() {
        assertTrue(TrackReadiness.ready(hasVideoFormat = true, audioExpected = true, hasAudioFormat = true))
    }

    @Test
    fun `an audio format that arrived is irrelevant when audio isn't expected`() {
        // e.g. a stale format left over from a previous session where audio was on.
        assertTrue(TrackReadiness.ready(hasVideoFormat = true, audioExpected = false, hasAudioFormat = true))
    }
}
