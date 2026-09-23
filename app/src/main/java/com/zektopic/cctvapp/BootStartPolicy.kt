package com.zektopic.cctvapp

/**
 * Decides whether the camera server should restart itself after a reboot.
 *
 * Kept free of Android and SharedPreferences imports, the same way
 * [EncoderImplementation] separates its migration rule from the framework code that
 * reads and writes it, so the default-and-override rule can be unit tested on the JVM
 * without a SharedPreferences fake.
 *
 * The rule this encodes: the Enable Server switch's own last position is the restart
 * intent. There is no separate opt-in step -- toggling the switch persists it
 * immediately (see `MainActivity`'s `serverSwitchListener`), and [BootReceiver] reads
 * it back after `BOOT_COMPLETED` -- and, identically, after `MY_PACKAGE_REPLACED`, so
 * an app update restarts the server the same way a reboot does; see
 * [BootReceiver.isRestartTriggerAction]. A device that has never had the switch touched
 * resolves to [DEFAULT_START_ON_BOOT]: this deployment is an unattended camera, so a
 * fresh install that stays dark until someone opens the app is the wrong default. Once
 * a person has explicitly turned the switch off, that decision has to stick across a
 * reboot or an update -- it is a deliberate "not now", not a crash to route around.
 */
object BootStartPolicy {

    /** What a device that has never persisted the switch's state should do at boot. */
    const val DEFAULT_START_ON_BOOT = true

    /**
     * Resolves the effective boot-start flag from what SharedPreferences has on file.
     *
     * Mirrors `SharedPreferences.getBoolean(key, default)` semantics explicitly instead
     * of leaning on the platform call's own default parameter, so the default lives in
     * one documented, testable place rather than a bare literal at each call site.
     *
     * @param hasStoredValue whether the preference key has ever been written
     * @param storedValue the last value written, meaningless when [hasStoredValue] is false
     */
    fun resolve(hasStoredValue: Boolean, storedValue: Boolean): Boolean =
        if (hasStoredValue) storedValue else DEFAULT_START_ON_BOOT
}
