package com.zektopic.cctvapp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AvSkewMonitorTest {
    @Test fun emptyStreamsReportNothing() {
        val s = AvSkewMonitor().snapshot()
        assertEquals(0, s.audio.n); assertEquals(0, s.video.n)
        assertNull(s.audio.arrivalMinusPtsMeanMs); assertNull(s.video.ptsRate); assertNull(s.skewMs)
        assertEquals(60, s.windowSec)
    }

    @Test fun singleSampleHasMeanButNoRate() {
        val m = AvSkewMonitor()
        m.onVideo(1_000_000, 1_800_000)
        val v = m.snapshot().video
        assertEquals(1, v.n)
        assertEquals(800.0, v.arrivalMinusPtsMeanMs!!, 1e-9)
        assertEquals(800.0, v.minMs!!, 1e-9); assertEquals(800.0, v.maxMs!!, 1e-9)
        assertNull(v.ptsRate)
        assertNull(m.snapshot().skewMs) // audio missing
    }

    @Test fun meanMinMaxAndRate() {
        val m = AvSkewMonitor()
        // offsets 100, 300, 200 ms; pts span 2 s over arrival span 2.1 s
        m.onAudio(0, 100_000); m.onAudio(1_000_000, 1_300_000); m.onAudio(2_000_000, 2_200_000)
        val a = m.snapshot().audio
        assertEquals(3, a.n)
        assertEquals(200.0, a.arrivalMinusPtsMeanMs!!, 1e-9)
        assertEquals(100.0, a.minMs!!, 1e-9); assertEquals(300.0, a.maxMs!!, 1e-9)
        assertEquals(2_000_000.0 / 2_100_000.0, a.ptsRate!!, 1e-9)
    }

    @Test fun skewIsAudioMeanMinusVideoMean() {
        val m = AvSkewMonitor()
        m.onVideo(0, 800_000); m.onVideo(33_000, 833_000)
        m.onAudio(0, 20_000); m.onAudio(21_000, 41_000)
        assertEquals(-780.0, m.snapshot().skewMs!!, 1e-9)
    }

    @Test fun ptsRateShowsSkewedLabelClock() {
        val m = AvSkewMonitor()
        for (i in 0..10) m.onAudio(i * 1_100_000L, i * 1_000_000L) // labels run 10% fast
        assertEquals(1.1, m.snapshot().audio.ptsRate!!, 1e-9)
    }

    @Test fun decreasingPtsClearsOnlyThatStream() {
        val m = AvSkewMonitor()
        m.onVideo(5_000_000, 5_100_000); m.onVideo(5_033_000, 5_133_000)
        m.onAudio(5_000_000, 5_000_000)
        m.onVideo(0, 9_000_000) // encoder restart
        val s = m.snapshot()
        assertEquals(1, s.video.n)
        assertEquals(9000.0, s.video.arrivalMinusPtsMeanMs!!, 1e-9)
        assertEquals(1, s.audio.n)
    }

    @Test fun windowTrimsOldSamples() {
        val m = AvSkewMonitor(windowUs = 10_000_000)
        for (i in 0..20) m.onVideo(i * 1_000_000L, i * 1_000_000L + i * 1000L)
        val v = m.snapshot().video
        assertEquals(10, v.n) // newest arrival 20.020 s; the 10.010 s sample is just outside 10 s
        assertEquals(11.0, v.minMs!!, 1e-9); assertEquals(20.0, v.maxMs!!, 1e-9)
        assertEquals(15.5, v.arrivalMinusPtsMeanMs!!, 1e-9)
    }

    @Test fun capacityBoundsMemoryAndKeepsRunningSumConsistent() {
        val m = AvSkewMonitor(windowUs = Long.MAX_VALUE / 4, capacity = 4)
        for (i in 0 until 10) m.onAudio(i * 1000L, i * 1000L + (i + 1) * 1000L)
        val a = m.snapshot().audio
        assertEquals(4, a.n)
        assertEquals(8.5, a.arrivalMinusPtsMeanMs!!, 1e-9) // offsets 7,8,9,10 ms
        assertEquals(7.0, a.minMs!!, 1e-9); assertEquals(10.0, a.maxMs!!, 1e-9)
    }

    @Test fun jsonShape() {
        val m = AvSkewMonitor()
        assertEquals(
            "{\"windowSec\":60,\"audio\":{\"n\":0,\"arrivalMinusPtsMeanMs\":null,\"minMs\":null,\"maxMs\":null,\"ptsRate\":null}," +
                "\"video\":{\"n\":0,\"arrivalMinusPtsMeanMs\":null,\"minMs\":null,\"maxMs\":null,\"ptsRate\":null},\"skewMs\":null,\"sessions\":[]}",
            m.snapshot().toJson()
        )
        m.onVideo(0, 500_000); m.onVideo(1_000_000, 1_500_000)
        m.onAudio(0, 0); m.onAudio(1_000_000, 1_000_000)
        val j = m.snapshot().toJson()
        assertTrue(j, j.contains("\"skewMs\":-500.0"))
        assertTrue(j, j.contains("\"ptsRate\":1.0000"))
        assertNotNull(m.snapshot().toLogLine())
    }

    @Test fun samplesBeforeAnyStartBelongToNoSession() {
        val m = AvSkewMonitor()
        assertNull(m.onVideo(0, 100)); assertNull(m.onAudio(0, 100))
        assertTrue(m.snapshot().sessions.isEmpty())
    }

    @Test fun firstVideoThenAudioRecordedOnceAndCompletionReported() {
        val m = AvSkewMonitor()
        m.onStreamStart(1_000_000, 777)
        assertNull(m.onVideo(50_000, 1_400_000))
        assertNull(m.onVideo(83_000, 1_500_000)) // later frames do not overwrite
        val done = m.onAudio(0, 1_100_000)
        assertNotNull(done)
        assertNull(m.onAudio(21_000, 1_200_000))
        val s = m.snapshot().sessions.single()
        assertEquals(1, s.seq); assertEquals(777L, s.wallStartMs)
        assertEquals(400L, s.firstVideoMs); assertEquals(100L, s.firstAudioMs)
        assertEquals(50_000L, s.firstVideoPtsUs); assertEquals(0L, s.firstAudioPtsUs)
        assertEquals(s, done)
    }

    @Test fun audioBeforeVideoCompletesOnVideo() {
        val m = AvSkewMonitor()
        m.onStreamStart(0, 1)
        assertNull(m.onAudio(0, 30_000))
        assertNull(m.snapshot().sessions.single().firstVideoMs)
        assertNotNull(m.onVideo(0, 250_000))
        val s = m.snapshot().sessions.single()
        assertEquals(30L, s.firstAudioMs); assertEquals(250L, s.firstVideoMs)
    }

    @Test fun restartOpensNewSessionAndKeepsOldOne() {
        val m = AvSkewMonitor()
        m.onStreamStart(0, 10)
        m.onVideo(0, 200_000)
        m.onStreamStart(5_000_000, 20) // restart before audio ever arrived
        m.onVideo(0, 5_300_000); m.onAudio(0, 5_050_000)
        val ss = m.snapshot().sessions
        assertEquals(2, ss.size)
        assertEquals(200L, ss[0].firstVideoMs); assertNull(ss[0].firstAudioMs)
        assertEquals(300L, ss[1].firstVideoMs); assertEquals(50L, ss[1].firstAudioMs)
        assertEquals(listOf(1, 2), ss.map { it.seq })
    }

    @Test fun sessionRingKeepsLastEightNewestLast() {
        val m = AvSkewMonitor()
        for (i in 1..11) m.onStreamStart(i * 1000L, i.toLong())
        val ss = m.snapshot().sessions
        assertEquals(8, ss.size)
        assertEquals((4..11).toList(), ss.map { it.seq })
    }

    @Test fun sessionsJsonShape() {
        val m = AvSkewMonitor()
        m.onStreamStart(0, 42)
        m.onVideo(9, 120_000)
        val j = m.snapshot().toJson()
        assertTrue(j, j.contains(
            "\"sessions\":[{\"seq\":1,\"wallStartMs\":42,\"firstVideoMs\":120,\"firstAudioMs\":null," +
                "\"firstVideoPtsUs\":9,\"firstAudioPtsUs\":null}]}"
        ))
    }
}
