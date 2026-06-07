package com.kmnexus.codexmeter.ui.settings

import android.content.Intent
import android.net.Uri
import android.provider.Settings

internal object SettingsBatteryOptimizationTarget {
    fun requestExemptionIntent(packageName: String): Intent =
        Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:$packageName"),
        )
}
