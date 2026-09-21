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

    @Test
    fun `a single identical hit is never suppressed -- only recurrence over time is`() {
        val suppressor = StaticObjectSuppressor(staticAfterMs = 90_000L)
        val mask = suppressor.activeMask(listOf(sighting(score = bagScore, box = bagBox)), atMs = 0L)
        assertEquals(listOf(true), mask)
    }

    @Test
    fun `identical score and box recurring past the static duration is suppressed`() {
        val suppressor = StaticObjectSuppressor(staticAfterMs = 90_000L)
        val sightings = listOf(sighting(score = bagScore, box = bagBox))

        suppressor.activeMask(sightings, atMs = 0L)
        suppressor.activeMask(sightings, atMs = 30_000L)
        val stillActiveAt89s = suppressor.activeMask(sightings, atMs = 89_000L)
        val suppressedAt90s = suppressor.activeMask(sightings, atMs = 90_000L)
        val suppressedAt120s = suppressor.activeMask(sightings, atMs = 120_000L)

        assertEquals(listOf(true), stillActiveAt89s)
        assertEquals(listOf(false), suppressedAt90s)
        assertEquals(listOf(false), suppressedAt120s)
    }

    @Test
    fun `a motionless real person is still recorded through the whole grace window`() {
        // Same contract as the test above, phrased from the safety side: anything under the
        // duration threshold -- an ordinary pause, not a piece of furniture -- keeps recording.
        val suppressor = StaticObjectSuppressor(staticAfterMs = 90_000L)
        val standingStill = listOf(sighting(score = 0.55f, box = listOf(0.10f, 0.10f, 0.30f, 0.90f)))

        var t = 0L
        while (t < 89_000L) {
            val mask = suppressor.activeMask(standingStill, atMs = t)
            assertTrue("expected active at t=$t", mask.single())
            t += 1_000L
        }
    }

    @Test
    fun `pixel jitter in box and score within tolerance still counts as the same static object`() {
        val suppressor = StaticObjectSuppressor(
            staticAfterMs = 90_000L,
            iouThreshold = StaticObjectSuppressor.DEFAULT_IOU_THRESHOLD,
            scoreTolerance = StaticObjectSuppressor.DEFAULT_SCORE_TOLERANCE
        )

        // Box edges wobble by ~0.5% of frame width/height each pass; score wobbles by 0.005.
        val jitteredBoxes = listOf(
            listOf(0.400f, 0.300f, 0.600f, 0.700f),
            listOf(0.405f, 0.298f, 0.598f, 0.702f),
            listOf(0.397f, 0.303f, 0.603f, 0.699f),
            listOf(0.402f, 0.301f, 0.599f, 0.701f)
        )
        val jitteredScores = listOf(bagScore, bagScore + 0.004f, bagScore - 0.003f, bagScore + 0.001f)

        var lastMask = listOf(true)
        for (i in jitteredBoxes.indices) {
            lastMask = suppressor.activeMask(
                listOf(sighting(score = jitteredScores[i], box = jitteredBoxes[i])),
                atMs = i * 30_000L
            )
        }
        // i=3 is at 90_000 ms with an unbroken (jittered-but-matched) run since t=0.
        assertEquals(listOf(false), lastMask)
    }

    @Test
    fun `a real move breaks suppression immediately`() {
        val suppressor = StaticObjectSuppressor(staticAfterMs = 90_000L)
        val stillSighting = sighting(score = bagScore, box = bagBox)

        suppressor.activeMask(listOf(stillSighting), atMs = 0L)
        val suppressed = suppressor.activeMask(listOf(stillSighting), atMs = 90_000L)
        assertEquals(listOf(false), suppressed)

        // The object (or whatever is now in that spot) moves to a clearly different box.
        val moved = sighting(score = bagScore, box = listOf(0.05f, 0.05f, 0.15f, 0.15f))
        val afterMove = suppressor.activeMask(listOf(moved), atMs = 90_500L)
        assertEquals("a moved box must not still read as the suppressed static object", listOf(true), afterMove)
    }

    @Test
    fun `a score jump on the same box also breaks the match immediately`() {
        val suppressor = StaticObjectSuppressor(staticAfterMs = 90_000L)
        val stillSighting = sighting(score = bagScore, box = bagBox)
        suppressor.activeMask(listOf(stillSighting), atMs = 0L)
        suppressor.activeMask(listOf(stillSighting), atMs = 90_000L)

        val higherScore = sighting(score = bagScore + 0.30f, box = bagBox)
        val afterScoreJump = suppressor.activeMask(listOf(higherScore), atMs = 90_500L)
        assertEquals(listOf(true), afterScoreJump)
    }

    @Test
    fun `disappearance clears state -- returning to the same spot starts a fresh grace window`() {
        val suppressor = StaticObjectSuppressor(staticAfterMs = 90_000L)
        val stillSighting = sighting(score = bagScore, box = bagBox)

        suppressor.activeMask(listOf(stillSighting), atMs = 0L)
        val suppressed = suppressor.activeMask(listOf(stillSighting), atMs = 90_000L)
        assertEquals(listOf(false), suppressed)

        // The label vanishes entirely for a frame -- the bag was picked up.
        suppressor.activeMask(emptyList(), atMs = 91_000L)

        // Something (maybe the bag again) reappears at the exact same spot and score. It must
        // not be instantly suppressed just because that spot was static before.
        val backAgain = suppressor.activeMask(listOf(stillSighting), atMs = 92_000L)
        assertEquals(listOf(true), backAgain)
    }

    @Test
    fun `a second object elsewhere is unaffected by a suppressed static object`() {
        val suppressor = StaticObjectSuppressor(staticAfterMs = 90_000L)
        val staticCoat = sighting(score = bagScore, box = bagBox)
        val enteringPerson = sighting(score = 0.62f, box = listOf(0.70f, 0.10f, 0.95f, 0.90f))

        suppressor.activeMask(listOf(staticCoat), atMs = 0L)
        suppressor.activeMask(listOf(staticCoat), atMs = 90_000L) // coat is now suppressed

        // The real person walks in at a clearly separate box, same frame the coat is suppressed.
        val mask = suppressor.activeMask(listOf(staticCoat, enteringPerson), atMs = 90_500L)
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
