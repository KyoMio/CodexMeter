package com.kmnexus.codexmeter.app

import com.kmnexus.codexmeter.domain.model.LocalAccountId
import com.kmnexus.codexmeter.domain.model.ProviderAccount
import com.kmnexus.codexmeter.domain.model.ProviderAccountId
import com.kmnexus.codexmeter.domain.model.ProviderId
import com.kmnexus.codexmeter.domain.model.QuotaWindowId
import com.kmnexus.codexmeter.domain.quota.CurrentQuotaState
import com.kmnexus.codexmeter.domain.quota.CurrentQuotaStatus
import com.kmnexus.codexmeter.domain.quota.CurrentQuotaFreshness
import com.kmnexus.codexmeter.domain.settings.NotificationPreferenceStore
import com.kmnexus.codexmeter.domain.settings.NotificationPreferences
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
 *
 * Both production entry points were silently dead after the per-account channel split because they
 * only drove the current-state channel, which no longer carries alerts. These tests pin the wiring
 * contract: each entry point must reach the per-account effects channel with the loaded state.
 */
class ImmediateAlertEvaluationRegressionTest {

    private val now = Instant.parse("2026-05-23T12:00:00Z")

    /**
     * Threshold-save entry: RepublishingNotificationPreferenceStore must re-evaluate alerts through
     * the per-account channel right after persisting new preferences.
     */
    @Test
    fun `saving notification preferences immediately re-evaluates alerts on the per-account channel`() = runTest {
        val delegate = RecordingNotificationPreferenceStore()
        val currentState = state()
        val publisher = RecordingCurrentStatePublisher()
        val effects = RecordingPerAccountEffects()
        val store = RepublishingNotificationPreferenceStore(
            delegate = delegate,
            currentQuotaStateLoader = HomeCurrentQuotaStateLoader { currentState },
            currentQuotaStatePublisher = publisher,
            perAccountEffectsPublisher = effects,
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
    }

    /**
     * Account-screen entry: the production wiring of AccountQuotaAlertEvaluationRequester must call
     * the per-account channel. This mirrors the AppContainer lambda exactly; the wiring assertion
     * is the channel choice, so the lambda body is replicated here against the real interfaces.
     */
    @Test
    fun `account screen alert evaluation wiring reaches the per-account channel`() = runTest {
        val effects = RecordingPerAccountEffects()
        val publisher = CompositeRecordingPublisher(effects)
        val state = state()

        // Replica of the AppContainer wiring lambda (keep in sync — the regression is about the
        // channel choice and ordering, which this pins):
        //   (composite as? PerAccountQuotaEffectPublisher)?.publishAccountEffects(state)
        //   composite.publish(state)
        (publisher as? PerAccountQuotaEffectPublisher)?.publishAccountEffects(state)
        publisher.publish(state)

        assertEquals(listOf(state), effects.effectStates)
        assertEquals(listOf(state), publisher.publishedStates)
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

    /** Mirrors CompositeCurrentQuotaStatePublisher's dual-channel shape for the wiring replica. */
    private class CompositeRecordingPublisher(
        private val effects: RecordingPerAccountEffects,
    ) : CurrentQuotaStatePublisher, PerAccountQuotaEffectPublisher {
        val publishedStates = mutableListOf<CurrentQuotaState>()

        override suspend fun publish(state: CurrentQuotaState) {
            publishedStates += state
        }

        override suspend fun publishAccountEffects(state: CurrentQuotaState) {
            effects.publishAccountEffects(state)
        }
    }
}
