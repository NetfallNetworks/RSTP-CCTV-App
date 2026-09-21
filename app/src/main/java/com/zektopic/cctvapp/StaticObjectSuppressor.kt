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
 * ### Clock
 * [activeMask]'s `atMs` **must be a monotonic clock** -- `SystemClock.elapsedRealtime()`, never
 * `System.currentTimeMillis()`. An early revision of this class was fed wall-clock time and
 * that is a live-safety bug, not a style nit: an NTP step forward past boot or a network
 * reconnect (routine on Android) of more than [staticAfterMs] would make `atMs - stableSinceMs`
 * clear the bar on the very next frame, suppressing a live person with no grace at all; a step
 * backward would mean nothing ever suppresses. A monotonic clock cannot step either way.
 *
 * ### Matching tolerance
 * Two hits count as "the same object" when both:
 * - **Box overlap (IoU) >= [iouThreshold], default 0.85.** IoU folds position *and* size into
 *   one number, which matters here: a person approaching the camera keeps roughly the same
 *   centre but grows, and growth alone should never read as "unchanged". A box that jitters by
 *   a couple of pixels on each edge -- the kind of noise a static object actually produces --
 *   still overlaps itself well above 0.95; 0.85 leaves real headroom for that jitter while
 *   still failing fast for anything that has genuinely moved or resized.
 * - **Score within [scoreTolerance], default 0.005 (half a point).** Tight on purpose. A wider
 *   band was tried first (0.02) and rejected: at a 0.30 floor that spans 0.296-0.336, the
 *   *entire* low-confidence range above the threshold, so a real, different, low-scoring person
 *   sitting down where the bag had been could inherit the bag's tracked run and its
 *   [stableSinceMs][Candidate.stableSinceMs] with zero grace. 0.005 still absorbs re-encode and
 *   auto-exposure noise on a genuinely static object without blurring two different objects
 *   together. A tolerance this tight cannot fail to re-suppress daylight-drifting background
 *   either, because that drift is a slow, real score change over tens of seconds -- exactly the
 *   kind of change the anchor comparison below (not a wall-clock-scale average) is built to
 *   treat as "different", which is correct: something is in fact changing.
 *
 * ### Duration before "background"
 * A detection only stops counting once it has matched the *same run* -- see Anchoring below --
 * for [staticAfterMs] running, default 90 seconds. That is 1.5x this app's own post-roll window
 * (60 s, see `ClipRecorder.DEFAULT_POST_ROLL_US`): a person standing still is recorded in full
 * for at least that long, comfortably past the length of an ordinary pause (checking a phone,
 * waiting at a door, tying a shoe). Only someone genuinely motionless well past a minute and a
 * half starts aging out -- and real people are not that still: a sway, a shift of weight or a
 * breath-driven silhouette change breaks the match (see below) and resumes full recording
 * immediately. This is the one real safety tradeoff in this design and needs on-device
 * confirmation against an actual still person, not just the bag that motivated it.
 *
 * ### Anchoring -- why "static" cannot ratchet
 * A run is compared against **two** references, and a sighting must match both to continue it:
 * the **anchor** (the box and score the run started with, fixed for its whole life) and the
 * **most recent** sighting that continued it. Comparing a moving object only to its immediately
 * previous position -- what an earlier revision of this class did -- lets a "static" run
 * ratchet: 0.85 IoU alone tolerates roughly 8% of the box's width moving per comparison, and at
 * 2 FPS that is on the order of 16% of the frame per second with no ceiling on how far the run
 * can walk over 90 seconds, because each step is only ever judged against the last one. Pinning
 * every sighting to the *anchor* as well bounds the whole run to within tolerance of where it
 * started, for its entire life: a slowly but genuinely moving object drifts outside that
 * tolerance and breaks the anchor match well before 90 s of accumulated motion, which correctly
 * restarts its clock rather than ever letting it go static while still moving.
 *
 * ### The wrong match is worse than no match
 * When several tracked candidates for a label could match a sighting (rare, but two coats or a
 * coat plus a person crossing near it are both possible), [activeMask] picks the **best**
 * overlap ([Candidate.anchorBox] IoU), not the first one found in whatever order the candidate
 * list happens to be in. An arbitrary first-match could hand a poor-overlap run's identity to a
 * sighting that actually fits a different, better-overlapping candidate.
 *
 * ### Call gaps
 * Detection does not run at a fixed cadence -- snapshots stop while nothing is streaming and
 * slow to an idle interval under thermal/battery throttling, so [activeMask] can go uncalled
 * for anywhere from seconds to minutes. A sighting is only allowed to continue a tracked
 * candidate when it was last confirmed within [maxGapMs] (default 3 s, a few missed passes at
 * this app's ~2 FPS active cadence) of `atMs`. Without this, a person detected for a few
 * seconds, a two-minute stream drop, then a similar re-detection at roughly the same spot would
 * inherit the original [stableSinceMs][Candidate.stableSinceMs] and could be suppressed on the
 * very next frame, with the intervening two minutes of silence counted as if it had been
 * observed continuously. Past the gap, the sighting simply starts a new run -- exactly like a
 * real move (see Ending suppression) -- which is the safe direction: worst case it delays
 * suppression, never a live person.
 *
 * ### Ending suppression
 * There is no decay timer. Every call rebuilds tracking from scratch out of *this frame's*
 * sightings:
 * - A tracked candidate **not matched by anything in the current frame** is dropped
 *   immediately -- covers the bag being removed. A later detection near the old spot is treated
 *   as brand new and gets the full [staticAfterMs] grace again, rather than being pre-aged into
 *   instant suppression by a stale record of an object that is no longer there.
 * - A sighting that **fails to match** an existing candidate (moved, resized, drifted past the
 *   anchor, a real score change, or too long since it was last confirmed -- see Call gaps)
 *   starts a brand-new candidate with the clock reset to now, so it is never suppressed on the
 *   frame it changes -- covers the still object starting to move.
 * - [reset] drops everything unconditionally. The caller uses it whenever the picture behind a
 *   tracked box can have changed discontinuously in a way this class has no sighting to reflect
 *   yet: a stream (re)start, a camera switch, or object detection being turned back on after
 *   being off. See `CctvServerService`'s call sites.
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
    private val scoreTolerance: Float = DEFAULT_SCORE_TOLERANCE,
    private val maxGapMs: Long = DEFAULT_MAX_GAP_MS
) {
    /** One object-detector hit: a label, its score, and its box (null when unlocatable). */
    data class Sighting(
        val label: String,
        val score: Float,
        /** Normalised 0-1 [left, top, right, bottom], same convention as [LiteRtDetection.box]. */
        val box: List<Float>?
    )

    /**
     * A tracked run. [anchorBox]/[anchorScore] are fixed for the run's whole life (see class doc
     * "Anchoring"); [lastBox]/[lastScore]/[lastSeenMs] update every time a sighting continues it.
     */
    private data class Candidate(
        val anchorBox: List<Float>,
        val anchorScore: Float,
        val stableSinceMs: Long,
        val lastBox: List<Float>,
        val lastScore: Float,
        val lastSeenMs: Long
    )

    /** label -> every distinct run currently being tracked for that label. */
    private var candidatesByLabel: Map<String, List<Candidate>> = emptyMap()

    /**
     * Given this frame's [sightings] (already filtered to whatever labels the caller wants
     * suppression applied to, e.g. "person" and the animal labels), returns a same-size,
     * same-order mask: true where the sighting should be acted on, false where it is a repeat
     * of an already-static object and should be treated as background.
     *
     * A sighting with a null [Sighting.box] cannot be position-tracked at all, so it always
     * comes back active (fails open, matching this app's bias to record) and is not tracked.
     *
     * [atMs] must be monotonic -- see class doc "Clock".
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
            val match = pool
                ?.filter { atMs - it.lastSeenMs <= maxGapMs && isSameObject(it, sighting.score, box) }
                ?.maxByOrNull { iou(it.anchorBox, box) }

            val candidate = if (match != null) {
                pool.remove(match)
                Candidate(
                    anchorBox = match.anchorBox,
                    anchorScore = match.anchorScore,
                    stableSinceMs = match.stableSinceMs,
                    lastBox = box,
                    lastScore = sighting.score,
                    lastSeenMs = atMs
                )
            } else {
                Candidate(
                    anchorBox = box,
                    anchorScore = sighting.score,
                    stableSinceMs = atMs,
                    lastBox = box,
                    lastScore = sighting.score,
                    lastSeenMs = atMs
                )
            }
            next.getOrPut(sighting.label) { mutableListOf() }.add(candidate)
            result.add(atMs - candidate.stableSinceMs < staticAfterMs)
        }

        candidatesByLabel = next
        return result
    }

    /**
     * Drops all tracked state unconditionally. Call whenever the picture behind a tracked box
     * can have changed discontinuously with no sighting to reflect it yet -- a stream
     * (re)start, a camera switch, or object detection being re-enabled after being off. See
     * class doc "Ending suppression".
     */
    fun reset() {
        candidatesByLabel = emptyMap()
    }

    /** Must match both the run's anchor and its most recent sighting -- see class doc. */
    private fun isSameObject(candidate: Candidate, score: Float, box: List<Float>): Boolean =
        abs(candidate.anchorScore - score) <= scoreTolerance &&
            iou(candidate.anchorBox, box) >= iouThreshold &&
            abs(candidate.lastScore - score) <= scoreTolerance &&
            iou(candidate.lastBox, box) >= iouThreshold

    companion object {
        /** 1.5x ClipRecorder.DEFAULT_POST_ROLL_US (60 s) -- see class doc "Duration". */
        const val DEFAULT_STATIC_AFTER_MS = 90_000L
        const val DEFAULT_IOU_THRESHOLD = 0.85
        const val DEFAULT_SCORE_TOLERANCE = 0.005f
        /** A few missed passes at this app's ~2 FPS active capture cadence -- see "Call gaps". */
        const val DEFAULT_MAX_GAP_MS = 3_000L

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
