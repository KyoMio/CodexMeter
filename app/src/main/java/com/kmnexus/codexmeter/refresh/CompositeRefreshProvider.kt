package com.kmnexus.codexmeter.refresh

import com.kmnexus.codexmeter.domain.model.ProviderAccount
import com.kmnexus.codexmeter.domain.model.ProviderId
import com.kmnexus.codexmeter.domain.refresh.QuotaError
import com.kmnexus.codexmeter.domain.refresh.RefreshTrigger

class CompositeRefreshProvider(
    private val providers: Map<ProviderId, RefreshProvider>,
) : RefreshProvider {
    override suspend fun refresh(
        account: ProviderAccount,
        trigger: RefreshTrigger,
    ): ProviderRefreshResult =
        providers[account.providerId]
            ?.refresh(account, trigger)
            ?: ProviderRefreshResult.Failure(
                QuotaError.AuthRequired(
                    httpStatus = null,
                    diagnosticsDigest = "composite_refresh_no_provider_for_${account.providerId.value}",
                ),
            )
}
