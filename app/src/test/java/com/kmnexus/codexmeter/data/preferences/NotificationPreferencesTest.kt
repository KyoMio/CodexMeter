package com.kmnexus.codexmeter.data.preferences

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStoreFile
import com.kmnexus.codexmeter.domain.model.LocalAccountId
import com.kmnexus.codexmeter.domain.model.ProviderId
import com.kmnexus.codexmeter.domain.model.QuotaWindowId
import com.kmnexus.codexmeter.domain.settings.NotificationAccountSelection
import com.kmnexus.codexmeter.domain.settings.NotificationPreferences
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
// Robolectric SDK 36 currently requires Java 21, while this project is pinned to Java 17.
@Config(sdk = [35])
class NotificationPreferencesTest {
    @Test
    fun `default notification preferences match mvp settings`() = runTest {
        val preferences = preferences("notification-default-test.preferences_pb")

        assertEquals(NotificationPreferences(), preferences.notificationPreferences())
    }

    @Test
    fun `persistent notification account defaults to follow current and five hour window`() = runTest {
        val preferences = preferences("notification-persistent-default-test.preferences_pb")

        val stored = preferences.notificationPreferences()

        assertNull(stored.persistentNotificationAccount)
        assertEquals(QuotaWindowId("five_hour"), stored.persistentNotificationWindowId)
    }

    @Test
    fun `new account quota alert defaults enable five hour only`() = runTest {
        val stored = preferences("notification-account-alert-default-test.preferences_pb")
            .notificationPreferences()

        assertTrue(
            stored.isQuotaAlertEnabled(
                providerId = ProviderId("codex"),
                localAccountId = LocalAccountId("local-1"),
                windowId = QuotaWindowId("five_hour"),
            ),
        )
        assertFalse(
            stored.isQuotaAlertEnabled(
                providerId = ProviderId("codex"),
                localAccountId = LocalAccountId("local-1"),
                windowId = QuotaWindowId("weekly"),
            ),
        )
    }

    @Test
    fun `reads persisted notification toggles and thresholds`() = runTest {
        val preferences = preferences("notification-persisted-test.preferences_pb")
        preferences.updateNotificationPreferences(
            NotificationPreferences(
                statusNotificationEnabled = false,
                quotaAlertsEnabled = false,
                accountErrorsEnabled = true,
                cautionThreshold = 75,
                warningThreshold = 20,
                persistentNotificationAccount = NotificationAccountSelection(
                    providerId = ProviderId("codex"),
                    localAccountId = LocalAccountId("local-2"),
                ),
                persistentNotificationWindowId = QuotaWindowId("weekly"),
            ).withAccountQuotaAlertEnabled(
                providerId = ProviderId("codex"),
                localAccountId = LocalAccountId("local-1"),
                windowId = QuotaWindowId("weekly"),
                enabled = true,
            ),
        )

        val stored = preferences.notificationPreferences()

        assertEquals(false, stored.statusNotificationEnabled)
        assertEquals(false, stored.quotaAlertsEnabled)
        assertEquals(true, stored.accountErrorsEnabled)
        assertEquals(75, stored.cautionThreshold)
        assertEquals(20, stored.warningThreshold)
        assertEquals(
            NotificationAccountSelection(
                providerId = ProviderId("codex"),
                localAccountId = LocalAccountId("local-2"),
            ),
            stored.persistentNotificationAccount,
        )
        assertEquals(QuotaWindowId("weekly"), stored.persistentNotificationWindowId)
        assertTrue(
            stored.isQuotaAlertEnabled(
                providerId = ProviderId("codex"),
                localAccountId = LocalAccountId("local-1"),
                windowId = QuotaWindowId("weekly"),
            ),
        )
    }

    @Test
    fun `migrates legacy used-percent threshold preferences to remaining-percent thresholds`() = runTest {
        val preferences = preferences("notification-legacy-threshold-test.preferences_pb")
        preferences.replaceRawThresholds(caution = 70, warning = 90)

        assertEquals(
            NotificationPreferences(
                cautionThreshold = 30,
                warningThreshold = 10,
            ),
            preferences.notificationPreferences(),
        )
    }

    @Test
    fun `legacy selected primary window migrates to enabled alert window for account`() = runTest {
        val preferences = preferences("notification-legacy-primary-window-test.preferences_pb")
        val migrated = NotificationPreferences().withLegacyPrimaryWindowAlertForAccount(
            providerId = ProviderId("codex"),
            localAccountId = LocalAccountId("local-1"),
            legacyPrimaryWindowId = QuotaWindowId("weekly"),
        )

        preferences.updateNotificationPreferences(migrated)

        val stored = preferences.notificationPreferences()
        assertFalse(
            stored.isQuotaAlertEnabled(
                providerId = ProviderId("codex"),
                localAccountId = LocalAccountId("local-1"),
                windowId = QuotaWindowId("five_hour"),
            ),
        )
        assertTrue(
            stored.isQuotaAlertEnabled(
                providerId = ProviderId("codex"),
                localAccountId = LocalAccountId("local-1"),
                windowId = QuotaWindowId("weekly"),
            ),
        )
    }

    private fun preferences(fileName: String): NotificationPreferencesDataStore =
        NotificationPreferencesDataStore.create(
            file = RuntimeEnvironment.getApplication()
                .preferencesDataStoreFile(fileName),
        )

    private suspend fun NotificationPreferencesDataStore.replaceRawThresholds(caution: Int, warning: Int) {
        dataStore.edit { stored ->
            stored[NotificationPreferencesDataStore.Keys.CAUTION_THRESHOLD] = caution
            stored[NotificationPreferencesDataStore.Keys.WARNING_THRESHOLD] = warning
        }
    }
}
