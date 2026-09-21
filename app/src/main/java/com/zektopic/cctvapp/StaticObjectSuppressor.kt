package com.zektopic.cctvapp

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Suppresses object-detector hits that are a still-life repeat of something already sitting in
 * frame -- a bag left on a chair, a jacket on a hook -- rather than a fresh person or animal.
 *
 * Observed live: a re-aimed camera picked up a dark bag over a chair and the detector scored it
 * `person` at 0.31601548194885254 -- clearing the 0.30 threshold by 0.016 -- and produced the
 * *identical* score on every later pass. That repetition, not the score itself, is the
 * signature of a static object: a real person's score and box drift from frame to frame even
 * standing still, because the input jitters (JPEG re-encode, auto-exposure, sensor noise). An
 * unmoving object's score does not, or moves by noise-floor amounts only.
 *
 * Raising the threshold was deliberately rejected: this app is biased to record, and a missed
 * real person costs more than a false clip. So detections stay recordable down to the model's
 * floor; what changes is that a detection which keeps recurring at the same place with the same
 * score, for long enough, stops being treated as new information.
 *
 * ### Matching tolerance
 * Two hits count as "the same object" when both:
 * - **Box overlap (IoU) >= [iouThreshold], default 0.85.** IoU folds position *and* size into
 *   one number, which matters here: a person approaching the camera keeps roughly the same
 *   centre but grows, and growth alone should never read as "unchanged". A box that jitters by
 *   a couple of pixels on each edge -- the kind of noise a static object actually produces --
 *   still overlaps itself well above 0.95; 0.85 leaves real headroom for that jitter while
 *   still failing fast for anything that has genuinely moved or resized.
 * - **Score within [scoreTolerance], default 0.02 (2 points).** The live false positive
 *   repeated its score exactly, but exact equality is not something to build on -- re-encoding,
 *   auto-exposure and quantisation can all nudge a static object's score by a little between
 *   passes. 2 points is generous enough to absorb that noise but far tighter than the gap
 *   between two genuinely different objects' scores in practice.
 *
 * ### Duration before "background"
 * A detection only stops counting once it has matched the *same* tracked box for
 * [staticAfterMs] running -- default 90 seconds. That is 1.5x this app's own post-roll window
 * (60 s, see `ClipRecorder.DEFAULT_POST_ROLL_US`): a person standing still is recorded in full
 * for at least that long, comfortably past the length of an ordinary pause (checking a phone,
 * waiting at a door, tying a shoe). Only someone genuinely motionless well past a minute and a
 * half starts aging out -- and real people are not that still: a sway, a shift of weight or a
 * breath-driven silhouette change breaks the match (see below) and resumes full recording
 * immediately. This is the one real safety tradeoff in this design and needs on-device
 * confirmation against an actual still person, not just the bag that motivated it.
 *
 * ### Ending suppression
 * There is no decay timer. Every call rebuilds tracking from scratch out of *this frame's*
 * sightings:
 * - A tracked box **not matched by anything in the current frame is dropped immediately** --
 *   covers the bag being removed. A later detection near the old spot is treated as brand new
 *   and gets the full [staticAfterMs] grace again, rather than being pre-aged into instant
 *   suppression by a stale record of an object that is no longer there.
 * - A sighting that **fails to match** an existing tracked box (moved, resized, or simply a
 *   different score) starts a brand-new candidate with the clock reset to now, so it is never
 *   suppressed on the frame it changes -- covers the still object starting to move.
 *
 * ### Per box, not global
 * State is tracked per (label, box) pair, not as one global "is anything static" flag. A coat
 * on the left of frame going static must never suppress a person entering on the right: they
 * are different boxes, tracked independently, and only a sighting whose box actually overlaps a
 * stale one is ever affected. [activeMask] processes every sighting the caller hands it (not
 * just the frame's top-scoring one), so two simultaneous detections of the same label -- the
 * static coat and a real, moving person -- are judged on their own boxes rather than collapsed
 * into a single decision.
 *
 * ### Motion detection
 * This class only ever sees object-detector output. `CctvServerService`'s frame-difference
 * motion detector feeds the recorder through a completely separate path and is never filtered
 * by this class, so a suppressed static "person" can never suppress a genuine motion event
 * landing in the same frame.
 *
 * Plain logic with no Android types, so it is covered by JVM unit tests.
 */
class StaticObjectSuppressor(
    private val staticAfterMs: Long = DEFAULT_STATIC_AFTER_MS,
    private val iouThreshold: Double = DEFAULT_IOU_THRESHOLD,
    private val scoreTolerance: Float = DEFAULT_SCORE_TOLERANCE
) {
    /** One object-detector hit: a label, its score, and its box (null when unlocatable). */
    data class Sighting(
        val label: String,
        val score: Float,
        /** Normalised 0-1 [left, top, right, bottom], same convention as [LiteRtDetection.box]. */
        val box: List<Float>?
    )

    private data class Candidate(val box: List<Float>, val score: Float, val stableSinceMs: Long)

    /** label -> every distinct box currently being tracked for that label. */
    private var candidatesByLabel: Map<String, List<Candidate>> = emptyMap()

    /**
     * Given this frame's [sightings] (already filtered to whatever labels the caller wants
     * suppression applied to, e.g. "person" and the animal labels), returns a same-size,
     * same-order mask: true where the sighting should be acted on, false where it is a repeat
     * of an already-static object and should be treated as background.
     *
     * A sighting with a null [Sighting.box] cannot be position-tracked at all, so it always
     * comes back active (fails open, matching this app's bias to record) and is not tracked.
     */
    fun activeMask(sightings: List<Sighting>, atMs: Long): List<Boolean> {
        val remainingByLabel = candidatesByLabel.mapValues { it.value.toMutableList() }
        val next = mutableMapOf<String, MutableList<Candidate>>()
        val result = ArrayList<Boolean>(sightings.size)

        for (sighting in sightings) {
            val box = sighting.box
            if (box == null) {
                result.add(true)
                continue
            }

            val pool = remainingByLabel[sighting.label]
            val match = pool?.firstOrNull { isSameObject(it, sighting.score, box) }
            val candidate = if (match != null) {
                pool.remove(match)
                Candidate(box, sighting.score, stableSinceMs = match.stableSinceMs)
            } else {
                Candidate(box, sighting.score, stableSinceMs = atMs)
            }
            next.getOrPut(sighting.label) { mutableListOf() }.add(candidate)
            result.add(atMs - candidate.stableSinceMs < staticAfterMs)
        }

        candidatesByLabel = next
        return result
    }

    private fun isSameObject(candidate: Candidate, score: Float, box: List<Float>): Boolean =
        abs(candidate.score - score) <= scoreTolerance && iou(candidate.box, box) >= iouThreshold

    companion object {
        /** 1.5x ClipRecorder.DEFAULT_POST_ROLL_US (60 s) -- see class doc. */
        const val DEFAULT_STATIC_AFTER_MS = 90_000L
        const val DEFAULT_IOU_THRESHOLD = 0.85
        const val DEFAULT_SCORE_TOLERANCE = 0.02f

        /**
         * Intersection-over-union of two [left, top, right, bottom] boxes. Pulled out as a
         * standalone, directly testable function -- this is the geometry worth getting right
         * on its own, independent of the tracking state machine around it.
         */
        fun iou(a: List<Float>, b: List<Float>): Double {
            require(a.size == 4 && b.size == 4) { "box must be [left, top, right, bottom]" }

            val overlapWidth = max(0f, min(a[2], b[2]) - max(a[0], b[0]))
            val overlapHeight = max(0f, min(a[3], b[3]) - max(a[1], b[1]))
            val overlapArea = overlapWidth.toDouble() * overlapHeight.toDouble()

            val areaA = max(0f, a[2] - a[0]).toDouble() * max(0f, a[3] - a[1]).toDouble()
            val areaB = max(0f, b[2] - b[0]).toDouble() * max(0f, b[3] - b[1]).toDouble()
            val union = areaA + areaB - overlapArea

            return if (union <= 0.0) 0.0 else overlapArea / union
        }
    }
}
