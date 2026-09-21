package com.zektopic.cctvapp

/**
 * The most recent encoded audio frames, so a clip's audio can begin aligned with its video
 * pre-roll rather than however the encoder happened to hand samples over.
 *
 * Unlike [PreRollBuffer], audio has no keyframes to anchor on -- every AAC frame decodes on
 * its own, so this is a plain time window: it keeps whatever is within [preRollUs] of the
 * newest sample, plus a byte ceiling as a safety net if samples ever stopped being trimmed
 * for some other reason. [framesFrom] is what keeps a clip's audio from opening before its
 * video: the caller passes the video clip's chosen start time as the floor, and only audio at
 * or after it comes back, so the written offset (elsewhere: `ptsUs - clipStartUs`) is never
 * negative.
 *
 * Plain logic with no Android types, so it is covered by JVM unit tests.
 */
class AudioPreRollBuffer(
    private val preRollUs: Long,
    private val maxBytes: Long
) {
    class Frame(val data: ByteArray, val ptsUs: Long)

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
     * Everything buffered at or after [floorUs], oldest first. Does not consume: the frames
     * stay available to a later clip (e.g. a rollover continuation).
     */
    fun framesFrom(floorUs: Long): List<Frame> = frames.filter { it.ptsUs >= floorUs }

    fun clear() {
        frames.clear()
        bytes = 0
    }

    private fun trim() {
        val newestPtsUs = frames.lastOrNull()?.ptsUs ?: return
        while (frames.isNotEmpty() &&
            (newestPtsUs - frames.first().ptsUs > preRollUs || bytes > maxBytes)
        ) {
            bytes -= frames.removeFirst().data.size
        }
    }
}
