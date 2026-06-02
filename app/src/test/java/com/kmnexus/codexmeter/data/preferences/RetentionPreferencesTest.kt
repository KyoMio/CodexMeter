package com.kmnexus.codexmeter.data.preferences

import androidx.datastore.preferences.preferencesDataStoreFile
import com.kmnexus.codexmeter.domain.settings.RetentionPreference
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
// Robolectric SDK 36 currently requires Java 21, while this project is pinned to Java 17.
@Config(sdk = [35])
class RetentionPreferencesTest {
    @Test
    fun `default retention preference is thirty days`() = runTest {
        val preferences = preferences("retention-default-test.preferences_pb")

        assertEquals(RetentionPreference.ThirtyDays, preferences.retentionPreference())
    }

    @Test
    fun `reads persisted retention preference`() = runTest {
        val preferences = preferences("retention-persisted-test.preferences_pb")
        preferences.updateRetentionPreference(RetentionPreference.SevenDays)

        assertEquals(RetentionPreference.SevenDays, preferences.retentionPreference())
    }

    private fun preferences(fileName: String): RetentionPreferences =
        RetentionPreferences.create(
            file = RuntimeEnvironment.getApplication()
                .preferencesDataStoreFile(fileName),
        )
}
