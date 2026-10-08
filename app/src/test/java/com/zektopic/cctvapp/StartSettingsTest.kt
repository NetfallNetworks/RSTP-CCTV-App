package com.zektopic.cctvapp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StartSettingsTest {

    private val stored = StartSettings(
        videoCodec = "H265", showPreview = true, width = 1920, height = 1080,
        authEnabled = true, authUsername = "u", authPassword = "p",
        showTimestamp = true, showDate = true, timestampPosition = "BOTTOM_LEFT",
        timestampSize = "LARGE", detectionEnabled = true, motionDetectionEnabled = false,
        objectDetectionEnabled = false, audioEnabled = true, flashlightEnabled = true,
        nightModeEnabled = true, hdrEnabled = true,
    )

    @Test
    fun `a null intent restart keeps every stored setting`() {
        val r = StartSettings.resolve(null, stored)
        assertTrue(r.detectionEnabled)
        assertEquals(1920, r.width)
        assertEquals(1080, r.height)
        assertEquals(stored, r)
    }

    @Test
    fun `extras override stored values and the rest stay stored`() {
        val extras = object : StartExtras {
            override fun string(key: String): String? = null
            override fun bool(key: String): Boolean? = if (key == "detection_enabled") false else null
            override fun int(key: String): Int? = if (key == "width") 1280 else null
        }
        val r = StartSettings.resolve(extras, stored)
        assertEquals(stored.copy(detectionEnabled = false, width = 1280), r)
    }
}
