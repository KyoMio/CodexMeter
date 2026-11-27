package com.kmnexus.codexmeter.app

import com.kmnexus.codexmeter.core.concurrency.VersionedStateGuard
import com.kmnexus.codexmeter.domain.model.LocalAccountId
import com.kmnexus.codexmeter.domain.model.ProviderAccount
import com.kmnexus.codexmeter.domain.model.ProviderAccountId
import com.kmnexus.codexmeter.domain.model.ProviderId
import com.kmnexus.codexmeter.domain.model.QuotaWindowId
import com.kmnexus.codexmeter.domain.quota.CurrentQuotaFreshness
import com.kmnexus.codexmeter.domain.quota.CurrentQuotaState
import com.kmnexus.codexmeter.domain.quota.CurrentQuotaStatus
import com.kmnexus.codexmeter.domain.settings.NotificationPreferenceStore
import com.kmnexus.codexmeter.domain.settings.NotificationPreferences
import com.kmnexus.codexmeter.refresh.CompositeCurrentQuotaStatePublisher
import com.kmnexus.codexmeter.refresh.CurrentQuotaStatePublisher
import com.kmnexus.codexmeter.refresh.PerAccountQuotaEffectPublisher
import com.kmnexus.codexmeter.ui.home.HomeCurrentQuotaStateLoader
import java.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Regression for GH-PR12-R2: enabling an alert on an already-low account, or raising a threshold
 * above the current remaining quota, must evaluate immediately — not at the next refresh.
 */
class ImmediateAlertEvaluationRegressionTest {

    private val now = Instant.parse("2026-05-23T12:00:00Z")

    @Test
    fun `saving notification preferences immediately re-evaluates alerts on the per-account channel`() = runTest {
        val delegate = RecordingNotificationPreferenceStore()
        val currentState = state()
        val publisher = RecordingCurrentStatePublisher()
        val effects = RecordingPerAccountEffects()
        val guard = VersionedStateGuard()
        val store = RepublishingNotificationPreferenceStore(
            delegate = delegate,
            currentQuotaStateLoader = HomeCurrentQuotaStateLoader { currentState },
            currentQuotaStatePublisher = publisher,
            perAccountEffectsPublisher = effects,
            stateGuard = guard,
        )

        store.updateNotificationPreferences(NotificationPreferences(statusNotificationEnabled = false))

        assertEquals(
            "the per-account channel must receive exactly one immediate re-evaluation",
            listOf(currentState),
            effects.effectStates,
        )
        assertEquals(
            "the current-state channel keeps its existing republish for the status notification",
            listOf(currentState),
            publisher.publishedStates,
        )
        assertEquals(1, guard.committedWrites)
    }

    @Test
    fun `account screen alert evaluation wiring reaches production requester and updates widget once`() = runTest {
        val notificationMember = RecordingDualChannelMember()
        val widgetMember = RecordingDualChannelMember()
        val composite = CompositeCurrentQuotaStatePublisher(listOf(widgetMember, notificationMember))
        val notificationOnly = CompositeCurrentQuotaStatePublisher(listOf(notificationMember))
        val state = state()
        val guard = VersionedStateGuard()

        val requester = ImmediateAccountQuotaAlertEvaluationRequester(
            loadAccountState = { _, _, _ -> state },
            perAccountEffectsPublisher = notificationOnly,
            currentQuotaStatePublisher = composite,
            stateGuard = guard,
        )

        requester.requestQuotaAlertEvaluation(
            providerId = ProviderId("codex"),
            localAccountId = LocalAccountId("local-1"),
            windowId = QuotaWindowId("five_hour"),
        )

        assertEquals(listOf(state), notificationMember.effectStates)
        assertEquals(listOf(state), notificationMember.publishedStates)
        assertEquals(emptyList<CurrentQuotaState>(), widgetMember.effectStates)
        assertEquals(listOf(state), widgetMember.publishedStates)
        assertEquals(1, guard.committedWrites)
    }

    private fun state(): CurrentQuotaState =
        CurrentQuotaState(
            status = CurrentQuotaStatus.NoData,
            freshness = CurrentQuotaFreshness.Unknown,
            account = ProviderAccount.createNew(
                localAccountId = LocalAccountId("local-1"),
                providerId = ProviderId("codex"),
                providerAccountId = ProviderAccountId("acct-1"),
                displayName = "Codex Main",
                now = now,
            ),
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

    private class RecordingCurrentStatePublisher : CurrentQuotaStatePublisher {
        val publishedStates = mutableListOf<CurrentQuotaState>()

        override suspend fun publish(state: CurrentQuotaState) {
            publishedStates += state
        }
    }

    private class RecordingPerAccountEffects : PerAccountQuotaEffectPublisher {
        val effectStates = mutableListOf<CurrentQuotaState>()

        override suspend fun publishAccountEffects(state: CurrentQuotaState) {
            effectStates += state
        }
    }

    private class RecordingDualChannelMember : CurrentQuotaStatePublisher, PerAccountQuotaEffectPublisher {
        val publishedStates = mutableListOf<CurrentQuotaState>()
        val effectStates = mutableListOf<CurrentQuotaState>()

        override suspend fun publish(state: CurrentQuotaState) {
            publishedStates += state
        }

        override suspend fun publishAccountEffects(state: CurrentQuotaState) {
            effectStates += state
        }
    }
}
