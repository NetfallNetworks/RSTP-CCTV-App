package com.zektopic.cctvapp

import java.util.Locale

/** One stream's view over the rolling window. Times in ms, [ptsRate] unitless. */
data class StreamSkew(
    val n: Int,
    /** Mean of (arrival - pts) in ms; null when [n] == 0. */
    val arrivalMinusPtsMeanMs: Double?,
    val minMs: Double?,
    val maxMs: Double?,
    /** (ptsLast - ptsFirst) / (arrivalLast - arrivalFirst); 1.0 = labels advance in real time. Null with < 2 samples or no arrival span. */
    val ptsRate: Double?
)

data class AvSkewSnapshot(
    val windowSec: Int,
    val audio: StreamSkew,
    val video: StreamSkew,
    /** audio mean minus video mean of (arrival - pts), ms; null until both streams have samples. */
    val skewMs: Double?
) {
    /** The /status `avSkew` object. Hand-built so it needs no Android JSON class. */
    fun toJson(): String =
        "{\"windowSec\":$windowSec,\"audio\":${streamJson(audio)},\"video\":${streamJson(video)}," +
            "\"skewMs\":${num(skewMs)}}"

    /** One-line form for the periodic log. */
    fun toLogLine(): String =
        "skewMs=${num(skewMs)} audio[${streamLog(audio)}] video[${streamLog(video)}] window=${windowSec}s"

    private fun streamJson(s: StreamSkew) =
        "{\"n\":${s.n},\"arrivalMinusPtsMeanMs\":${num(s.arrivalMinusPtsMeanMs)}," +
            "\"minMs\":${num(s.minMs)},\"maxMs\":${num(s.maxMs)},\"ptsRate\":${num(s.ptsRate, 4)}}"

    private fun streamLog(s: StreamSkew) =
        "n=${s.n} mean=${num(s.arrivalMinusPtsMeanMs)} min=${num(s.minMs)} max=${num(s.maxMs)} rate=${num(s.ptsRate, 4)}"

    private fun num(v: Double?, digits: Int = 1): String =
        if (v == null || v.isNaN() || v.isInfinite()) "null"
        else String.format(Locale.US, "%.${digits}f", v)
}

/**
 * Diagnostic only: compares each stream's presentation-time label (ptsUs) with the monotonic
 * time its encoder callback actually fired (arrivalUs), over a rolling window of arrival time.
 * If video frames are labelled early but delivered late, video's (arrival - pts) mean is
 * larger than audio's and [AvSkewSnapshot.skewMs] is negative. It never feeds back into any
 * timestamp or recording path.
 *
 * Callers pass arrivalUs from one monotonic clock (System.nanoTime() / 1000). Thread-safe:
 * the callbacks arrive on separate encoder threads. Memory is bounded by `capacity` samples
 * per stream; no allocation per call.
 *
 * Plain logic with no Android types, so it is covered by JVM unit tests.
 */
class AvSkewMonitor(
    private val windowUs: Long = DEFAULT_WINDOW_US,
    capacity: Int = DEFAULT_CAPACITY
) {
    companion object {
        const val DEFAULT_WINDOW_US = 60_000_000L
        const val DEFAULT_CAPACITY = 8192
    }

    private class Ring(val capacity: Int) {
        val pts = LongArray(capacity)
        val arrival = LongArray(capacity)
        var head = 0
        var size = 0
        /** Running sum of (arrival - pts) in microseconds over the held samples. */
        var sumDiff = 0L

        fun clear() { head = 0; size = 0; sumDiff = 0L }

        fun at(i: Int) = (head + i) % capacity

        fun dropOldest() {
            val idx = head
            sumDiff -= arrival[idx] - pts[idx]
            head = (head + 1) % capacity
            size--
        }

        fun add(ptsUs: Long, arrivalUs: Long, windowUs: Long) {
            if (size > 0 && ptsUs < pts[at(size - 1)]) clear() // encoder restart / pts reset
            if (size == capacity) dropOldest()
            val idx = at(size)
            pts[idx] = ptsUs
            arrival[idx] = arrivalUs
            sumDiff += arrivalUs - ptsUs
            size++
            while (size > 1 && arrivalUs - arrival[head] > windowUs) dropOldest()
        }

        fun toSkew(): StreamSkew {
            if (size == 0) return StreamSkew(0, null, null, null, null)
            var min = Long.MAX_VALUE
            var max = Long.MIN_VALUE
            for (i in 0 until size) {
                val j = at(i)
                val d = arrival[j] - pts[j]
                if (d < min) min = d
                if (d > max) max = d
            }
            val first = at(0)
            val last = at(size - 1)
            val span = arrival[last] - arrival[first]
            val rate = if (size >= 2 && span > 0) (pts[last] - pts[first]).toDouble() / span else null
            return StreamSkew(size, sumDiff.toDouble() / size / 1000.0, min / 1000.0, max / 1000.0, rate)
        }
    }

    private val lock = Any()
    private val video = Ring(capacity)
    private val audio = Ring(capacity)

    fun onVideo(ptsUs: Long, arrivalUs: Long) = synchronized(lock) { video.add(ptsUs, arrivalUs, windowUs) }

    fun onAudio(ptsUs: Long, arrivalUs: Long) = synchronized(lock) { audio.add(ptsUs, arrivalUs, windowUs) }

    fun snapshot(): AvSkewSnapshot = synchronized(lock) {
        val a = audio.toSkew()
        val v = video.toSkew()
        val am = a.arrivalMinusPtsMeanMs
        val vm = v.arrivalMinusPtsMeanMs
        AvSkewSnapshot((windowUs / 1_000_000L).toInt(), a, v, if (am != null && vm != null) am - vm else null)
    }
}
