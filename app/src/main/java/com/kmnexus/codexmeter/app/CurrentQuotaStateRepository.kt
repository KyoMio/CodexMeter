package com.kmnexus.codexmeter.app

import com.kmnexus.codexmeter.data.currency.ExchangeRateReader
import com.kmnexus.codexmeter.data.local.dao.ProviderAccountDao
import com.kmnexus.codexmeter.data.local.dao.QuotaSnapshotDao
import com.kmnexus.codexmeter.data.local.dao.RefreshAttemptDao
import com.kmnexus.codexmeter.data.preferences.CurrentAccountReader
import com.kmnexus.codexmeter.data.preferences.CurrentAccountSelection
import com.kmnexus.codexmeter.data.repository.toDomain
import com.kmnexus.codexmeter.domain.currency.CurrencyPreferenceReader
import com.kmnexus.codexmeter.domain.currency.CurrencyPreferences
import com.kmnexus.codexmeter.domain.currency.ExchangeRates
import com.kmnexus.codexmeter.domain.currency.withConvertedBalance
import com.kmnexus.codexmeter.domain.model.LocalAccountId
import com.kmnexus.codexmeter.domain.model.ProviderAccount
import com.kmnexus.codexmeter.domain.model.ProviderId
import com.kmnexus.codexmeter.domain.model.QuotaWindowId
import com.kmnexus.codexmeter.domain.quota.CurrentQuotaState
import com.kmnexus.codexmeter.domain.quota.CurrentQuotaStateFactory
import com.kmnexus.codexmeter.domain.quota.QuotaWindow
import com.kmnexus.codexmeter.domain.settings.DefaultPrimaryQuotaWindowPreferenceReader
import com.kmnexus.codexmeter.domain.settings.NotificationPreferenceReader
import com.kmnexus.codexmeter.domain.settings.NotificationPreferences
import com.kmnexus.codexmeter.domain.settings.PrimaryQuotaWindowPreferenceReader
import com.kmnexus.codexmeter.notification.StatusNotificationStateLoader
import com.kmnexus.codexmeter.ui.home.HomeCurrentQuotaStateLoader
import java.time.Clock

internal class CurrentQuotaStateRepository(
    private val currentAccountReader: CurrentAccountReader,
    private val providerAccountDao: ProviderAccountDao,
    private val quotaSnapshotDao: QuotaSnapshotDao,
    private val refreshAttemptDao: RefreshAttemptDao,
    private val currentQuotaStateFactory: CurrentQuotaStateFactory = CurrentQuotaStateFactory(),
    private val primaryQuotaWindowPreferenceReader: PrimaryQuotaWindowPreferenceReader =
        DefaultPrimaryQuotaWindowPreferenceReader,
    private val notificationPreferenceReader: NotificationPreferenceReader =
        DefaultStatusNotificationPreferenceReader,
    private val clock: Clock,
    private val currencyPreferenceReader: CurrencyPreferenceReader = DefaultCurrencyPreferenceReader,
    private val exchangeRateReader: ExchangeRateReader = DefaultExchangeRateReader,
) : HomeCurrentQuotaStateLoader, StatusNotificationStateLoader {
    override suspend fun loadCurrentState(): CurrentQuotaState {
        val selection = currentAccountReader.currentAccountSelection()
            ?: return unauthenticatedState()
        val account = loadAccount(selection)
            ?: return unauthenticatedState()
        return loadState(
            account = account,
            primaryWindowId = primaryQuotaWindowPreferenceReader.primaryQuotaWindowId(),
        )
    }

    override suspend fun loadStatusNotificationState(refreshedState: CurrentQuotaState): CurrentQuotaState {
        val preferences = notificationPreferenceReader.notificationPreferences()
        val configuredAccount = preferences.persistentNotificationAccount
            ?.let { selection ->
                loadAccount(
                    CurrentAccountSelection(
                        providerId = selection.providerId,
                        localAccountId = selection.localAccountId,
                    ),
                )
            }
        val fallbackSelection = currentAccountReader.currentAccountSelection()
        val account = configuredAccount
            ?: fallbackSelection?.let { loadAccount(it) }
            ?: return unauthenticatedState()

        val state = loadState(
            account = account,
            primaryWindowId = preferences.persistentNotificationWindowId,
        )
        val currency = currencyPreferenceReader.currencyPreferences()
        val rates = exchangeRateReader.currentRates()
        val resolvedPrimary = resolveNotificationPrimaryWindow(state)
        return state.copy(
            primaryWindow = resolvedPrimary?.withConvertedBalance(currency.targetCurrency, rates),
        )
    }

    suspend fun loadAccountState(
        providerId: ProviderId,
        localAccountId: LocalAccountId,
        primaryWindowId: QuotaWindowId,
    ): CurrentQuotaState {
        val account = loadAccount(
            CurrentAccountSelection(
                providerId = providerId,
                localAccountId = localAccountId,
            ),
        ) ?: return unauthenticatedState()
        return loadState(
            account = account,
            primaryWindowId = primaryWindowId,
        )
    }

    private suspend fun loadAccount(selection: CurrentAccountSelection): ProviderAccount? =
        providerAccountDao.getById(selection.localAccountId.value)
            ?.toDomain()
            ?.takeIf { it.providerId == selection.providerId }

    private suspend fun loadState(
        account: ProviderAccount,
        primaryWindowId: QuotaWindowId,
    ): CurrentQuotaState {
        val latestSnapshot = quotaSnapshotDao.getLatestForAccount(
            providerId = account.providerId.value,
            localAccountId = account.localAccountId.value,
        )?.toDomain()
        val latestAttempt = refreshAttemptDao.getLatestForAccount(
            providerId = account.providerId.value,
            localAccountId = account.localAccountId.value,
        )?.toDomain()

        return currentQuotaStateFactory.create(
            account = account,
            latestSnapshot = latestSnapshot,
            latestAttempt = latestAttempt,
            now = clock.instant(),
            primaryWindowId = primaryWindowId,
        )
    }

    private fun unauthenticatedState(): CurrentQuotaState =
        currentQuotaStateFactory.create(
            account = null,
            latestSnapshot = null,
            latestAttempt = null,
            now = clock.instant(),
        )
}

/**
 * Resolves the primary window for the persistent status notification.
 *
 * When the stored persistentNotificationWindowId belongs to a previous account and is absent from
 * the current account's snapshot, [CurrentQuotaState.primaryWindow] is null. Rather than showing
 * no quota in the notification, fall back to the account's own primary-candidate window, or else
 * the first window in the snapshot.
 */
internal fun resolveNotificationPrimaryWindow(state: CurrentQuotaState): QuotaWindow? =
    state.primaryWindow
        ?: state.snapshot?.windows?.firstOrNull { it.isPrimaryCandidate }
        ?: state.snapshot?.windows?.firstOrNull()

private object DefaultStatusNotificationPreferenceReader : NotificationPreferenceReader {
    override suspend fun notificationPreferences(): NotificationPreferences = NotificationPreferences()
}

private object DefaultCurrencyPreferenceReader : CurrencyPreferenceReader {
    override suspend fun currencyPreferences() = CurrencyPreferences()
}

private object DefaultExchangeRateReader : ExchangeRateReader {
    override suspend fun currentRates(): ExchangeRates? = null
}
