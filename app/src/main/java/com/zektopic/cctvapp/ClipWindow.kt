package com.zektopic.cctvapp

/**
 * When an event clip stops.
 *
 * A clip runs until [postRollUs] after the most recent detection, so someone who stays in
 * view keeps one clip going rather than producing a string of short ones -- but never
 * past [maxClipUs] from its first frame, so a person standing on the patio all evening
 * cannot grow a single file without bound. All times are encoder presentation times.
 */
class ClipWindow(
    private val postRollUs: Long,
    private val maxClipUs: Long,
    val startUs: Long,
    triggeredAtUs: Long
) {
    var endAtUs: Long = cap(triggeredAtUs + postRollUs)
        private set

    fun extend(triggeredAtUs: Long) {
        endAtUs = maxOf(endAtUs, cap(triggeredAtUs + postRollUs))
    }

    fun isOver(ptsUs: Long): Boolean = ptsUs > endAtUs

    private fun cap(us: Long): Long = minOf(us, startUs + maxClipUs)
}
