package com.kmnexus.codexmeter.app

import com.kmnexus.codexmeter.domain.quota.CurrentQuotaState
import com.kmnexus.codexmeter.domain.refresh.RefreshTrigger
import com.kmnexus.codexmeter.refresh.RefreshCoordinator
import com.kmnexus.codexmeter.ui.home.HomeAppOpenRefreshUseCase
import com.kmnexus.codexmeter.ui.home.HomeRefreshUseCase

internal class HomeRefreshCoordinatorUseCase(
    private val currentAccountStore: CurrentQuotaRefreshAccountStore,
    private val refreshCoordinator: RefreshCoordinator,
    private val currentQuotaStateLoader: CurrentQuotaStateRepository,
) : HomeRefreshUseCase, HomeAppOpenRefreshUseCase {
    override suspend fun refreshCurrentState(): CurrentQuotaState =
        refreshThenLoad(RefreshTrigger.Manual)

    override suspend fun refreshForAppOpen(): CurrentQuotaState =
        refreshThenLoad(RefreshTrigger.AppOpen)

    private suspend fun refreshThenLoad(trigger: RefreshTrigger): CurrentQuotaState {
        val account = currentAccountStore.currentAccount()
        if (account != null) {
            refreshCoordinator.refresh(account = account, trigger = trigger)
        }
        return currentQuotaStateLoader.loadCurrentState()
    }
}
