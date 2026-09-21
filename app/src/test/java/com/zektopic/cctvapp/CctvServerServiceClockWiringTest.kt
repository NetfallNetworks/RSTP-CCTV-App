package com.zektopic.cctvapp

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Regression guard for the C1 finding on PR #10: [StaticObjectSuppressor.activeMask] must be
 * driven by a monotonic clock (`SystemClock.elapsedRealtime()`), never wall-clock time
 * (`System.currentTimeMillis()`). A wall-clock feed is a live-safety bug, not a style nit -- an
 * NTP step forward past boot or a network reconnect can jump `atMs` by more than the static
 * duration in a single call, suppressing a live person with zero grace; a step backward means
 * nothing ever suppresses. See [StaticObjectSuppressor]'s "Clock" KDoc section.
 *
 * `CctvServerService` has Android imports (`Context`, `Bitmap`, ...) and cannot be instantiated
 * or exercised by a plain JVM test the way [StaticObjectSuppressor] itself is -- there is no
 * Robolectric or instrumented test target in this project. This checks the one call site that
 * matters directly in source, which is exactly what an adversarial reviewer did to find C1 in
 * the first place, so it is at least caught mechanically the next time it regresses rather than
 * only by another review pass.
 */
class CctvServerServiceClockWiringTest {

    private fun serviceSource(): String {
        val candidates = listOf(
            File("app/src/main/java/com/zektopic/cctvapp/CctvServerService.kt"),
            File("src/main/java/com/zektopic/cctvapp/CctvServerService.kt")
        )
        val file = candidates.firstOrNull { it.exists() }
            ?: error(
                "CctvServerService.kt not found from working directory " +
                    "${File(".").absolutePath} -- tried $candidates. Update this test's search " +
                    "paths if the module layout changed."
            )
        return file.readText()
    }

    /**
     * Matches the first two positional arguments of the `activeMask(...)` call, whatever else
     * follows (a third `maxGapMs = ...` argument, multi-line formatting, trailing whitespace).
     * Group 2 is the clock argument this test cares about.
     */
    private val activeMaskCallRegex =
        Regex("""staticObjectSuppressor\.activeMask\(\s*([\w.]+)\s*,\s*([\w.]+)\s*[,)]""")

    @Test
    fun `activeMask is called with a monotonic clock argument, not capturedAtMs`() {
        val source = serviceSource()
        val call = activeMaskCallRegex.find(source)
            ?: error(
                "No staticObjectSuppressor.activeMask(...) call site found -- update this " +
                    "test's regex if the call was refactored."
            )
        val clockArgument = call.groupValues[2]

        assertTrue(
            "activeMask's clock argument must come from SystemClock.elapsedRealtime() (e.g. " +
                "a variable named elapsedRealtimeMs), not capturedAtMs / System.currentTimeMillis" +
                "() -- found argument '$clockArgument'. Passing wall-clock time here is exactly " +
                "the C1 finding from PR #10's review.",
            clockArgument.contains("elapsed", ignoreCase = true)
        )
        assertTrue(
            "activeMask's clock argument must not be the wall-clock capturedAtMs variable",
            clockArgument != "capturedAtMs"
        )
    }

    @Test
    fun `the monotonic clock variable is actually assigned from SystemClock elapsedRealtime, not currentTimeMillis`() {
        val source = serviceSource()
        // Find `val elapsedRealtimeMs = <expr>` (or whatever the C1 test above resolved the
        // clock argument's name to) and check its right-hand side.
        val call = activeMaskCallRegex.find(source)
            ?: error("No staticObjectSuppressor.activeMask(...) call site found")
        val clockArgument = call.groupValues[2]

        val assignment = Regex("""val\s+${Regex.escape(clockArgument)}\s*(?::\s*Long)?\s*=\s*([^\n]+)""")
            .find(source)
            ?: error(
                "No 'val $clockArgument = ...' assignment found in CctvServerService.kt -- it " +
                    "may be a function parameter threaded from elsewhere; if so, trace it back " +
                    "to its source and update this test to check the real assignment site."
            )
        val rhs = assignment.groupValues[1]

        assertTrue(
            "'$clockArgument' must be assigned from SystemClock.elapsedRealtime(), found: '$rhs'",
            rhs.contains("elapsedRealtime()")
        )
        assertTrue(
            "'$clockArgument' must not be assigned from System.currentTimeMillis(), found: '$rhs'",
            !rhs.contains("currentTimeMillis()")
        )
    }
}
