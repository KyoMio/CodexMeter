package com.kmnexus.codexmeter.app

import com.kmnexus.codexmeter.core.concurrency.VersionedStateGuard
import com.kmnexus.codexmeter.domain.settings.NotificationPreferenceStore
import com.kmnexus.codexmeter.domain.settings.NotificationPreferences
import com.kmnexus.codexmeter.refresh.CurrentQuotaStatePublisher
import com.kmnexus.codexmeter.refresh.PerAccountQuotaEffectPublisher
import com.kmnexus.codexmeter.ui.home.HomeCurrentQuotaStateLoader
import kotlinx.coroutines.CancellationException

internal class RepublishingNotificationPreferenceStore(
    private val delegate: NotificationPreferenceStore,
    private val currentQuotaStateLoader: HomeCurrentQuotaStateLoader,
    private val currentQuotaStatePublisher: CurrentQuotaStatePublisher,
    private val perAccountEffectsPublisher: PerAccountQuotaEffectPublisher =
        PerAccountQuotaEffectPublisher { },
    private val stateGuard: VersionedStateGuard,
) : NotificationPreferenceStore {
    override suspend fun notificationPreferences(): NotificationPreferences =
        delegate.notificationPreferences()

    override suspend fun updateNotificationPreferences(preferences: NotificationPreferences) {
        stateGuard.beginEdit()
        try {
            delegate.updateNotificationPreferences(preferences)
        } finally {
            stateGuard.endEdit()
        }
        try {
            stateGuard.commitSerialized {
                val state = currentQuotaStateLoader.loadCurrentState()
                currentQuotaStatePublisher.publish(state)
                // Threshold and toggle changes must re-evaluate alerts NOW (docs/SPEC.md: threshold
                // updates re-evaluate alerts).
                perAccountEffectsPublisher.publishAccountEffects(state)
            }
        } catch (exception: CancellationException) {
            throw exception
        } catch (_: Exception) {
            // Preference writes are durable; presentation refresh can recover on the next state update.
        }
    }
}
