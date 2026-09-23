package com.zektopic.cctvapp

import android.Manifest
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/**
 * Restarts the camera server after a reboot, or after this app itself has just been
 * updated in place (an `adb install -r`, or a managed/Play update on the deployed
 * device), when the user has asked for that.
 *
 * Both triggers -- `BOOT_COMPLETED` and `MY_PACKAGE_REPLACED` -- resolve through
 * exactly the same policy below and the same [MainActivity] launch route; see
 * [isRestartTriggerAction]. The device this app runs on is an unattended patio
 * camera, and an update killing the foreground service is otherwise indistinguishable
 * from the reboot case this receiver already had to solve: nothing else restarts it,
 * and the camera sits down until someone walks over and taps the switch.
 *
 * Two separate Android restrictions apply here, and they are easy to conflate:
 *
 * 1. API 31+ refuses to let a background component start a foreground service whose
 *    type is `camera` or `microphone` at all -- the start throws
 *    `ForegroundServiceStartNotAllowedException`. `BOOT_COMPLETED` and
 *    `MY_PACKAGE_REPLACED` are both on Android's own exemption list for this one, so
 *    it has not been observed here, but the start is still guarded against it (and the
 *    SecurityException some OEM builds throw instead), degrading to a tap-to-resume
 *    notification rather than crashing the receiver.
 * 2. API 30+ (Android 11) separately denies the camera/microphone *themselves* to a
 *    foreground service that was started while the app was in the background --
 *    logged as "Foreground service started from background can not have
 *    location/camera/microphone access". This one is NOT on the same exemption list as
 *    #1 -- neither `BOOT_COMPLETED` nor `MY_PACKAGE_REPLACED` exempts it, and neither
 *    does declaring `foregroundServiceType="camera"` (that only says what the service
 *    is allowed to ask for, not that a background-started service is allowed to ask).
 *    This is the one that actually bites here: the service starts fine from either
 *    broadcast, but if it is started directly the camera open is refused and
 *    `startStream()` never completes -- with nothing thrown at this call site to
 *    catch. See [BootCameraAccessPolicy] for how this receiver routes around it (that
 *    routing is keyed on SDK version and the overlay permission, not on which of the
 *    two broadcasts triggered it, so it applies unchanged here), and
 *    `CctvServerService.startStream()`'s catch block for how that failure is kept out
 *    of `/status` either way.
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
        private const val RESUME_NOTIFICATION_ID = 2
        private const val CHANNEL_ID = "CctvServerChannel"

        /**
         * Tells [MainActivity] this launch exists only to get the camera server running
         * from a genuinely foreground context after a boot or an in-place update -- see
         * [BootCameraAccessPolicy].
         */
        const val ACTION_START_SERVER_FROM_BOOT = "com.zektopic.cctvapp.ACTION_START_SERVER_FROM_BOOT"

        /**
         * True for either system broadcast this receiver acts on. Kept as a small,
         * Context-free function (like [BootStartPolicy] and [BootCameraAccessPolicy]) so
         * it is unit-testable without a Robolectric or instrumented target -- see
         * `BootReceiverTest`.
         */
        fun isRestartTriggerAction(action: String?): Boolean =
            action == Intent.ACTION_BOOT_COMPLETED || action == Intent.ACTION_MY_PACKAGE_REPLACED
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (!isRestartTriggerAction(intent.action)) return

        // Opt-in only. Silently re-arming a camera after every reboot or update is not
        // a reasonable default for a device that might have changed hands or location.
        if (!AppPreferences.getStartOnBoot(context)) {
            Log.d(TAG, "Start-on-boot disabled; ignoring ${intent.action}")
            return
        }

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "Camera permission not granted; not starting server on boot/update")
            return
        }

        // Settings.canDrawOverlays has existed since API 23; minSdk here is 24, so it is
        // always safe to call directly.
        val canDrawOverlays = Settings.canDrawOverlays(context)

        if (BootCameraAccessPolicy.shouldLaunchActivityForCamera(Build.VERSION.SDK_INT, canDrawOverlays)) {
            launchActivityToStartServer(context)
            return
        }

        if (!BootCameraAccessPolicy.canReachCameraUnattended(Build.VERSION.SDK_INT, canDrawOverlays)) {
            // Android 11+ with no overlay permission: there is no legal unattended path
            // left (see BootCameraAccessPolicy's doc). Starting the service directly
            // would "succeed" -- both triggering broadcasts are exempt from restriction
            // #1 above -- while the camera silently fails later with nothing to catch, which is
            // exactly the bug this branch exists to stop reproducing. Go straight to
            // asking a human, the same as an outright start failure below.
            Log.w(TAG, "No unattended path to the camera on this OS/permission combo; prompting the user")
            notifyResumeRequired(context)
            return
        }

        // Pre-Android 11: the while-in-use restriction above does not exist yet, so the
        // direct start this app used before that OS version still works unchanged.
        startServiceDirectly(context)
    }

    /**
     * Routes the start through a real, resumed [MainActivity] instead of starting
     * [CctvServerService] directly. `FLAG_ACTIVITY_NEW_TASK` is required from a
     * non-Activity context; the launch itself is legal from the background here
     * because the app holds `SYSTEM_ALERT_WINDOW` (verified above) -- a documented
     * exemption to the *separate* background-activity-start restriction. That exemption
     * is granted for holding the permission, not for which broadcast woke this receiver
     * up, so it covers a `MY_PACKAGE_REPLACED`-triggered launch exactly the same way it
     * covers `BOOT_COMPLETED`. Once the Activity is actually resumed, the app is no
     * longer "in the background" for the while-in-use check, so its own
     * `autoStartServerIfNeeded`-style start (see `MainActivity.handleBootStartIntent`)
     * reaches the camera normally.
     */
    private fun launchActivityToStartServer(context: Context) {
        val activityIntent = Intent(context, MainActivity::class.java).apply {
            action = ACTION_START_SERVER_FROM_BOOT
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            )
        }
        try {
            context.startActivity(activityIntent)
            Log.d(TAG, "Launched MainActivity to start the camera server in the foreground after boot/update")
        } catch (e: Exception) {
            Log.w(TAG, "Could not launch MainActivity after boot/update; prompting the user instead", e)
            notifyResumeRequired(context)
        }
    }

    private fun startServiceDirectly(context: Context) {
        val serviceIntent = Intent(context, CctvServerService::class.java).apply {
            putExtra("video_codec", AppPreferences.getVideoCodec(context))
            putExtra("width", AppPreferences.getVideoWidth(context))
            putExtra("height", AppPreferences.getVideoHeight(context))
            putExtra("force_software", AppPreferences.getForceSoftware(context))
            putExtra("show_preview", AppPreferences.getShowPreview(context))
            putExtra("auth_enabled", AppPreferences.getAuthEnabled(context))
            putExtra("auth_username", AppPreferences.getUsername(context))
            putExtra("auth_password", AppPreferences.getPassword(context))
            putExtra("show_timestamp", AppPreferences.getShowTimestamp(context))
            putExtra("show_date", AppPreferences.getShowDate(context))
            putExtra("timestamp_position", AppPreferences.getTimestampPosition(context))
            putExtra("timestamp_size", AppPreferences.getTimestampSize(context))
            putExtra("flashlight_enabled", AppPreferences.getFlashlightEnabled(context))
            putExtra("night_mode_enabled", AppPreferences.getNightModeEnabled(context))
            putExtra("detection_enabled", AppPreferences.getDetectionEnabled(context))
            putExtra("motion_detection_enabled", AppPreferences.getMotionDetectionEnabled(context))
            putExtra("object_detection_enabled", AppPreferences.getObjectDetectionEnabled(context))
            putExtra("audio_enabled", AppPreferences.getAudioEnabled(context))
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
            Log.d(TAG, "Camera server started after boot/update")
        } catch (e: Exception) {
            // Covers ForegroundServiceStartNotAllowedException (API 31+) without
            // referencing a class that does not exist on older platforms, plus the
            // SecurityException some OEM builds throw instead.
            Log.w(TAG, "Could not start server on boot/update; prompting the user instead", e)
            notifyResumeRequired(context)
        }
    }

    /** Falls back to a notification the user can tap to start the server by hand. */
    private fun notifyResumeRequired(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        val contentIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_cctv)
            .setContentTitle(context.getString(R.string.boot_resume_title))
            .setContentText(context.getString(R.string.boot_resume_text))
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .build()

        try {
            val manager = context.getSystemService(NotificationManager::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                manager.createNotificationChannel(
                    android.app.NotificationChannel(
                        CHANNEL_ID,
                        context.getString(R.string.notification_channel_name),
                        NotificationManager.IMPORTANCE_DEFAULT
                    )
                )
            }
            manager.notify(RESUME_NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            Log.w(TAG, "Could not post resume notification", e)
        }
    }
}
