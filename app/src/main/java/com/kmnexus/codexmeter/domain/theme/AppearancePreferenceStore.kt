package com.kmnexus.codexmeter.domain.theme

import kotlinx.coroutines.flow.Flow

interface AppearancePreferenceStore {
    val themeMode: Flow<ThemeMode>
    suspend fun setThemeMode(mode: ThemeMode)
}
