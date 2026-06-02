package com.kmnexus.codexmeter.data.preferences

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.kmnexus.codexmeter.domain.settings.RetentionPreference
import com.kmnexus.codexmeter.domain.settings.RetentionPreferenceStore
import java.io.File
import kotlinx.coroutines.flow.first

class RetentionPreferences(
    private val dataStore: DataStore<Preferences>,
) : RetentionPreferenceStore {
    override suspend fun retentionPreference(): RetentionPreference {
        val stored = dataStore.data.first()[Keys.RETENTION_PREFERENCE]
        return RetentionPreference.entries.firstOrNull { it.name == stored }
            ?: RetentionPreference.ThirtyDays
    }

    override suspend fun updateRetentionPreference(preference: RetentionPreference) {
        dataStore.edit { preferences ->
            preferences[Keys.RETENTION_PREFERENCE] = preference.name
        }
    }

    companion object {
        fun create(file: File): RetentionPreferences =
            RetentionPreferences(
                PreferenceDataStoreFactory.create(
                    produceFile = { file },
                ),
            )
    }

    object Keys {
        val RETENTION_PREFERENCE = stringPreferencesKey("retention_preference")
    }
}
