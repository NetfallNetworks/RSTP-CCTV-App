package com.zektopic.cctvapp

/**
 * Decides how [BootReceiver] should try to get the camera server running after a
 * reboot, on a device where the switch's last position says it should.
 *
 * Kept free of Android imports, the same way [BootStartPolicy] separates its rule from
 * the SharedPreferences code that reads and writes it, so this is unit-testable on the
 * JVM without an Android runtime.
 *
 * ## The problem this encodes
 *
 * Starting the *service* itself from `BOOT_COMPLETED` is fine -- boot broadcasts are on
 * Android's own exemption list for starting a foreground service from the background.
 * But Android 11 (API 30) added a second, narrower restriction on top of that: a
 * foreground service started while the app is in the background cannot open the
 * camera or microphone, full stop -- logged as "Foreground service started from
 * background can not have location/camera/microphone access". That restriction has its
 * own exemption list, and it does *not* include boot broadcasts. It also does not
 * include merely holding `SYSTEM_ALERT_WINDOW` ("draw over other apps") -- that
 * permission does not exempt a service from the while-in-use check.
 *
 * `SYSTEM_ALERT_WINDOW` exempts something else entirely: starting an *Activity* from
 * the background (a separate restriction from the one above). This app already
 * requires that permission for its own overlay and already refuses to start the server
 * without it (see `MainActivity.startServer()`), so on a working install it is already
 * granted. [shouldLaunchActivityForCamera] is true exactly when that route is both
 * necessary (API 30+, where the while-in-use restriction exists) and legally available
 * (the overlay permission is granted, so background-launching the Activity is allowed).
 * When it resolves true, [BootReceiver] launches `MainActivity` instead of starting the
 * service directly; the Activity reaching a genuinely resumed, visible state puts the
 * app in the foreground *before* it asks for the camera, so the while-in-use
 * restriction never applies to that start at all -- the ordinary switch-driven start
 * path (already foreground-safe) takes it from there.
 *
 * When it resolves false on API 30+ (no overlay permission), there is no unattended
 * route left: starting the service directly would "succeed" exactly as it does today
 * (boot broadcasts are exempt from *that* check) while the camera silently fails later,
 * which is bug this exists to stop reproducing. [BootReceiver] skips straight to the
 * resume notification in that case instead of pretending to have started.
 */
object BootCameraAccessPolicy {

    /** Android 11, where the foreground-service while-in-use camera restriction begins. */
    const val FIRST_RESTRICTED_SDK_INT = 30

    fun shouldLaunchActivityForCamera(sdkInt: Int, canDrawOverlays: Boolean): Boolean =
        sdkInt >= FIRST_RESTRICTED_SDK_INT && canDrawOverlays

    /**
     * True when starting the service directly from `BOOT_COMPLETED` is expected to
     * actually get the camera (either the while-in-use restriction does not apply on
     * this OS version, or [shouldLaunchActivityForCamera] is handling it a different
     * way). False means neither path can reach the camera unattended right now.
     */
    fun canReachCameraUnattended(sdkInt: Int, canDrawOverlays: Boolean): Boolean =
        sdkInt < FIRST_RESTRICTED_SDK_INT || canDrawOverlays
}
