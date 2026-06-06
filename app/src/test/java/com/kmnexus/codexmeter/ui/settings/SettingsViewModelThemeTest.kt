package com.kmnexus.codexmeter.ui.settings

import com.kmnexus.codexmeter.domain.theme.AppearancePreferenceStore
import com.kmnexus.codexmeter.domain.theme.ThemeMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelThemeTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private class FakeAppearancePreferenceStore(
        initialMode: ThemeMode,
    ) : AppearancePreferenceStore {
        private val _themeMode = MutableStateFlow(initialMode)
        override val themeMode: Flow<ThemeMode> = _themeMode

        override suspend fun setThemeMode(mode: ThemeMode) {
            _themeMode.value = mode
        }
    }

    @Test
    fun `loadSettings reads theme from store`() = runTest {
        val store = FakeAppearancePreferenceStore(ThemeMode.DARK)
        val viewModel = SettingsViewModel(appearancePreferenceStore = store)
        viewModel.loadSettings()
        runCurrent()

        assertEquals(ThemeMode.DARK, viewModel.uiState.value.appearance.themeMode)
    }

    @Test
    fun `updateThemeMode updates ui state and persists to store`() = runTest {
        val store = FakeAppearancePreferenceStore(ThemeMode.SYSTEM)
        val viewModel = SettingsViewModel(appearancePreferenceStore = store)

        viewModel.updateThemeMode(ThemeMode.LIGHT)
        runCurrent()

        assertEquals(ThemeMode.LIGHT, viewModel.uiState.value.appearance.themeMode)
        assertEquals(ThemeMode.LIGHT, store.themeMode.first())
    }
}
