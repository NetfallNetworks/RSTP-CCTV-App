package com.zektopic.cctvapp

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BootCameraAccessPolicyTest {

    private val preR = BootCameraAccessPolicy.FIRST_RESTRICTED_SDK_INT - 1
    private val r = BootCameraAccessPolicy.FIRST_RESTRICTED_SDK_INT
    private val postR = BootCameraAccessPolicy.FIRST_RESTRICTED_SDK_INT + 3

    @Test
    fun `below Android 11 the direct start path is used regardless of the overlay permission`() {
        assertFalse(BootCameraAccessPolicy.shouldLaunchActivityForCamera(preR, canDrawOverlays = true))
        assertFalse(BootCameraAccessPolicy.shouldLaunchActivityForCamera(preR, canDrawOverlays = false))
    }

    @Test
    fun `on Android 11+ the activity route is used only when the overlay permission is granted`() {
        assertTrue(BootCameraAccessPolicy.shouldLaunchActivityForCamera(r, canDrawOverlays = true))
        assertFalse(BootCameraAccessPolicy.shouldLaunchActivityForCamera(r, canDrawOverlays = false))
        assertTrue(BootCameraAccessPolicy.shouldLaunchActivityForCamera(postR, canDrawOverlays = true))
        assertFalse(BootCameraAccessPolicy.shouldLaunchActivityForCamera(postR, canDrawOverlays = false))
    }

    @Test
    fun `the camera is unattended-reachable below Android 11 no matter what`() {
        assertTrue(BootCameraAccessPolicy.canReachCameraUnattended(preR, canDrawOverlays = true))
        assertTrue(BootCameraAccessPolicy.canReachCameraUnattended(preR, canDrawOverlays = false))
    }

    @Test
    fun `on Android 11+ the camera is only unattended-reachable with the overlay permission granted`() {
        assertTrue(BootCameraAccessPolicy.canReachCameraUnattended(r, canDrawOverlays = true))
        assertFalse(BootCameraAccessPolicy.canReachCameraUnattended(r, canDrawOverlays = false))
    }
}
