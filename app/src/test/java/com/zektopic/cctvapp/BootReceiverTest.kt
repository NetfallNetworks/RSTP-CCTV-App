package com.zektopic.cctvapp

import android.content.Intent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * `BootReceiver` itself has Android imports (`Context`, `NotificationManager`, ...) and cannot
 * be instantiated or have `onReceive` exercised by a plain JVM test -- there is no Robolectric
 * or instrumented test target in this project (see `CctvServerServiceClockWiringTest` for the
 * established pattern this follows).
 *
 * [BootReceiver.isRestartTriggerAction] is the one piece of the restart-after-update fix that
 * is free of Context, so it is exercised directly here. `Intent.ACTION_BOOT_COMPLETED` and
 * `Intent.ACTION_MY_PACKAGE_REPLACED` are compile-time String constants, so referencing them
 * from a plain unit test works the same way `AdaptiveQualityTest` compares against
 * `PowerManager`'s thermal constants -- no Robolectric needed for a static final String.
 *
 * The start-on-boot gate that follows the action check in `onReceive` is already covered,
 * value-for-value, by [BootStartPolicyTest]; what is not covered there is that `onReceive`
 * still reaches that single, shared gate for the new action instead of special-casing around
 * it, which the last test below checks at the source level.
 */
class BootReceiverTest {

    @Test
    fun `BOOT_COMPLETED is a restart trigger`() {
        assertTrue(BootReceiver.isRestartTriggerAction(Intent.ACTION_BOOT_COMPLETED))
    }

    @Test
    fun `MY_PACKAGE_REPLACED is a restart trigger, handled the same as BOOT_COMPLETED`() {
        assertTrue(BootReceiver.isRestartTriggerAction(Intent.ACTION_MY_PACKAGE_REPLACED))
    }

    @Test
    fun `unrelated actions, and no action at all, are ignored`() {
        assertFalse(BootReceiver.isRestartTriggerAction(Intent.ACTION_SCREEN_ON))
        assertFalse(BootReceiver.isRestartTriggerAction(Intent.ACTION_PACKAGE_REPLACED))
        assertFalse(BootReceiver.isRestartTriggerAction("com.example.SOME_OTHER_ACTION"))
        assertFalse(BootReceiver.isRestartTriggerAction(null))
    }

    /**
     * Regression guard: `onReceive` must check `AppPreferences.getStartOnBoot(context)`
     * exactly once, unconditionally, right after the (now two-action) trigger guard -- not
     * inside a branch keyed on which of the two actions matched. If a future edit special-cased
     * `MY_PACKAGE_REPLACED` around this check, an explicit "off" would stop sticking for it,
     * which is exactly the behaviour [BootStartPolicy]'s doc promises never happens.
     */
    @Test
    fun `the start-on-boot gate is checked once, unconditionally, for both trigger actions`() {
        val source = receiverSource()
        val gateCount = Regex("""AppPreferences\.getStartOnBoot\(context\)""").findAll(source).count()

        assertTrue(
            "Expected exactly one AppPreferences.getStartOnBoot(context) call in " +
                "BootReceiver.onReceive so both BOOT_COMPLETED and MY_PACKAGE_REPLACED share the " +
                "same gate; found $gateCount",
            gateCount == 1
        )
        assertTrue(
            "Expected onReceive's trigger guard to route through isRestartTriggerAction(...) " +
                "rather than checking Intent.ACTION_BOOT_COMPLETED directly, so MY_PACKAGE_REPLACED " +
                "is not silently dropped by a leftover single-action check",
            source.contains("isRestartTriggerAction(intent.action)")
        )
    }

    private fun receiverSource(): String {
        val candidates = listOf(
            File("app/src/main/java/com/zektopic/cctvapp/BootReceiver.kt"),
            File("src/main/java/com/zektopic/cctvapp/BootReceiver.kt")
        )
        val file = candidates.firstOrNull { it.exists() }
            ?: error(
                "BootReceiver.kt not found from working directory ${File(".").absolutePath} -- " +
                    "tried $candidates. Update this test's search paths if the module layout changed."
            )
        return file.readText()
    }
}
