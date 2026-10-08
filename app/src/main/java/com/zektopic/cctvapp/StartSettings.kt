package com.zektopic.cctvapp

import android.content.Intent

/**
 * Read-only view of the extras on a start Intent. A null source means the system
 * restarted the service (START_STICKY after the process was killed) with a null Intent.
 */
interface StartExtras {
    fun string(key: String): String?
    fun bool(key: String): Boolean?
    fun int(key: String): Int?
}

/** The settings [CctvServerService.onStartCommand] applies on every (re)start. */
data class StartSettings(
    val videoCodec: String,
    val showPreview: Boolean,
    val width: Int,
    val height: Int,
    val authEnabled: Boolean,
    val authUsername: String,
    val authPassword: String,
    val showTimestamp: Boolean,
    val showDate: Boolean,
    val timestampPosition: String,
    val timestampSize: String,
    val detectionEnabled: Boolean,
    val motionDetectionEnabled: Boolean,
    val objectDetectionEnabled: Boolean,
    val audioEnabled: Boolean,
    val flashlightEnabled: Boolean,
    val nightModeEnabled: Boolean,
    val hdrEnabled: Boolean,
) {
    companion object {
        /**
         * Extras win; anything absent -- including every setting when [extras] is null --
         * keeps the stored value. Never substitute a hardcoded default here: the result is
         * persisted back, so a default would silently overwrite the user's settings.
         */
        fun resolve(extras: StartExtras?, stored: StartSettings): StartSettings = StartSettings(
            videoCodec = extras?.string("video_codec") ?: stored.videoCodec,
            showPreview = extras?.bool("show_preview") ?: stored.showPreview,
            width = extras?.int("width") ?: stored.width,
            height = extras?.int("height") ?: stored.height,
            authEnabled = extras?.bool("auth_enabled") ?: stored.authEnabled,
            authUsername = extras?.string("auth_username") ?: stored.authUsername,
            authPassword = extras?.string("auth_password") ?: stored.authPassword,
            showTimestamp = extras?.bool("show_timestamp") ?: stored.showTimestamp,
            showDate = extras?.bool("show_date") ?: stored.showDate,
            timestampPosition = extras?.string("timestamp_position") ?: stored.timestampPosition,
            timestampSize = extras?.string("timestamp_size") ?: stored.timestampSize,
            detectionEnabled = extras?.bool("detection_enabled") ?: stored.detectionEnabled,
            motionDetectionEnabled = extras?.bool("motion_detection_enabled") ?: stored.motionDetectionEnabled,
            objectDetectionEnabled = extras?.bool("object_detection_enabled") ?: stored.objectDetectionEnabled,
            audioEnabled = extras?.bool("audio_enabled") ?: stored.audioEnabled,
            flashlightEnabled = extras?.bool("flashlight_enabled") ?: stored.flashlightEnabled,
            nightModeEnabled = extras?.bool("night_mode_enabled") ?: stored.nightModeEnabled,
            hdrEnabled = extras?.bool("hdr_enabled") ?: stored.hdrEnabled,
        )
    }
}

/** [StartExtras] over a real Intent; an absent extra reads as null so the stored value wins. */
class IntentStartExtras(private val intent: Intent) : StartExtras {
    override fun string(key: String): String? = intent.getStringExtra(key)
    override fun bool(key: String): Boolean? =
        if (intent.hasExtra(key)) intent.getBooleanExtra(key, false) else null
    override fun int(key: String): Int? =
        if (intent.hasExtra(key)) intent.getIntExtra(key, 0) else null
}
