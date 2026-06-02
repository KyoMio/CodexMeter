package com.kmnexus.codexmeter.data.preferences

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.kmnexus.codexmeter.domain.model.LocalAccountId
import com.kmnexus.codexmeter.domain.model.ProviderId
import java.io.File
import kotlinx.coroutines.flow.first

data class CurrentAccountSelection(
    val providerId: ProviderId,
    val localAccountId: LocalAccountId,
)

interface CurrentAccountReader {
    suspend fun currentAccountSelection(): CurrentAccountSelection?
}

interface CurrentAccountStore : CurrentAccountReader {
    suspend fun updateCurrentAccountSelection(selection: CurrentAccountSelection?)
}

class CurrentAccountPreferences(
    internal val dataStore: DataStore<Preferences>,
) : CurrentAccountStore {
    override suspend fun currentAccountSelection(): CurrentAccountSelection? {
        val preferences = dataStore.data.first()
        val providerId = preferences[Keys.CURRENT_PROVIDER_ID]?.takeIf { it.isNotBlank() }
        val localAccountId = preferences[Keys.CURRENT_LOCAL_ACCOUNT_ID]?.takeIf { it.isNotBlank() }

        return if (providerId != null && localAccountId != null) {
            CurrentAccountSelection(
                providerId = ProviderId(providerId),
                localAccountId = LocalAccountId(localAccountId),
            )
        } else {
            null
        }
    }

    override suspend fun updateCurrentAccountSelection(selection: CurrentAccountSelection?) {
        dataStore.edit { preferences ->
            if (selection == null) {
                preferences.remove(Keys.CURRENT_PROVIDER_ID)
                preferences.remove(Keys.CURRENT_LOCAL_ACCOUNT_ID)
            } else {
                preferences[Keys.CURRENT_PROVIDER_ID] = selection.providerId.value
                preferences[Keys.CURRENT_LOCAL_ACCOUNT_ID] = selection.localAccountId.value
            }
        }
    }

    companion object {
        fun create(file: File): CurrentAccountPreferences =
            CurrentAccountPreferences(
                PreferenceDataStoreFactory.create(
                    produceFile = { file },
                ),
            )
    }

    object Keys {
        val CURRENT_PROVIDER_ID = stringPreferencesKey("current_provider_id")
        val CURRENT_LOCAL_ACCOUNT_ID = stringPreferencesKey("current_local_account_id")
    }
}
