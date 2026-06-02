package com.kmnexus.codexmeter.app

import android.app.ActivityManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.PowerManager
import java.util.Locale

data class DeviceEnvironment(
    val androidRelease: String,
    val deviceModel: String,
    val locale: String,
    val batteryOptimizationIgnored: Boolean?,
    val backgroundRestricted: String,
    val networkType: String,
    val dataSaver: String,
    val appFirstInstallAtMillis: Long?,
    val appLastUpdateAtMillis: Long?,
)

fun interface DeviceEnvironmentReader {
    fun read(): DeviceEnvironment
}

internal class AndroidDeviceEnvironmentReader(
    context: Context,
) : DeviceEnvironmentReader {
    private val appContext = context.applicationContext

    override fun read(): DeviceEnvironment {
        val powerManager = appContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val activityManager = appContext.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val connectivity = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

        val packageInfo = runCatching {
            appContext.packageManager.getPackageInfo(appContext.packageName, 0)
        }.getOrNull()

        return DeviceEnvironment(
            androidRelease = Build.VERSION.RELEASE ?: "unknown",
            deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}",
            locale = Locale.getDefault().toLanguageTag(),
            batteryOptimizationIgnored = powerManager
                ?.isIgnoringBatteryOptimizations(appContext.packageName),
            backgroundRestricted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                (activityManager?.isBackgroundRestricted == true).toString()
            } else {
                "not_applicable"
            },
            networkType = connectivity.networkType(),
            dataSaver = connectivity.dataSaverStatus(),
            appFirstInstallAtMillis = packageInfo?.firstInstallTime,
            appLastUpdateAtMillis = packageInfo?.lastUpdateTime,
        )
    }

    private fun ConnectivityManager?.networkType(): String {
        if (this == null) return "unknown"
        val capabilities = activeNetwork?.let { getNetworkCapabilities(it) } ?: return "none"
        return when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            else -> "unknown"
        }
    }

    private fun ConnectivityManager?.dataSaverStatus(): String =
        when (this?.restrictBackgroundStatus) {
            ConnectivityManager.RESTRICT_BACKGROUND_STATUS_DISABLED -> "disabled"
            ConnectivityManager.RESTRICT_BACKGROUND_STATUS_WHITELISTED -> "whitelisted"
            ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED -> "enabled"
            else -> "unknown"
        }
}
