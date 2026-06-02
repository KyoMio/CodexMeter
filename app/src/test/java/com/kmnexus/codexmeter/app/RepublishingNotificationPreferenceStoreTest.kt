package com.kmnexus.codexmeter.app

import com.kmnexus.codexmeter.domain.quota.CurrentQuotaFreshness
import com.kmnexus.codexmeter.domain.quota.CurrentQuotaState
import com.kmnexus.codexmeter.domain.quota.CurrentQuotaStatus
import com.kmnexus.codexmeter.domain.settings.NotificationPreferenceStore
import com.kmnexus.codexmeter.domain.settings.NotificationPreferences
import com.kmnexus.codexmeter.refresh.CurrentQuotaStatePublisher
import com.kmnexus.codexmeter.ui.home.HomeCurrentQuotaStateLoader
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class RepublishingNotificationPreferenceStoreTest {
    @Test
    fun `updating notification preferences republishes current quota state`() = runTest {
        val delegate = RecordingNotificationPreferenceStore()
        val currentState = state()
        val publisher = RecordingCurrentQuotaStatePublisher()
        val store = RepublishingNotificationPreferenceStore(
            delegate = delegate,
            currentQuotaStateLoader = HomeCurrentQuotaStateLoader { currentState },
            currentQuotaStatePublisher = publisher,
        )
        val preferences = NotificationPreferences(statusNotificationEnabled = false)

        store.updateNotificationPreferences(preferences)

        assertEquals(listOf(preferences), delegate.updates)
        assertEquals(listOf(currentState), publisher.publishedStates)
    }

    private fun state(): CurrentQuotaState =
        CurrentQuotaState(
            status = CurrentQuotaStatus.NoData,
            freshness = CurrentQuotaFreshness.Unknown,
            account = null,
            snapshot = null,
            latestAttempt = null,
            primaryWindow = null,
            secondaryWindows = emptyList(),
            primaryWindowCanAlert = false,
            error = null,
        )

    private class RecordingNotificationPreferenceStore : NotificationPreferenceStore {
        val updates = mutableListOf<NotificationPreferences>()
        private var preferences = NotificationPreferences()

        override suspend fun notificationPreferences(): NotificationPreferences = preferences

        override suspend fun updateNotificationPreferences(preferences: NotificationPreferences) {
            updates += preferences
            this.preferences = preferences
        }
    }

    private class RecordingCurrentQuotaStatePublisher : CurrentQuotaStatePublisher {
        val publishedStates = mutableListOf<CurrentQuotaState>()

        override suspend fun publish(state: CurrentQuotaState) {
            publishedStates += state
        }
    }
}
