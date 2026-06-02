package com.kmnexus.codexmeter.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.kmnexus.codexmeter.R
import com.kmnexus.codexmeter.domain.model.LocalAccountId
import com.kmnexus.codexmeter.providers.ProviderRegistry
import com.kmnexus.codexmeter.ui.quota.quotaWindowLabelRes
import com.kmnexus.codexmeter.domain.quota.CurrentQuotaState
import com.kmnexus.codexmeter.domain.quota.CurrentQuotaStatus
import com.kmnexus.codexmeter.domain.quota.Credits
import com.kmnexus.codexmeter.data.currency.ExchangeRateReader
import com.kmnexus.codexmeter.domain.currency.CurrencyPreferenceReader
import com.kmnexus.codexmeter.domain.currency.CurrencyPreferences
import com.kmnexus.codexmeter.domain.currency.ExchangeRates
import com.kmnexus.codexmeter.domain.currency.withConvertedBalance
import com.kmnexus.codexmeter.domain.quota.QuotaWindow
import com.kmnexus.codexmeter.domain.quota.QuotaWindowAvailability
import com.kmnexus.codexmeter.domain.quota.QuotaWindowDisplayKind
import com.kmnexus.codexmeter.domain.settings.NotificationPreferenceReader
import com.kmnexus.codexmeter.domain.settings.NotificationPreferences
import com.kmnexus.codexmeter.ui.providerPlanDisplayName
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

fun interface HomeCurrentQuotaStateLoader {
    suspend fun loadCurrentState(): CurrentQuotaState
}

fun interface HomeRefreshUseCase {
    suspend fun refreshCurrentState(): CurrentQuotaState
}

fun interface HomeAppOpenRefreshUseCase {
    suspend fun refreshForAppOpen(): CurrentQuotaState
}

data class HomeTrendQuery(
    val windowId: String,
    val displayKind: QuotaWindowDisplayKind,
    val useModelBucketSum: Boolean,
)

fun interface HomeTrendHistoryLoader {
    suspend fun loadTrend(accountId: LocalAccountId?, query: HomeTrendQuery): List<HomeTrendPointUi>
}

private object NoopHomeCurrentQuotaStateLoader : HomeCurrentQuotaStateLoader {
    override suspend fun loadCurrentState(): CurrentQuotaState =
        com.kmnexus.codexmeter.domain.quota.CurrentQuotaStateFactory().create(
            account = null,
            latestSnapshot = null,
            latestAttempt = null,
            now = Instant.EPOCH,
        )
}

private object NoopHomeRefreshUseCase : HomeRefreshUseCase {
    override suspend fun refreshCurrentState(): CurrentQuotaState =
        NoopHomeCurrentQuotaStateLoader.loadCurrentState()
}

private object NoopHomeAppOpenRefreshUseCase : HomeAppOpenRefreshUseCase {
    override suspend fun refreshForAppOpen(): CurrentQuotaState =
        NoopHomeCurrentQuotaStateLoader.loadCurrentState()
}

private object NoopHomeTrendHistoryLoader : HomeTrendHistoryLoader {
    override suspend fun loadTrend(accountId: LocalAccountId?, query: HomeTrendQuery): List<HomeTrendPointUi> = emptyList()
}

private object DefaultHomeNotificationPreferenceReader : NotificationPreferenceReader {
    override suspend fun notificationPreferences(): NotificationPreferences = NotificationPreferences()
}

private object DefaultHomeCurrencyPreferenceReader : CurrencyPreferenceReader {
    override suspend fun currencyPreferences(): CurrencyPreferences = CurrencyPreferences()
}

private object DefaultHomeExchangeRateReader : ExchangeRateReader {
    override suspend fun currentRates(): ExchangeRates? = null
}

class HomeViewModel(
    private val currentQuotaStateLoader: HomeCurrentQuotaStateLoader = NoopHomeCurrentQuotaStateLoader,
    private val appOpenRefreshUseCase: HomeAppOpenRefreshUseCase = NoopHomeAppOpenRefreshUseCase,
    private val refreshUseCase: HomeRefreshUseCase = NoopHomeRefreshUseCase,
    private val trendHistoryLoader: HomeTrendHistoryLoader = NoopHomeTrendHistoryLoader,
    private val notificationPreferenceReader: NotificationPreferenceReader = DefaultHomeNotificationPreferenceReader,
    private val currencyPreferenceReader: CurrencyPreferenceReader = DefaultHomeCurrencyPreferenceReader,
    private val exchangeRateReader: ExchangeRateReader = DefaultHomeExchangeRateReader,
) : ViewModel() {
    private val _uiState = MutableStateFlow(HomeUiState.loading())
    private var currentTrendAccountId: LocalAccountId? = null
    private var currentTrendQuery: HomeTrendQuery? = null
    private var notificationPreferences = NotificationPreferences()
    private var currencyPreferences: CurrencyPreferences = CurrencyPreferences()
    private var exchangeRates: ExchangeRates? = null
    private val trendPointsByAccount = mutableMapOf<LocalAccountId?, List<HomeTrendPointUi>>()
    private var appOpenLoadJob: Job? = null
    private var trendLoadJob: Job? = null
    private var manualRefreshSuccessCount = 0

    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    fun updateCurrentQuotaState(state: CurrentQuotaState, isRefreshing: Boolean = false) {
        currentTrendAccountId = state.account?.localAccountId
        currentTrendQuery = state.trendWindow()?.toTrendQuery()
        clearTrendCacheFor(currentTrendAccountId)
        _uiState.value = mapToUiState(state = state, isRefreshing = isRefreshing)
        if (state.account != null && currentTrendQuery != null) {
            loadTrend()
        }
    }

    fun loadCurrentState() {
        if (appOpenLoadJob?.isActive == true) {
            return
        }
        _uiState.value = _uiState.value.copy(isRefreshing = true)
        appOpenLoadJob = viewModelScope.launch {
            loadNotificationPreferences()
            val persistedState = loadPersistedCurrentStateOrNull()
            if (persistedState != null) {
                updateCurrentQuotaState(state = persistedState, isRefreshing = true)
            }
            try {
                updateCurrentQuotaState(appOpenRefreshUseCase.refreshForAppOpen())
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: Exception) {
                val fallbackState = loadPersistedCurrentStateOrNull() ?: persistedState
                if (fallbackState != null) {
                    updateCurrentQuotaState(fallbackState)
                } else {
                    _uiState.value = _uiState.value.copy(isRefreshing = false)
                }
            }
        }
    }

    fun refreshNow() {
        _uiState.value = _uiState.value.copy(isRefreshing = true)
        viewModelScope.launch {
            loadNotificationPreferences()
            val refreshedState = refreshUseCase.refreshCurrentState()
            if (refreshedState.status.isRefreshSweepSuccess()) {
                manualRefreshSuccessCount += 1
            }
            updateCurrentQuotaState(refreshedState)
        }
    }

    private suspend fun loadNotificationPreferences() {
        runCatching {
            notificationPreferenceReader.notificationPreferences()
        }.onSuccess { preferences ->
            notificationPreferences = preferences
        }
        currencyPreferences = currencyPreferenceReader.currencyPreferences()
        exchangeRates = exchangeRateReader.currentRates()
    }

    /** Test-only: populate preference fields via injected readers without triggering a full state load. */
    internal suspend fun loadPreferencesForTest() {
        notificationPreferences = notificationPreferenceReader.notificationPreferences()
        currencyPreferences = currencyPreferenceReader.currencyPreferences()
        exchangeRates = exchangeRateReader.currentRates()
    }

    private suspend fun loadPersistedCurrentStateOrNull(): CurrentQuotaState? =
        try {
            currentQuotaStateLoader.loadCurrentState()
        } catch (exception: CancellationException) {
            throw exception
        } catch (_: Exception) {
            null
        }

    fun mapToUiState(
        state: CurrentQuotaState,
        isRefreshing: Boolean = false,
    ): HomeUiState {
        if (state.status == CurrentQuotaStatus.Unauthenticated) {
            return HomeUiState.unauthenticated(isRefreshing = isRefreshing)
        }

        val contentStatus = state.status.toHomeStatus()
        val statusTitleResId = state.statusTitleResId()
        val statusDescriptionResId = state.statusDescriptionResId()
        val allWindows = state.allWindows()
        val quotaCards = allWindows
            .map { it.withConvertedBalance(currencyPreferences.targetCurrency, exchangeRates) }
            .map { window -> window.toQuotaCard() }
        val effectiveRefreshing = isRefreshing || state.status == CurrentQuotaStatus.Loading

        return HomeUiState(
            contentStatus = contentStatus,
            statusTitleResId = statusTitleResId,
            statusDescriptionResId = statusDescriptionResId,
            errorMessageResId = state.error?.safeMessageKey?.toErrorMessageResId(),
            account = state.account?.let {
                HomeAccountUi(
                    displayName = it.displayName,
                    avatarInitial = it.avatarInitial,
                    avatarColorKey = it.avatarColorKey,
                    planType = providerPlanDisplayName(it.providerId, state.snapshot?.planType),
                    credits = state.snapshot?.credits.toHomeCredits(),
                    providerIconResId = ProviderRegistry.iconFor(it.providerId),
                )
            },
            quotaCards = quotaCards,
            trend = HomeTrendUi(
                metricLabelResId = state.trendWindow()?.displayKind.metricLabelResId(),
                points = trendPointsByAccount[state.account?.localAccountId].orEmpty(),
            ),
            loading = if (
                state.status == CurrentQuotaStatus.Loading &&
                quotaCards.isEmpty()
            ) {
                HomeLoadingUi(
                    titleResId = R.string.home_loading_card_title,
                    descriptionResId = R.string.home_loading_card_description,
                )
            } else {
                null
            },
            refresh = HomeRefreshUi(
                titleResId = if (effectiveRefreshing) R.string.home_state_loading_title else statusTitleResId,
                descriptionResId = if (effectiveRefreshing) {
                    R.string.home_state_loading_description
                } else {
                    statusDescriptionResId
                },
                buttonTextResId = if (effectiveRefreshing) R.string.home_refreshing else R.string.home_refresh,
                lastSuccessfulRefreshAt = state.snapshot?.fetchedAt ?: state.account?.lastSuccessfulRefreshAt,
                lastAttemptFinishedAt = state.latestAttempt?.finishedAt,
            ),
            primaryAction = state.primaryAction(),
            secondaryAction = state.secondaryAction(),
            isRefreshing = effectiveRefreshing,
            manualRefreshSuccessCount = manualRefreshSuccessCount,
        )
    }

    private fun CurrentQuotaStatus.toHomeStatus(): HomeContentStatus =
        when (this) {
            CurrentQuotaStatus.Unauthenticated -> HomeContentStatus.Unauthenticated
            CurrentQuotaStatus.Loading -> HomeContentStatus.Loading
            CurrentQuotaStatus.Fresh -> HomeContentStatus.Fresh
            CurrentQuotaStatus.PossiblyStale -> HomeContentStatus.PossiblyStale
            CurrentQuotaStatus.Expired -> HomeContentStatus.Expired
            CurrentQuotaStatus.AuthRequired -> HomeContentStatus.AuthRequired
            CurrentQuotaStatus.ErrorWithLastKnownGood -> HomeContentStatus.ErrorWithLastKnownGood
            CurrentQuotaStatus.NoData -> HomeContentStatus.NoData
        }

    private fun CurrentQuotaStatus.isRefreshSweepSuccess(): Boolean =
        this == CurrentQuotaStatus.Fresh || this == CurrentQuotaStatus.PossiblyStale

    private fun CurrentQuotaState.statusTitleResId(): Int =
        when (status) {
            CurrentQuotaStatus.Unauthenticated -> R.string.home_state_unauthenticated_title
            CurrentQuotaStatus.Loading -> R.string.home_state_loading_title
            CurrentQuotaStatus.Fresh -> R.string.home_state_fresh_title
            CurrentQuotaStatus.PossiblyStale -> R.string.home_state_possibly_stale_title
            CurrentQuotaStatus.Expired -> R.string.home_state_expired_title
            CurrentQuotaStatus.AuthRequired -> R.string.home_state_auth_required_title
            CurrentQuotaStatus.ErrorWithLastKnownGood -> R.string.home_state_error_lkg_title
            CurrentQuotaStatus.NoData -> R.string.home_state_no_data_title
        }

    private fun CurrentQuotaState.statusDescriptionResId(): Int =
        when (status) {
            CurrentQuotaStatus.Unauthenticated -> R.string.home_state_unauthenticated_description
            CurrentQuotaStatus.Loading -> R.string.home_state_loading_description
            CurrentQuotaStatus.Fresh -> R.string.home_state_fresh_description
            CurrentQuotaStatus.PossiblyStale -> R.string.home_state_possibly_stale_description
            CurrentQuotaStatus.Expired -> R.string.home_state_expired_description
            CurrentQuotaStatus.AuthRequired -> R.string.home_state_auth_required_description
            CurrentQuotaStatus.ErrorWithLastKnownGood -> R.string.home_state_error_lkg_description
            CurrentQuotaStatus.NoData -> R.string.home_state_no_data_description
        }

    private fun CurrentQuotaState.primaryAction(): HomeActionUi? =
        when (status) {
            CurrentQuotaStatus.Unauthenticated,
            CurrentQuotaStatus.AuthRequired -> HomeActionUi(
                kind = HomeActionKind.LoginToCodex,
                labelResId = R.string.home_login_to_codex,
            )
            else -> null
        }

    private fun CurrentQuotaState.secondaryAction(): HomeActionUi? = null

    private fun CurrentQuotaState.allWindows(): List<QuotaWindow> =
        (listOfNotNull(primaryWindow) + secondaryWindows).distinctBy { it.windowId.value }

    private fun loadTrend() {
        val accountId = currentTrendAccountId
        val query = currentTrendQuery ?: return
        trendLoadJob?.cancel()
        trendLoadJob = viewModelScope.launch {
            val points = try {
                trendHistoryLoader.loadTrend(accountId = accountId, query = query)
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: Exception) {
                emptyList()
            }
            trendPointsByAccount[accountId] = points
            if (currentTrendAccountId == accountId) {
                _uiState.value = _uiState.value.copy(
                    trend = _uiState.value.trend.copy(points = points),
                )
            }
        }
    }

    private fun clearTrendCacheFor(accountId: LocalAccountId?) {
        trendPointsByAccount.remove(accountId)
    }

    // The trend follows the window the user sees as primary. CurrentQuotaState.primaryWindow is only
    // non-null when the snapshot contains the globally-configured primary window id (default
    // "five_hour"), which never matches non-Codex providers (DeepSeek "balance", Antigravity families,
    // etc.). Fall back to the snapshot's own primary-candidate window so every provider charts usage.
    private fun CurrentQuotaState.trendWindow(): QuotaWindow? =
        primaryWindow
            ?: secondaryWindows.firstOrNull { it.isPrimaryCandidate }
            ?: secondaryWindows.firstOrNull()

    private fun QuotaWindow.toTrendQuery(): HomeTrendQuery =
        HomeTrendQuery(
            windowId = windowId.value,
            displayKind = displayKind,
            useModelBucketSum = usesModelBucketSum,
        )

    private fun QuotaWindowDisplayKind?.metricLabelResId(): Int =
        when (this) {
            QuotaWindowDisplayKind.Balance -> R.string.home_trend_metric_spend
            QuotaWindowDisplayKind.UsageCount -> R.string.home_trend_metric_calls
            else -> R.string.home_trend_metric_usage
        }

    private fun QuotaWindow.toQuotaCard(): HomeQuotaCardUi {
        val quotaStatus = toQuotaStatus()
        return HomeQuotaCardUi(
            windowId = windowId.value,
            titleResId = quotaWindowLabelRes(windowId.value),
            displayKind = displayKind,
            usedPercent = usedPercent,
            balanceAmount = balanceAmount,
            balanceCurrency = balanceCurrency,
            usedCount = usedCount,
            limitCount = limitCount,
            subLabel = subLabel,
            isPrimary = isPrimaryCandidate,
            resetAt = resetAt,
            status = quotaStatus,
            tone = quotaStatus.toTone(),
            statusLabelResId = quotaStatus.labelResId(),
            originalBalanceAmount = originalBalanceAmount,
            originalBalanceCurrency = originalBalanceCurrency,
            grantedBalance = grantedBalance,
            toppedUpBalance = toppedUpBalance,
        )
    }

    private fun QuotaWindow.toQuotaStatus(): HomeQuotaStatus {
        if (availability != QuotaWindowAvailability.Available) return HomeQuotaStatus.Unavailable
        if (displayKind == QuotaWindowDisplayKind.Balance) {
            val amount = balanceAmount?.toDoubleOrNull() ?: return HomeQuotaStatus.Unavailable
            return when {
                amount <= 0.0 -> HomeQuotaStatus.Exhausted
                amount <= notificationPreferences.balanceWarningThreshold -> HomeQuotaStatus.Warning
                amount <= notificationPreferences.balanceCautionThreshold -> HomeQuotaStatus.Caution
                else -> HomeQuotaStatus.Normal
            }
        }
        val percent = displayPercent ?: return HomeQuotaStatus.Unavailable
        return when {
            percent <= notificationPreferences.limitThreshold -> HomeQuotaStatus.Exhausted
            percent <= notificationPreferences.warningThreshold -> HomeQuotaStatus.Warning
            percent <= notificationPreferences.cautionThreshold -> HomeQuotaStatus.Caution
            else -> HomeQuotaStatus.Normal
        }
    }

    internal fun testQuotaStatusFor(window: QuotaWindow): HomeQuotaStatus =
        window.withConvertedBalance(currencyPreferences.targetCurrency, exchangeRates).toQuotaStatus()

    private fun Credits?.toHomeCredits(): HomeCreditsUi =
        when {
            this == null || !hasCredits -> HomeCreditsUi.Unavailable
            unlimited -> HomeCreditsUi.Unlimited
            balance != null -> HomeCreditsUi.Balance(balance)
            else -> HomeCreditsUi.Unavailable
        }

    private fun HomeQuotaStatus.toTone(): HomeStatusTone =
        when (this) {
            HomeQuotaStatus.Normal -> HomeStatusTone.Success
            HomeQuotaStatus.Caution -> HomeStatusTone.Warning
            HomeQuotaStatus.Warning -> HomeStatusTone.Danger
            HomeQuotaStatus.Exhausted -> HomeStatusTone.Danger
            HomeQuotaStatus.Unavailable -> HomeStatusTone.Neutral
        }

    private fun HomeQuotaStatus.labelResId(): Int =
        when (this) {
            HomeQuotaStatus.Normal -> R.string.home_quota_status_normal
            HomeQuotaStatus.Caution -> R.string.home_quota_status_caution
            HomeQuotaStatus.Warning -> R.string.home_quota_status_warning
            HomeQuotaStatus.Exhausted -> R.string.home_quota_status_exhausted
            HomeQuotaStatus.Unavailable -> R.string.home_quota_status_unavailable
        }

    private fun String.toErrorMessageResId(): Int =
        when (this) {
            "error_auth_required" -> R.string.error_auth_required
            "error_network" -> R.string.error_network
            else -> R.string.error_unknown
        }

    companion object {
        fun factory(
            currentQuotaStateLoader: HomeCurrentQuotaStateLoader,
            appOpenRefreshUseCase: HomeAppOpenRefreshUseCase,
            refreshUseCase: HomeRefreshUseCase,
            trendHistoryLoader: HomeTrendHistoryLoader = NoopHomeTrendHistoryLoader,
            notificationPreferenceReader: NotificationPreferenceReader = DefaultHomeNotificationPreferenceReader,
            currencyPreferenceReader: CurrencyPreferenceReader = DefaultHomeCurrencyPreferenceReader,
            exchangeRateReader: ExchangeRateReader = DefaultHomeExchangeRateReader,
        ): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    HomeViewModel(
                        currentQuotaStateLoader = currentQuotaStateLoader,
                        appOpenRefreshUseCase = appOpenRefreshUseCase,
                        refreshUseCase = refreshUseCase,
                        trendHistoryLoader = trendHistoryLoader,
                        notificationPreferenceReader = notificationPreferenceReader,
                        currencyPreferenceReader = currencyPreferenceReader,
                        exchangeRateReader = exchangeRateReader,
                    ) as T
            }

    }
}
