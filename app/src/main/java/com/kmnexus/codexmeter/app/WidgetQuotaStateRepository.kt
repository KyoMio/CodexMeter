package com.kmnexus.codexmeter.app

import com.kmnexus.codexmeter.data.currency.ExchangeRateReader
import com.kmnexus.codexmeter.data.local.dao.ProviderAccountDao
import com.kmnexus.codexmeter.data.local.dao.QuotaSnapshotDao
import com.kmnexus.codexmeter.data.local.dao.RefreshAttemptDao
import com.kmnexus.codexmeter.data.repository.toDomain
import com.kmnexus.codexmeter.domain.currency.CurrencyPreferenceReader
import com.kmnexus.codexmeter.domain.currency.CurrencyPreferences
import com.kmnexus.codexmeter.domain.currency.ExchangeRates
import com.kmnexus.codexmeter.domain.currency.withConvertedBalance
import com.kmnexus.codexmeter.domain.model.AccountStatus
import com.kmnexus.codexmeter.domain.model.QuotaWindowId
import com.kmnexus.codexmeter.domain.quota.CurrentQuotaStateFactory
import com.kmnexus.codexmeter.domain.settings.NotificationPreferenceReader
import com.kmnexus.codexmeter.domain.settings.NotificationPreferences
import com.kmnexus.codexmeter.widget.WidgetQuotaConfiguration
import com.kmnexus.codexmeter.widget.WidgetQuotaState
import com.kmnexus.codexmeter.widget.WidgetQuotaStateFactory
import com.kmnexus.codexmeter.widget.WidgetQuotaStateLoader
import java.time.Clock

internal class WidgetQuotaStateRepository(
    private val providerAccountDao: ProviderAccountDao,
    private val quotaSnapshotDao: QuotaSnapshotDao,
    private val refreshAttemptDao: RefreshAttemptDao,
    private val currentQuotaStateFactory: CurrentQuotaStateFactory = CurrentQuotaStateFactory(),
    private val widgetQuotaStateFactory: WidgetQuotaStateFactory = WidgetQuotaStateFactory(),
    private val notificationPreferenceReader: NotificationPreferenceReader = DefaultWidgetNotificationPreferenceReader,
    private val currencyPreferenceReader: CurrencyPreferenceReader = DefaultWidgetCurrencyPreferenceReader,
    private val exchangeRateReader: ExchangeRateReader = DefaultWidgetExchangeRateReader,
    private val clock: Clock,
) : WidgetQuotaStateLoader {
    override suspend fun loadWidgetQuotaState(configuration: WidgetQuotaConfiguration): WidgetQuotaState {
        val providerId = configuration.providerId?.takeIf { it.isNotBlank() }
        val localAccountId = configuration.localAccountId?.takeIf { it.isNotBlank() }
        if (providerId == null || localAccountId == null) {
            return widgetQuotaStateFactory.unconfigured(hasAccounts = hasSelectableAccount())
        }
        val account = providerAccountDao.getById(localAccountId)
            ?.toDomain()
            ?.takeIf { it.providerId.value == providerId }
            ?: return widgetQuotaStateFactory.unconfigured(hasAccounts = hasSelectableAccount())

        val latestSnapshot = quotaSnapshotDao.getLatestForAccount(
            providerId = account.providerId.value,
            localAccountId = account.localAccountId.value,
        )?.toDomain()
        val latestAttempt = refreshAttemptDao.getLatestForAccount(
            providerId = account.providerId.value,
            localAccountId = account.localAccountId.value,
        )?.toDomain()
        // primaryWindow 用于 CurrentQuotaState 内部计算；取第一个已选窗口或默认。
        val primaryWindowId = configuration.selectedWindowIds.firstOrNull()
            ?.let(::QuotaWindowId)
            ?: DEFAULT_WIDGET_PRIMARY_WINDOW_ID
        val currentQuotaState = currentQuotaStateFactory.create(
            account = account,
            latestSnapshot = latestSnapshot,
            latestAttempt = latestAttempt,
            now = clock.instant(),
            primaryWindowId = primaryWindowId,
        )
        // 与首页 / 通知路径一致：余额先按目标货币换算，工厂再与余额阈值比较并显示换算后的值。
        val currency = currencyPreferenceReader.currencyPreferences()
        val rates = exchangeRateReader.currentRates()
        val convertedState = currentQuotaState.let { state ->
            state.copy(
                snapshot = state.snapshot?.copy(
                    windows = state.snapshot.windows.map { it.withConvertedBalance(currency.targetCurrency, rates) },
                ),
                primaryWindow = state.primaryWindow?.withConvertedBalance(currency.targetCurrency, rates),
                secondaryWindows = state.secondaryWindows.map { it.withConvertedBalance(currency.targetCurrency, rates) },
            )
        }
        return widgetQuotaStateFactory.create(
            state = convertedState,
            notificationPreferences = notificationPreferenceReader.notificationPreferences(),
            selectedWindowIds = configuration.selectedWindowIds,
        )
    }

    private suspend fun hasSelectableAccount(): Boolean =
        providerAccountDao.listAll().any { it.toDomain().status != AccountStatus.Deleted }
}

private object DefaultWidgetNotificationPreferenceReader : NotificationPreferenceReader {
    override suspend fun notificationPreferences(): NotificationPreferences = NotificationPreferences()
}

private object DefaultWidgetCurrencyPreferenceReader : CurrencyPreferenceReader {
    override suspend fun currencyPreferences(): CurrencyPreferences = CurrencyPreferences()
}

private object DefaultWidgetExchangeRateReader : ExchangeRateReader {
    override suspend fun currentRates(): ExchangeRates? = null
}

private val DEFAULT_WIDGET_PRIMARY_WINDOW_ID = QuotaWindowId("five_hour")
