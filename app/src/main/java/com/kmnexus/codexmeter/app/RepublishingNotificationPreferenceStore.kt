package com.kmnexus.codexmeter.app

import com.kmnexus.codexmeter.domain.settings.NotificationPreferenceStore
import com.kmnexus.codexmeter.domain.settings.NotificationPreferences
import com.kmnexus.codexmeter.refresh.CurrentQuotaStatePublisher
import com.kmnexus.codexmeter.ui.home.HomeCurrentQuotaStateLoader
import kotlinx.coroutines.CancellationException

internal class RepublishingNotificationPreferenceStore(
    private val delegate: NotificationPreferenceStore,
    private val currentQuotaStateLoader: HomeCurrentQuotaStateLoader,
    private val currentQuotaStatePublisher: CurrentQuotaStatePublisher,
) : NotificationPreferenceStore {
    override suspend fun notificationPreferences(): NotificationPreferences =
        delegate.notificationPreferences()

    override suspend fun updateNotificationPreferences(preferences: NotificationPreferences) {
        delegate.updateNotificationPreferences(preferences)
        try {
            currentQuotaStatePublisher.publish(currentQuotaStateLoader.loadCurrentState())
        } catch (exception: CancellationException) {
            throw exception
        } catch (_: Exception) {
            // Preference writes are durable; presentation refresh can recover on the next state update.
        }
    }
}
