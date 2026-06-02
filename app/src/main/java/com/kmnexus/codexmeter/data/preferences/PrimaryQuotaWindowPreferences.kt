package com.kmnexus.codexmeter.data.preferences

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.kmnexus.codexmeter.domain.model.QuotaWindowId
import com.kmnexus.codexmeter.domain.settings.DEFAULT_PRIMARY_QUOTA_WINDOW_ID
import com.kmnexus.codexmeter.domain.settings.PrimaryQuotaWindowPreferenceStore
import com.kmnexus.codexmeter.domain.settings.SUPPORTED_PRIMARY_QUOTA_WINDOW_IDS
import java.io.File
import kotlinx.coroutines.flow.first

class PrimaryQuotaWindowPreferences(
    private val dataStore: DataStore<Preferences>,
) : PrimaryQuotaWindowPreferenceStore {
    override suspend fun primaryQuotaWindowId(): QuotaWindowId {
        val stored = dataStore.data.first()[Keys.PRIMARY_QUOTA_WINDOW_ID]
            ?.takeIf { it.isNotBlank() }
            ?.let(::QuotaWindowId)

        return stored?.takeIf { it in SUPPORTED_PRIMARY_QUOTA_WINDOW_IDS }
            ?: DEFAULT_PRIMARY_QUOTA_WINDOW_ID
    }

    override suspend fun updatePrimaryQuotaWindowId(windowId: QuotaWindowId) {
        val storedWindowId = windowId.takeIf { it in SUPPORTED_PRIMARY_QUOTA_WINDOW_IDS }
            ?: DEFAULT_PRIMARY_QUOTA_WINDOW_ID
        dataStore.edit { preferences ->
            preferences[Keys.PRIMARY_QUOTA_WINDOW_ID] = storedWindowId.value
        }
    }

    companion object {
        fun create(file: File): PrimaryQuotaWindowPreferences =
            PrimaryQuotaWindowPreferences(
                PreferenceDataStoreFactory.create(
                    produceFile = { file },
                ),
            )
    }

    object Keys {
        val PRIMARY_QUOTA_WINDOW_ID = stringPreferencesKey("primary_quota_window_id")
    }
}
