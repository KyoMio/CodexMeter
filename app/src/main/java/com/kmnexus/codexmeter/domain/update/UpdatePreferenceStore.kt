package com.kmnexus.codexmeter.domain.update

data class UpdatePreferences(
    val autoCheckEnabled: Boolean = true,
    val notifyOnUpdateEnabled: Boolean = true,
    val lastNotifiedVersionName: String? = null,
    val availableUpdate: AppUpdateInfo? = null,
)

interface UpdatePreferenceStore {
    suspend fun preferences(): UpdatePreferences
    suspend fun setAutoCheckEnabled(enabled: Boolean)
    suspend fun setNotifyOnUpdateEnabled(enabled: Boolean)
    suspend fun setLastNotifiedVersion(versionName: String?)
    suspend fun setAvailableUpdate(update: AppUpdateInfo?)
}

object NoopUpdatePreferenceStore : UpdatePreferenceStore {
    override suspend fun preferences(): UpdatePreferences = UpdatePreferences()
    override suspend fun setAutoCheckEnabled(enabled: Boolean) = Unit
    override suspend fun setNotifyOnUpdateEnabled(enabled: Boolean) = Unit
    override suspend fun setLastNotifiedVersion(versionName: String?) = Unit
    override suspend fun setAvailableUpdate(update: AppUpdateInfo?) = Unit
}
