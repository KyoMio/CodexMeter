package com.kmnexus.codexmeter.ui.settings

object SettingsBatteryOptimizationGate {
    /**
     * Offer the optional "ignore battery optimization" guidance only when background refresh is on and
     * the app is still subject to optimization. A null status means we could not read it, so we stay
     * quiet rather than nag. Exemption never bypasses Doze entirely; it only improves reliability.
     */
    fun shouldOfferExemption(
        batteryOptimizationIgnored: Boolean?,
        backgroundRefreshEnabled: Boolean,
    ): Boolean =
        backgroundRefreshEnabled && batteryOptimizationIgnored == false
}
