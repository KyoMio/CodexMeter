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
    private val notificationTargetBoundary: com.kmnexus.codexmeter.notification.NotificationTargetBoundary =
        com.kmnexus.codexmeter.notification.NotificationTargetBoundary(),
) : NotificationPreferenceStore {
    override suspend fun notificationPreferences(): NotificationPreferences =
        delegate.notificationPreferences()

    override suspend fun updateNotificationPreferences(preferences: NotificationPreferences) {
        stateGuard.beginEdit()
        try {
            // CM-10 R1: binding changes (persistentNotificationAccount) move the notification
            // target. Serialize against the posting boundary so a post that passed its re-check
            // cannot be interleaved between this write and system notification submission.
            // The critical section is the DataStore write alone — the republish below runs
            // after release and (commitSerialized) respects the commitMutex-first lock order.
            notificationTargetBoundary.serialize {
                delegate.updateNotificationPreferences(preferences)
            }
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
