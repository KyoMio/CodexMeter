package com.kmnexus.codexmeter.ui.settings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsBatteryOptimizationGateTest {
    @Test
    fun `offers exemption when background refresh is on and optimization not ignored`() {
        assertTrue(
            SettingsBatteryOptimizationGate.shouldOfferExemption(
                batteryOptimizationIgnored = false,
                backgroundRefreshEnabled = true,
            ),
        )
    }

    @Test
    fun `hides hint once the app is already exempt from battery optimization`() {
        assertFalse(
            SettingsBatteryOptimizationGate.shouldOfferExemption(
                batteryOptimizationIgnored = true,
                backgroundRefreshEnabled = true,
            ),
        )
    }

    @Test
    fun `hides hint when background refresh is disabled`() {
        assertFalse(
            SettingsBatteryOptimizationGate.shouldOfferExemption(
                batteryOptimizationIgnored = false,
                backgroundRefreshEnabled = false,
            ),
        )
    }

    @Test
    fun `stays hidden when the optimization status is unknown`() {
        assertFalse(
            SettingsBatteryOptimizationGate.shouldOfferExemption(
                batteryOptimizationIgnored = null,
                backgroundRefreshEnabled = true,
            ),
        )
    }
}
