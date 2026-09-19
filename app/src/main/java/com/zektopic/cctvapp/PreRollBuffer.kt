package com.zektopic.cctvapp

/**
 * The most recent encoded video frames, so a clip can begin before the detection that
 * triggered it.
 *
 * Detection samples a snapshot a couple of times a second and the detector then takes its
 * own time, so by the moment a person is recognised they have already been in frame for a
 * while. A clip that only started then would miss them walking in.
 *
 * A clip has to open on a keyframe, so this keeps whole GOPs: it only ever drops frames
 * from the front up to the next keyframe, and only once that keyframe still leaves at
 * least [preRollUs] of history. The oldest buffered frame is therefore always a keyframe
 * (once one has arrived), and the buffer spans between [preRollUs] and [preRollUs] plus
 * one keyframe interval.
 *
 * Plain logic with no Android types, so it is covered by JVM unit tests.
 */
class PreRollBuffer(
    private val preRollUs: Long,
    /** Safety ceiling if the encoder stops producing keyframes; see [add]. */
    private val maxBytes: Long
) {
    class Frame(val data: ByteArray, val ptsUs: Long, val isKeyFrame: Boolean)

    private val frames = ArrayDeque<Frame>()
    private var bytes = 0L

    val size: Int get() = frames.size
    val byteCount: Long get() = bytes
    val newestPtsUs: Long? get() = frames.lastOrNull()?.ptsUs

    fun add(frame: Frame) {
        frames.addLast(frame)
        bytes += frame.data.size
        trim()
    }

    /**
     * Everything buffered, oldest first, starting at a keyframe. Empty until the first
     * keyframe arrives. Does not consume: the frames stay available to a later clip.
     */
    fun framesFromKeyFrame(): List<Frame> {
        val start = frames.indexOfFirst { it.isKeyFrame }
        return if (start < 0) emptyList() else frames.subList(start, frames.size).toList()
    }

    fun clear() {
        frames.clear()
        bytes = 0
    }

    private fun trim() {
        // Frames ahead of the first keyframe can never start a clip.
        val firstKey = frames.indexOfFirst { it.isKeyFrame }
        if (firstKey < 0) {
            clear()
            return
        }
        if (firstKey > 0) dropFirst(firstKey)

        val newestPtsUs = frames.last().ptsUs
        while (true) {
            val nextKey = indexOfNextKeyFrame() ?: break
            val historyIfDropped = newestPtsUs - frames[nextKey].ptsUs
            if (historyIfDropped < preRollUs && bytes <= maxBytes) break
            dropFirst(nextKey)
        }

        // One GOP bigger than the ceiling means keyframes have stopped arriving; holding
        // on would grow without bound.
        if (bytes > maxBytes && indexOfNextKeyFrame() == null) clear()
    }

    private fun indexOfNextKeyFrame(): Int? {
        for (i in 1 until frames.size) if (frames[i].isKeyFrame) return i
        return null
    }

    private fun dropFirst(count: Int) {
        repeat(count) { bytes -= frames.removeFirst().data.size }
    }
}
