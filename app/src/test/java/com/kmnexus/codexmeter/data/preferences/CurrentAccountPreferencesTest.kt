package com.kmnexus.codexmeter.data.preferences

import androidx.datastore.preferences.preferencesDataStoreFile
import com.kmnexus.codexmeter.domain.model.LocalAccountId
import com.kmnexus.codexmeter.domain.model.ProviderId
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
// Robolectric SDK 36 currently requires Java 21, while this project is pinned to Java 17.
@Config(sdk = [35])
class CurrentAccountPreferencesTest {
    @Test
    fun `reads persisted current account selection`() = runTest {
        val preferences = CurrentAccountPreferences.create(
            file = RuntimeEnvironment.getApplication()
                .preferencesDataStoreFile("current-account-test.preferences_pb"),
        )
        preferences.updateCurrentAccountSelection(
            CurrentAccountSelection(
                providerId = ProviderId("codex"),
                localAccountId = LocalAccountId("local-current"),
            ),
        )

        val selection = preferences.currentAccountSelection()

        assertEquals(
            CurrentAccountSelection(
                providerId = ProviderId("codex"),
                localAccountId = LocalAccountId("local-current"),
            ),
            selection,
        )
    }
}
