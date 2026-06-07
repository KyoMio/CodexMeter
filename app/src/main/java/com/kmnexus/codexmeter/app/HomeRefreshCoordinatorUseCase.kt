package com.kmnexus.codexmeter.app

import com.kmnexus.codexmeter.domain.quota.CurrentQuotaState
import com.kmnexus.codexmeter.domain.refresh.RefreshTrigger
import com.kmnexus.codexmeter.refresh.RefreshCoordinator
import com.kmnexus.codexmeter.ui.home.HomeRefreshUseCase

internal class HomeRefreshCoordinatorUseCase(
    private val currentAccountStore: CurrentQuotaRefreshAccountStore,
    private val refreshCoordinator: RefreshCoordinator,
    private val currentQuotaStateLoader: CurrentQuotaStateRepository,
) : HomeRefreshUseCase {
    // Manual pull-to-refresh only. Opening Home no longer triggers a network refresh (it just reloads
    // the persisted snapshot), so there is no separate app-open path here.
    override suspend fun refreshCurrentState(): CurrentQuotaState {
        val account = currentAccountStore.currentAccount()
        if (account != null) {
            refreshCoordinator.refresh(account = account, trigger = RefreshTrigger.Manual)
        }
        return currentQuotaStateLoader.loadCurrentState()
    }
}
