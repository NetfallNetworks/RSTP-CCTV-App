package com.zektopic.cctvapp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StaticObjectSuppressorTest {

    private fun sighting(label: String = "person", score: Float, box: List<Float>?) =
        StaticObjectSuppressor.Sighting(label, score, box)

    /** The live bag-over-a-chair box, unmoved. */
    private val bagBox = listOf(0.40f, 0.30f, 0.60f, 0.70f)
    private val bagScore = 0.31601548194885254f

    /**
     * Feeds [sightings] to [suppressor] once a second from t=0 up to (but not including)
     * [untilMs], asserting every call along the way is active. Every test that needs to reach
     * real suppression must build up to it this way, one second at a time -- not with two
     * sparse calls far apart -- because the C2 fix means a gap over [StaticObjectSuppressor]'s
     * `maxGapMs` (3s by default) starts a fresh run rather than continuing the old one. A
     * sparse two-call test would therefore never actually reach 90s of continuous tracking; it
     * would silently be asserting on a run that only just started.
     */
    private fun buildUpToJustBefore(
        suppressor: StaticObjectSuppressor,
        sightings: List<StaticObjectSuppressor.Sighting>,
        untilMs: Long
    ): Long {
        var t = 0L
        while (t < untilMs) {
            val mask = suppressor.activeMask(sightings, atMs = t)
            assertTrue("expected active at t=$t, building up to $untilMs", mask.all { it })
            t += 1_000L
        }
        return t
    }

    // ---------------------------------------------------------------------------------------
    // Baseline behaviour
    // ---------------------------------------------------------------------------------------

    @Test
    fun `a single identical hit is never suppressed -- only recurrence over time is`() {
        val suppressor = StaticObjectSuppressor(staticAfterMs = 90_000L)
        val mask = suppressor.activeMask(listOf(sighting(score = bagScore, box = bagBox)), atMs = 0L)
        assertEquals(listOf(true), mask)
    }

    @Test
    fun `a perfectly static object -- zero jitter, the literal bag case -- is suppressed exactly at 90s and stays suppressed`() {
        val suppressor = StaticObjectSuppressor(staticAfterMs = 90_000L)
        val sightings = listOf(sighting(score = bagScore, box = bagBox))

        // Active every second up through t=89_000 (buildUpToJustBefore asserts that itself).
        var t = buildUpToJustBefore(suppressor, sightings, untilMs = 90_000L)

        // Suppressed at 90s and stays suppressed for as long as it keeps being detected.
        while (t <= 120_000L) {
            val mask = suppressor.activeMask(sightings, atMs = t)
            assertTrue("expected suppressed at t=$t, at or after the 90s mark", mask.none { it })
            t += 1_000L
        }
    }

    @Test
    fun `pixel jitter in box and score within tolerance still counts as the same static object`() {
        // Box edges wobble by ~0.5% of frame width/height each pass; score wobbles by <=0.0015
        // -- both anchor- and previous-frame-relative, well inside the tightened 0.005 score
        // tolerance and the 0.85 IoU tolerance.
        val suppressor = StaticObjectSuppressor(staticAfterMs = 90_000L)

        val jitteredBoxes = listOf(
            listOf(0.400f, 0.300f, 0.600f, 0.700f),
            listOf(0.405f, 0.298f, 0.598f, 0.702f),
            listOf(0.397f, 0.303f, 0.603f, 0.699f),
            listOf(0.402f, 0.301f, 0.599f, 0.701f)
        )
        val jitteredScores = listOf(bagScore, bagScore + 0.001f, bagScore - 0.001f, bagScore + 0.0015f)

        // Cycles through the four jittered variants once a second -- well inside maxGapMs --
        // for 90s, checked active the whole way, then suppressed once the bar is cleared.
        var t = 0L
        while (t < 90_000L) {
            val i = ((t / 1_000L) % jitteredBoxes.size).toInt()
            val mask = suppressor.activeMask(listOf(sighting(score = jitteredScores[i], box = jitteredBoxes[i])), atMs = t)
            assertTrue("expected active at t=$t", mask.single())
            t += 1_000L
        }

        val i = ((t / 1_000L) % jitteredBoxes.size).toInt()
        val suppressedMask = suppressor.activeMask(listOf(sighting(score = jitteredScores[i], box = jitteredBoxes[i])), atMs = t)
        assertEquals(listOf(false), suppressedMask)
    }

    @Test
    fun `a real move breaks suppression immediately`() {
        val suppressor = StaticObjectSuppressor(staticAfterMs = 90_000L)
        val stillSighting = sighting(score = bagScore, box = bagBox)

        val t = buildUpToJustBefore(suppressor, listOf(stillSighting), untilMs = 90_000L)
        val suppressed = suppressor.activeMask(listOf(stillSighting), atMs = t)
        assertEquals(listOf(false), suppressed)

        // The object (or whatever is now in that spot) moves to a clearly different box.
        val moved = sighting(score = bagScore, box = listOf(0.05f, 0.05f, 0.15f, 0.15f))
        val afterMove = suppressor.activeMask(listOf(moved), atMs = t + 500L)
        assertEquals("a moved box must not still read as the suppressed static object", listOf(true), afterMove)
    }

    @Test
    fun `a score jump on the same box also breaks the match immediately`() {
        val suppressor = StaticObjectSuppressor(staticAfterMs = 90_000L)
        val stillSighting = sighting(score = bagScore, box = bagBox)

        val t = buildUpToJustBefore(suppressor, listOf(stillSighting), untilMs = 90_000L)
        val suppressed = suppressor.activeMask(listOf(stillSighting), atMs = t)
        assertEquals(listOf(false), suppressed)

        val higherScore = sighting(score = bagScore + 0.30f, box = bagBox)
        val afterScoreJump = suppressor.activeMask(listOf(higherScore), atMs = t + 500L)
        assertEquals(listOf(true), afterScoreJump)
    }

    @Test
    fun `disappearance clears state -- returning to the same spot starts a fresh grace window`() {
        val suppressor = StaticObjectSuppressor(staticAfterMs = 90_000L)
        val stillSighting = sighting(score = bagScore, box = bagBox)

        val t = buildUpToJustBefore(suppressor, listOf(stillSighting), untilMs = 90_000L)
        val suppressed = suppressor.activeMask(listOf(stillSighting), atMs = t)
        assertEquals(listOf(false), suppressed)

        // The label vanishes entirely for a frame -- the bag was picked up.
        suppressor.activeMask(emptyList(), atMs = t + 1_000L)

        // Something (maybe the bag again) reappears at the exact same spot and score. It must
        // not be instantly suppressed just because that spot was static before.
        val backAgain = suppressor.activeMask(listOf(stillSighting), atMs = t + 2_000L)
        assertEquals(listOf(true), backAgain)
    }

    @Test
    fun `reset clears all tracked state unconditionally, with no gap required`() {
        val suppressor = StaticObjectSuppressor(staticAfterMs = 90_000L)
        val stillSighting = sighting(score = bagScore, box = bagBox)

        val t = buildUpToJustBefore(suppressor, listOf(stillSighting), untilMs = 90_000L)
        val suppressed = suppressor.activeMask(listOf(stillSighting), atMs = t)
        assertEquals(listOf(false), suppressed)

        suppressor.reset()

        // Called back-to-back, 1 ms later -- reset must not depend on a call gap to take effect.
        val afterReset = suppressor.activeMask(listOf(stillSighting), atMs = t + 1L)
        assertEquals(listOf(true), afterReset)
    }

    @Test
    fun `a second object elsewhere is unaffected by a suppressed static object`() {
        val suppressor = StaticObjectSuppressor(staticAfterMs = 90_000L)
        val staticCoat = sighting(score = bagScore, box = bagBox)
        val enteringPerson = sighting(score = 0.62f, box = listOf(0.70f, 0.10f, 0.95f, 0.90f))

        val t = buildUpToJustBefore(suppressor, listOf(staticCoat), untilMs = 90_000L)
        val suppressed = suppressor.activeMask(listOf(staticCoat), atMs = t) // coat is now suppressed
        assertEquals(listOf(false), suppressed)

        // The real person walks in at a clearly separate box, same frame the coat is suppressed.
        val mask = suppressor.activeMask(listOf(staticCoat, enteringPerson), atMs = t + 500L)
        assertEquals(listOf(false, true), mask)
    }

    @Test
    fun `different labels at the same box are tracked independently`() {
        val suppressor = StaticObjectSuppressor(staticAfterMs = 90_000L)
        val asPerson = sighting(label = "person", score = bagScore, box = bagBox)
        val asCat = sighting(label = "cat", score = bagScore, box = bagBox)

        suppressor.activeMask(listOf(asPerson), atMs = 0L)
        // "cat" has never been seen at this box before -- must not inherit the person clock.
        val catMask = suppressor.activeMask(listOf(asCat), atMs = 90_000L)
        assertEquals(listOf(true), catMask)
    }

    @Test
    fun `a null box cannot be tracked and always stays active`() {
        val suppressor = StaticObjectSuppressor(staticAfterMs = 90_000L)
        val unlocatable = sighting(score = bagScore, box = null)

        repeat(10) {
            val mask = suppressor.activeMask(listOf(unlocatable), atMs = it * 30_000L)
            assertEquals(listOf(true), mask)
        }
    }

    // ---------------------------------------------------------------------------------------
    // C2 -- call gaps must not carry accumulated static time across a stream drop
    // ---------------------------------------------------------------------------------------

    @Test
    fun `a call gap larger than maxGapMs starts a fresh run -- a stream drop must not carry over accumulated static time`() {
        // Mirrors the review's exact scenario: detected steadily, then a real gap (stream
        // down, thermal-throttle idle cadence, detection toggled off) far longer than a
        // couple of missed passes, then a similar re-detection at roughly the same spot.
        val suppressor = StaticObjectSuppressor(staticAfterMs = 90_000L, maxGapMs = 3_000L)
        val stillSighting = sighting(score = bagScore, box = bagBox)

        var t = 0L
        while (t <= 80_000L) {
            val mask = suppressor.activeMask(listOf(stillSighting), atMs = t)
            assertTrue("expected active at t=$t", mask.single())
            t += 1_000L
        }
        // 80s of steady detection so far -- not yet suppressed (under the 90s bar), and this
        // is exactly the state an old, gap-blind implementation would carry straight through
        // the drop below.

        // The stream drops for two minutes: no calls at all while it's down.
        val afterGap = t + 120_000L

        val reappeared = suppressor.activeMask(listOf(stillSighting), atMs = afterGap)
        assertEquals(
            "a detection returning after a gap far longer than maxGapMs must start a fresh " +
                "run, not inherit the pre-gap stableSinceMs and suppress with zero grace",
            listOf(true),
            reappeared
        )

        // And it must genuinely need a fresh 90s, not be a hair's breadth from suppression --
        // the bug this guards against would have it suppressed on this very next call.
        val stillActiveShortlyAfter = suppressor.activeMask(listOf(stillSighting), atMs = afterGap + 5_000L)
        assertEquals(listOf(true), stillActiveShortlyAfter)
    }

    @Test
    fun `a gap at or under maxGapMs still continues the same run`() {
        val suppressor = StaticObjectSuppressor(staticAfterMs = 90_000L, maxGapMs = 3_000L)
        val stillSighting = sighting(score = bagScore, box = bagBox)

        suppressor.activeMask(listOf(stillSighting), atMs = 0L)
        // Exactly maxGapMs later -- this app's own idle capture interval -- must still match.
        val stillActive = suppressor.activeMask(listOf(stillSighting), atMs = 3_000L)
        assertEquals(listOf(true), stillActive)

        // Keep going, still never gapping by more than maxGapMs, all the way to suppression --
        // confirms the earlier exact-boundary gap didn't leave any lasting damage either way.
        var t = 4_000L
        while (t < 90_000L) {
            val mask = suppressor.activeMask(listOf(stillSighting), atMs = t)
            assertTrue("expected active at t=$t", mask.single())
            t += 1_000L
        }
        val suppressed = suppressor.activeMask(listOf(stillSighting), atMs = t)
        assertEquals(listOf(false), suppressed)
    }

    // ---------------------------------------------------------------------------------------
    // C3 -- matching must anchor to run start, not ratchet frame-to-frame
    // ---------------------------------------------------------------------------------------

    @Test
    fun `slow but genuine drift never accumulates to a false suppression, even though each frame looks static against the previous one`() {
        // The box drifts right by 5% of its own width every second. Each consecutive pair
        // overlaps at IoU ~0.90 -- comfortably "the same object" under a naive
        // previous-frame-only comparison -- but the object has moved by more than a full
        // box-width after 40s. Anchoring must catch this well before any single run could
        // accumulate 90s.
        val suppressor = StaticObjectSuppressor(staticAfterMs = 90_000L)
        val width = 0.20f
        val dx = 0.010f // 5% of width per frame; per-step IoU ~0.90, comfortably above 0.85

        var everSuppressed = false
        for (n in 0..200) {
            val left = 0.10f + n * dx
            val box = listOf(left, 0.10f, left + width, 0.30f)
            val mask = suppressor.activeMask(listOf(sighting(score = bagScore, box = box)), atMs = n * 1_000L)
            if (!mask.single()) everSuppressed = true
        }

        assertTrue(
            "a continuously (if slowly) moving object must never be suppressed -- if this is " +
                "true, matching drifted against the previous frame instead of the run's anchor",
            !everSuppressed
        )
    }

    @Test
    fun `a real standing person's natural score noise keeps breaking the anchor match, so they are never suppressed -- asserted well past the 90s boundary`() {
        // Perfectly stationary box (this person is not moving), but the score alternates by
        // 0.008 -- larger than the 0.005 tolerance, and realistic noise for a live detection
        // (pose, lighting, re-encode) rather than the bag's exact repeat. What happens to this
        // person at and after the 90s mark is asserted directly, not left implicit.
        val suppressor = StaticObjectSuppressor(staticAfterMs = 90_000L)
        val box = listOf(0.10f, 0.10f, 0.30f, 0.90f)
        val scores = listOf(0.55f, 0.558f, 0.55f, 0.558f)

        var everSuppressed = false
        var t = 0L
        while (t <= 150_000L) {
            val score = scores[((t / 1_000L) % scores.size).toInt()]
            val mask = suppressor.activeMask(listOf(sighting(score = score, box = box)), atMs = t)
            if (!mask.single()) everSuppressed = true
            t += 1_000L
        }

        assertTrue(
            "a person whose score naturally varies by more than the tolerance must never be " +
                "suppressed, including well past the 90s mark",
            !everSuppressed
        )
    }

    // ---------------------------------------------------------------------------------------
    // I1 -- best-overlap match, not first-found; tightened score tolerance
    // ---------------------------------------------------------------------------------------

    @Test
    fun `an ambiguous sighting inherits the best-overlap candidate, not whichever was tracked first`() {
        // Two distinct, simultaneously-tracked "person" runs, close enough together that a
        // later sighting can plausibly match either by IoU: OLD, continuously detected since
        // t=0 (95s old by the decision point -- already past the 90s bar on its own), and NEW,
        // first seen at t=95_000 (0s old at the decision point). A first-match implementation
        // (order of insertion: OLD before NEW) would wrongly attach the ambiguous sighting to
        // OLD and suppress it instantly; the correct implementation picks NEW, which overlaps
        // the ambiguous sighting more closely (IoU ~0.95 vs ~0.89).
        val suppressor = StaticObjectSuppressor(staticAfterMs = 90_000L, maxGapMs = 3_000L)
        val height = listOf(0.10f, 0.30f) // top, bottom -- shared by every box below
        fun boxAt(left: Float) = listOf(left, height[0], left + 0.20f, height[1])

        val oldBox = boxAt(0.400f)
        val newBox = boxAt(0.417f) // 0.017 away from oldBox -> IoU(old, new) ~0.843, so New
        // never merges into Old's run when it's first seen.

        var t = 0L
        while (t <= 95_000L) {
            val sightings = if (t == 95_000L) {
                listOf(sighting(score = bagScore, box = oldBox), sighting(score = bagScore, box = newBox))
            } else {
                listOf(sighting(score = bagScore, box = oldBox))
            }
            suppressor.activeMask(sightings, atMs = t)
            t += 1_000L
        }

        val ambiguousBox = boxAt(0.412f) // ~0.012 from oldBox (IoU ~0.887), ~0.005 from newBox (IoU ~0.951)
        val mask = suppressor.activeMask(listOf(sighting(score = bagScore, box = ambiguousBox)), atMs = 96_000L)

        assertEquals(
            "the ambiguous sighting overlaps NEW (age 1s at this point) more than OLD (age " +
                "96s); picking OLD -- the first-tracked candidate -- would wrongly suppress it",
            listOf(true),
            mask
        )
    }

    @Test
    fun `a low-scoring person cannot inherit the static bag's run just by being close in score`() {
        val suppressor = StaticObjectSuppressor(staticAfterMs = 90_000L)
        val staticBag = sighting(score = bagScore, box = bagBox)
        val t = buildUpToJustBefore(suppressor, listOf(staticBag), untilMs = 90_000L)
        val bagSuppressed = suppressor.activeMask(listOf(staticBag), atMs = t)
        assertEquals(listOf(false), bagSuppressed)

        // A different, real object at the same spot, score just 0.01 off -- within the
        // rejected 0.02 tolerance, outside the current 0.005 one.
        val differentPerson = sighting(score = bagScore + 0.01f, box = bagBox)
        val mask = suppressor.activeMask(listOf(differentPerson), atMs = t + 500L)
        assertEquals(
            "a 0.01 score difference must not be treated as the same object under the " +
                "tightened tolerance -- it must get its own fresh grace window",
            listOf(true),
            mask
        )
    }

    @Test
    fun `the default score tolerance is tight enough not to span the low-confidence band above the detector floor`() {
        // The live threshold is 0.30f (LiteRtObjectDetector). A tolerance of 0.02 spans
        // 0.296-0.336 -- effectively the whole low-confidence band just above the floor. This
        // pins the tightened constant so it cannot silently widen back out.
        assertTrue(StaticObjectSuppressor.DEFAULT_SCORE_TOLERANCE <= 0.005f)
    }

    // ---------------------------------------------------------------------------------------
    // IoU geometry, tested directly
    // ---------------------------------------------------------------------------------------

    @Test
    fun `IoU of identical boxes is 1, of disjoint boxes is 0`() {
        val box = listOf(0.1f, 0.1f, 0.5f, 0.5f)
        assertEquals(1.0, StaticObjectSuppressor.iou(box, box), 1e-9)

        val disjoint = listOf(0.6f, 0.6f, 0.9f, 0.9f)
        assertEquals(0.0, StaticObjectSuppressor.iou(box, disjoint), 1e-9)
    }

    @Test
    fun `IoU tolerates a couple of pixels of jitter on a person-sized box`() {
        // ~300x600 px box in a 640-wide analysis frame; edges nudged by ~2 px each.
        val original = listOf(0.30f, 0.10f, 0.77f, 0.65f)
        val jittered = listOf(0.303f, 0.097f, 0.767f, 0.653f)
        assertTrue(StaticObjectSuppressor.iou(original, jittered) >= StaticObjectSuppressor.DEFAULT_IOU_THRESHOLD)
    }
}
