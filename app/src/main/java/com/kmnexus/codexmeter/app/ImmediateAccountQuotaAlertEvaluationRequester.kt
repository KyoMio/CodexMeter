package com.kmnexus.codexmeter.app

import com.kmnexus.codexmeter.core.concurrency.VersionedStateGuard
import com.kmnexus.codexmeter.domain.model.LocalAccountId
import com.kmnexus.codexmeter.domain.model.ProviderId
import com.kmnexus.codexmeter.domain.model.QuotaWindowId
import com.kmnexus.codexmeter.domain.quota.CurrentQuotaState
import com.kmnexus.codexmeter.refresh.CurrentQuotaStatePublisher
import com.kmnexus.codexmeter.refresh.PerAccountQuotaEffectPublisher
import com.kmnexus.codexmeter.ui.account.AccountQuotaAlertEvaluationRequester

/**
 * Production [AccountQuotaAlertEvaluationRequester] wiring: immediately evaluates alerts for the
 * toggled account on [perAccountEffectsPublisher], then updates status presentation under
 * [VersionedStateGuard.commitSerialized].
 */
internal class ImmediateAccountQuotaAlertEvaluationRequester(
    private val loadAccountState: suspend (
        providerId: ProviderId,
        localAccountId: LocalAccountId,
        primaryWindowId: QuotaWindowId,
    ) -> CurrentQuotaState,
    private val perAccountEffectsPublisher: PerAccountQuotaEffectPublisher,
    private val currentQuotaStatePublisher: CurrentQuotaStatePublisher,
    private val stateGuard: VersionedStateGuard,
) : AccountQuotaAlertEvaluationRequester {
    override suspend fun requestQuotaAlertEvaluation(
        providerId: ProviderId,
        localAccountId: LocalAccountId,
        windowId: QuotaWindowId,
    ) {
        val state = loadAccountState(providerId, localAccountId, windowId)
        perAccountEffectsPublisher.publishAccountEffects(state)
        stateGuard.commitSerialized {
            currentQuotaStatePublisher.publish(state)
        }
    }
}
