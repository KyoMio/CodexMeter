package com.kmnexus.codexmeter.data.preferences

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.kmnexus.codexmeter.domain.update.AppUpdateInfo
import com.kmnexus.codexmeter.domain.update.UpdatePreferences
import com.kmnexus.codexmeter.domain.update.UpdatePreferenceStore
import java.io.File
import kotlinx.coroutines.flow.first

class UpdatePreferencesDataStore(
    internal val dataStore: DataStore<Preferences>,
) : UpdatePreferenceStore {
    override suspend fun preferences(): UpdatePreferences {
        val stored = dataStore.data.first()
        val version = stored[Keys.AVAILABLE_VERSION]?.takeIf { it.isNotBlank() }
        val available = version?.let {
            AppUpdateInfo(
                versionName = it,
                releasePageUrl = stored[Keys.AVAILABLE_RELEASE_URL].orEmpty(),
                apkDownloadUrl = stored[Keys.AVAILABLE_APK_URL].orEmpty(),
                apkFileName = stored[Keys.AVAILABLE_APK_NAME].orEmpty(),
                releaseNotes = stored[Keys.AVAILABLE_RELEASE_NOTES]?.takeIf { it.isNotBlank() },
            )
        }
        return UpdatePreferences(
            autoCheckEnabled = stored[Keys.AUTO_CHECK_ENABLED] ?: true,
            notifyOnUpdateEnabled = stored[Keys.NOTIFY_ON_UPDATE_ENABLED] ?: true,
            lastNotifiedVersionName = stored[Keys.LAST_NOTIFIED_VERSION]?.takeIf { it.isNotBlank() },
            availableUpdate = available,
        )
    }

    override suspend fun setAutoCheckEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.AUTO_CHECK_ENABLED] = enabled }
    }

    override suspend fun setNotifyOnUpdateEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.NOTIFY_ON_UPDATE_ENABLED] = enabled }
    }

    override suspend fun setLastNotifiedVersion(versionName: String?) {
        dataStore.edit { prefs ->
            if (versionName.isNullOrBlank()) prefs.remove(Keys.LAST_NOTIFIED_VERSION)
            else prefs[Keys.LAST_NOTIFIED_VERSION] = versionName
        }
    }

    override suspend fun setAvailableUpdate(update: AppUpdateInfo?) {
        dataStore.edit { prefs ->
            if (update == null || update.versionName.isBlank()) {
                prefs.remove(Keys.AVAILABLE_VERSION)
                prefs.remove(Keys.AVAILABLE_RELEASE_URL)
                prefs.remove(Keys.AVAILABLE_APK_URL)
                prefs.remove(Keys.AVAILABLE_APK_NAME)
                prefs.remove(Keys.AVAILABLE_RELEASE_NOTES)
            } else {
                prefs[Keys.AVAILABLE_VERSION] = update.versionName
                prefs[Keys.AVAILABLE_RELEASE_URL] = update.releasePageUrl
                prefs[Keys.AVAILABLE_APK_URL] = update.apkDownloadUrl
                prefs[Keys.AVAILABLE_APK_NAME] = update.apkFileName
                val notes = update.releaseNotes
                if (notes.isNullOrBlank()) prefs.remove(Keys.AVAILABLE_RELEASE_NOTES)
                else prefs[Keys.AVAILABLE_RELEASE_NOTES] = notes
            }
        }
    }

    private object Keys {
        val AUTO_CHECK_ENABLED = booleanPreferencesKey("auto_check_enabled")
        val NOTIFY_ON_UPDATE_ENABLED = booleanPreferencesKey("notify_on_update_enabled")
        val LAST_NOTIFIED_VERSION = stringPreferencesKey("last_notified_version")
        val AVAILABLE_VERSION = stringPreferencesKey("available_version")
        val AVAILABLE_RELEASE_URL = stringPreferencesKey("available_release_url")
        val AVAILABLE_APK_URL = stringPreferencesKey("available_apk_url")
        val AVAILABLE_APK_NAME = stringPreferencesKey("available_apk_name")
        val AVAILABLE_RELEASE_NOTES = stringPreferencesKey("available_release_notes")
    }

    companion object {
        fun create(file: File): UpdatePreferencesDataStore =
            UpdatePreferencesDataStore(PreferenceDataStoreFactory.create(produceFile = { file }))
    }
}
