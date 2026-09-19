package com.zektopic.cctvapp

/**
 * When an event clip stops.
 *
 * A clip runs until [postRollUs] after the most recent detection, so an animal that
 * moves, pauses and moves again keeps one clip going rather than producing a string of
 * short ones. A single file is capped at [maxClipUs] from its first frame; when the cap,
 * not the post-roll, is what ends it, [cutShort] is true and the recorder rolls straight
 * into a continuation clip. All times are encoder presentation times.
 */
class ClipWindow(
    private val postRollUs: Long,
    private val maxClipUs: Long,
    val startUs: Long,
    triggeredAtUs: Long
) {
    /** Where the clip would end with no cap. */
    var requestedEndUs: Long = triggeredAtUs + postRollUs
        private set

    val endAtUs: Long get() = minOf(requestedEndUs, startUs + maxClipUs)

    /** True if the cap, rather than a quiet post-roll, is what ends this clip. */
    val cutShort: Boolean get() = requestedEndUs > endAtUs

    fun extend(triggeredAtUs: Long) {
        requestedEndUs = maxOf(requestedEndUs, triggeredAtUs + postRollUs)
    }

    fun isOver(ptsUs: Long): Boolean = ptsUs > endAtUs
}
