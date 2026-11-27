package com.kmnexus.codexmeter.app

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
) : NotificationPreferenceStore {
    override suspend fun notificationPreferences(): NotificationPreferences =
        delegate.notificationPreferences()

    override suspend fun updateNotificationPreferences(preferences: NotificationPreferences) {
        delegate.updateNotificationPreferences(preferences)
        try {
            val state = currentQuotaStateLoader.loadCurrentState()
            currentQuotaStatePublisher.publish(state)
            // Threshold and toggle changes must re-evaluate alerts NOW (docs/SPEC.md: threshold
            // updates re-evaluate alerts) — waiting for the next refresh defeats the purpose of
            // raising a threshold on an already-low account. This is a re-evaluation of durable
            // state, not a stale write-back, so the per-account channel is correct and needs no
            // epoch guard.
            perAccountEffectsPublisher.publishAccountEffects(state)
        } catch (exception: CancellationException) {
            throw exception
        } catch (_: Exception) {
            // Preference writes are durable; presentation refresh can recover on the next state update.
        }
    }
}
