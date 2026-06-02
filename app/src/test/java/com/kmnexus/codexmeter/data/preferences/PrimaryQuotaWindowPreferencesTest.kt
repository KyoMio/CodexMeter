package com.kmnexus.codexmeter.data.preferences

import androidx.datastore.preferences.preferencesDataStoreFile
import com.kmnexus.codexmeter.domain.model.QuotaWindowId
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
class PrimaryQuotaWindowPreferencesTest {
    @Test
    fun `default primary quota window is five hour`() = runTest {
        val preferences = preferences("primary-window-default-test.preferences_pb")

        assertEquals(QuotaWindowId("five_hour"), preferences.primaryQuotaWindowId())
    }

    @Test
    fun `reads persisted primary quota window`() = runTest {
        val preferences = preferences("primary-window-persisted-test.preferences_pb")
        preferences.updatePrimaryQuotaWindowId(QuotaWindowId("weekly"))

        assertEquals(QuotaWindowId("weekly"), preferences.primaryQuotaWindowId())
    }

    private fun preferences(fileName: String): PrimaryQuotaWindowPreferences =
        PrimaryQuotaWindowPreferences.create(
            file = RuntimeEnvironment.getApplication()
                .preferencesDataStoreFile(fileName),
        )
}
