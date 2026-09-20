package com.zektopic.cctvapp

/**
 * When an event clip stops.
 *
 * A clip runs until the later of [postRollUs] after the most recent detection and any manual
 * hold ("Record now"), so an animal that moves, pauses and moves again keeps one clip going
 * rather than producing a string of short ones. A single file is capped at [maxClipUs] from
 * its first frame; when the cap, not the end rule, is what ends it, [cutShort] is true and
 * the recorder rolls straight into a continuation clip. All times are encoder presentation
 * times.
 */
class ClipWindow(
    private val postRollUs: Long,
    private val maxClipUs: Long,
    val startUs: Long,
    triggeredAtUs: Long
) {
    /** Where detections alone would end the clip: [postRollUs] after the latest. */
    var detectionEndUs: Long = triggeredAtUs + postRollUs
        private set

    /** Manual floor on the end ("Record now" / Hold); Long.MIN_VALUE when none. */
    var holdUntilUs: Long = Long.MIN_VALUE
        private set

    /** The spec's rule: the later of the detection end and the manual hold. */
    private val wantedEndUs: Long get() = maxOf(detectionEndUs, holdUntilUs)

    val endAtUs: Long get() = minOf(wantedEndUs, startUs + maxClipUs)

    /** True if the per-file cap, not the end rule, is what ends this file. */
    val cutShort: Boolean get() = wantedEndUs > endAtUs

    fun extend(triggeredAtUs: Long) {
        detectionEndUs = maxOf(detectionEndUs, triggeredAtUs + postRollUs)
    }

    fun hold(untilUs: Long) {
        holdUntilUs = maxOf(holdUntilUs, untilUs)
    }

    fun isOver(ptsUs: Long): Boolean = ptsUs > endAtUs
}
