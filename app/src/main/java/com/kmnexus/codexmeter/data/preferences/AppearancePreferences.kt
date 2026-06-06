package com.kmnexus.codexmeter.data.preferences

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.kmnexus.codexmeter.domain.theme.AppearancePreferenceStore
import com.kmnexus.codexmeter.domain.theme.ThemeMode
import java.io.File
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class AppearancePreferences(
    internal val dataStore: DataStore<Preferences>,
) : AppearancePreferenceStore {
    override val themeMode: Flow<ThemeMode> =
        dataStore.data.map { prefs -> ThemeMode.fromStorage(prefs[Keys.THEME_MODE]) }

    override suspend fun setThemeMode(mode: ThemeMode) {
        dataStore.edit { it[Keys.THEME_MODE] = mode.storageValue }
    }

    private object Keys {
        val THEME_MODE = stringPreferencesKey("theme_mode")
    }

    companion object {
        fun create(file: File): AppearancePreferences =
            AppearancePreferences(
                PreferenceDataStoreFactory.create(
                    produceFile = { file },
                ),
            )
    }
}
