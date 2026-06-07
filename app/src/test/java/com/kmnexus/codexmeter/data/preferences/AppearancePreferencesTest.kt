package com.kmnexus.codexmeter.data.preferences

import androidx.datastore.preferences.preferencesDataStoreFile
import com.kmnexus.codexmeter.domain.theme.ThemeMode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
// Robolectric SDK 36 needs Java 21; project is pinned to Java 17.
@Config(sdk = [35])
class AppearancePreferencesTest {
    private fun store(file: String) = AppearancePreferences.create(
        file = RuntimeEnvironment.getApplication().preferencesDataStoreFile(file),
    )

    @Test fun `defaults to system when unset`() = runTest {
        assertEquals(ThemeMode.SYSTEM, store("appearance-default.preferences_pb").themeMode.first())
    }

    @Test fun `persists and reads back each mode`() = runTest {
        val subject = store("appearance-roundtrip.preferences_pb")
        for (mode in ThemeMode.entries) {
            subject.setThemeMode(mode)
            assertEquals(mode, subject.themeMode.first())
        }
    }
}
