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

/**
 * One stream start (a call to startStream()) and when its first video / audio frame reached
 * the recorder callbacks. Delays are ms after the start; null until that frame is seen.
 */
data class AvSession(
    val seq: Int,
    val wallStartMs: Long,
    val firstVideoMs: Long?,
    val firstAudioMs: Long?,
    val firstVideoPtsUs: Long?,
    val firstAudioPtsUs: Long?
) {
    val complete: Boolean get() = firstVideoMs != null && firstAudioMs != null

    fun toJson(): String =
        "{\"seq\":$seq,\"wallStartMs\":$wallStartMs,\"firstVideoMs\":${n(firstVideoMs)}," +
            "\"firstAudioMs\":${n(firstAudioMs)},\"firstVideoPtsUs\":${n(firstVideoPtsUs)}," +
            "\"firstAudioPtsUs\":${n(firstAudioPtsUs)}}"

    fun toLogLine(): String =
        "seq=$seq wallStartMs=$wallStartMs firstVideoMs=${n(firstVideoMs)} firstAudioMs=${n(firstAudioMs)} " +
            "firstVideoPtsUs=${n(firstVideoPtsUs)} firstAudioPtsUs=${n(firstAudioPtsUs)}"

    private fun n(v: Long?) = v?.toString() ?: "null"
}

data class AvSkewSnapshot(
    val windowSec: Int,
    val audio: StreamSkew,
    val video: StreamSkew,
    /** audio mean minus video mean of (arrival - pts), ms; null until both streams have samples. */
    val skewMs: Double?,
    /** Last stream starts, oldest first (newest last). */
    val sessions: List<AvSession> = emptyList()
) {
    /** The /status `avSkew` object. Hand-built so it needs no Android JSON class. */
    fun toJson(): String =
        "{\"windowSec\":$windowSec,\"audio\":${streamJson(audio)},\"video\":${streamJson(video)}," +
            "\"skewMs\":${num(skewMs)},\"sessions\":[${sessions.joinToString(",") { it.toJson() }}]}"

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
        const val MAX_SESSIONS = 8
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

    private var nextSeq = 1
    private var startUs = 0L
    /** Oldest first; the last entry is the open session. Bounded by [MAX_SESSIONS]. */
    private val sessions = ArrayList<AvSession>(MAX_SESSIONS)

    /**
     * Call immediately before each startStream(), on the same clock as arrivalUs. Opens a new
     * session; earlier ones are kept (last [MAX_SESSIONS]). Callbacks before any start belong
     * to no session.
     */
    fun onStreamStart(startUs: Long, wallMs: Long) = synchronized(lock) {
        this.startUs = startUs
        if (sessions.size == MAX_SESSIONS) sessions.removeAt(0)
        sessions.add(AvSession(nextSeq++, wallMs, null, null, null, null))
    }

    /** Returns the session if this call completed it (first video and audio both seen), else null. */
    fun onVideo(ptsUs: Long, arrivalUs: Long): AvSession? = synchronized(lock) {
        video.add(ptsUs, arrivalUs, windowUs)
        val i = sessions.lastIndex
        if (i < 0 || sessions[i].firstVideoMs != null) return@synchronized null
        val s = sessions[i].copy(firstVideoMs = (arrivalUs - startUs) / 1000, firstVideoPtsUs = ptsUs)
        sessions[i] = s
        if (s.complete) s else null
    }

    /** See [onVideo]. */
    fun onAudio(ptsUs: Long, arrivalUs: Long): AvSession? = synchronized(lock) {
        audio.add(ptsUs, arrivalUs, windowUs)
        val i = sessions.lastIndex
        if (i < 0 || sessions[i].firstAudioMs != null) return@synchronized null
        val s = sessions[i].copy(firstAudioMs = (arrivalUs - startUs) / 1000, firstAudioPtsUs = ptsUs)
        sessions[i] = s
        if (s.complete) s else null
    }

    fun snapshot(): AvSkewSnapshot = synchronized(lock) {
        val a = audio.toSkew()
        val v = video.toSkew()
        val am = a.arrivalMinusPtsMeanMs
        val vm = v.arrivalMinusPtsMeanMs
        AvSkewSnapshot((windowUs / 1_000_000L).toInt(), a, v, if (am != null && vm != null) am - vm else null, sessions.toList())
    }
}
