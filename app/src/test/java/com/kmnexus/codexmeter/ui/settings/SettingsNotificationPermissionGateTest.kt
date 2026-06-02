package com.kmnexus.codexmeter.ui.settings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsNotificationPermissionGateTest {
    @Test
    fun `android 13 enable without permission requests runtime permission`() {
        assertTrue(
            SettingsNotificationPermissionGate.shouldRequestPermission(
                sdkInt = 33,
                notificationPermissionGranted = false,
                requestedEnabled = true,
            ),
        )
    }

    @Test
    fun `disabling notifications never requests runtime permission`() {
        assertFalse(
            SettingsNotificationPermissionGate.shouldRequestPermission(
                sdkInt = 35,
                notificationPermissionGranted = false,
                requestedEnabled = false,
            ),
        )
    }

    @Test
    fun `android 12 does not request notification runtime permission`() {
        assertFalse(
            SettingsNotificationPermissionGate.shouldRequestPermission(
                sdkInt = 32,
                notificationPermissionGranted = false,
                requestedEnabled = true,
            ),
        )
    }
}
