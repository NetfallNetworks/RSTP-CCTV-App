package com.zektopic.cctvapp

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BootStartPolicyTest {

    @Test
    fun `a device that has never had the switch touched defaults to starting`() {
        // hasStoredValue = false is what SharedPreferences.contains() returns for a
        // fresh install. storedValue is meaningless here and is passed as both to prove
        // it is genuinely ignored.
        assertTrue(BootStartPolicy.resolve(hasStoredValue = false, storedValue = true))
        assertTrue(BootStartPolicy.resolve(hasStoredValue = false, storedValue = false))
    }

    @Test
    fun `an explicit off sticks once the switch has been touched`() {
        assertFalse(BootStartPolicy.resolve(hasStoredValue = true, storedValue = false))
    }

    @Test
    fun `an explicit on is honoured once the switch has been touched`() {
        assertTrue(BootStartPolicy.resolve(hasStoredValue = true, storedValue = true))
    }

    @Test
    fun `the documented default matches what an untouched device resolves to`() {
        assertTrue(BootStartPolicy.DEFAULT_START_ON_BOOT)
        assertTrue(
            BootStartPolicy.resolve(
                hasStoredValue = false,
                storedValue = !BootStartPolicy.DEFAULT_START_ON_BOOT
            )
        )
    }
}
