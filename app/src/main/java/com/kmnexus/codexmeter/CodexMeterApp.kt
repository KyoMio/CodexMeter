package com.kmnexus.codexmeter

import android.app.Application
import androidx.work.Configuration
import com.kmnexus.codexmeter.app.AppContainer
import com.kmnexus.codexmeter.core.i18n.AppLocaleController
import com.kmnexus.codexmeter.domain.account.AccountDeleteUseCase
import com.kmnexus.codexmeter.domain.account.AccountListUseCase
import com.kmnexus.codexmeter.domain.account.AccountRenameUseCase
import com.kmnexus.codexmeter.domain.account.AccountSwitchUseCase
import com.kmnexus.codexmeter.data.currency.ExchangeRateReader
import com.kmnexus.codexmeter.domain.auth.ApiKeyLoginUseCase
import com.kmnexus.codexmeter.domain.auth.SessionLoginUseCase
import com.kmnexus.codexmeter.domain.currency.CurrencyPreferenceReader
import com.kmnexus.codexmeter.domain.currency.CurrencyPreferenceStore
import com.kmnexus.codexmeter.domain.auth.DeviceCodeLoginController
import com.kmnexus.codexmeter.domain.auth.DeviceCodeLoginNotifier
import com.kmnexus.codexmeter.domain.model.ProviderAccount
import com.kmnexus.codexmeter.domain.settings.DEFAULT_REFRESH_INTERVAL_MINUTES
import com.kmnexus.codexmeter.domain.settings.NotificationPreferenceStore
import com.kmnexus.codexmeter.domain.settings.PrimaryQuotaWindowPreferenceStore
import com.kmnexus.codexmeter.domain.settings.QuotaHistoryClearUseCase
import com.kmnexus.codexmeter.domain.settings.RetentionPreferenceStore
import com.kmnexus.codexmeter.domain.update.AppUpdateCheckUseCase
import com.kmnexus.codexmeter.domain.update.AppUpdateDownloadUseCase
import com.kmnexus.codexmeter.refresh.ExchangeRateRefresher
import com.kmnexus.codexmeter.refresh.QuotaRefreshDependenciesProvider
import com.kmnexus.codexmeter.refresh.RefreshCoordinator
import com.kmnexus.codexmeter.ui.home.HomeCurrentQuotaStateLoader
import com.kmnexus.codexmeter.ui.home.HomeRefreshUseCase
import com.kmnexus.codexmeter.ui.home.HomeAppOpenRefreshUseCase
import com.kmnexus.codexmeter.ui.home.HomeTrendHistoryLoader
import com.kmnexus.codexmeter.refresh.RefreshWorkScheduler
import com.kmnexus.codexmeter.app.NotificationWindowChoicesLoader
import com.kmnexus.codexmeter.ui.account.AccountQuotaAlertEvaluationRequester
import com.kmnexus.codexmeter.ui.settings.SettingsBackgroundRefreshStatusReader
import com.kmnexus.codexmeter.ui.settings.SettingsDiagnosticsReader
import com.kmnexus.codexmeter.widget.WidgetQuotaStateLoader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class CodexMeterApp : Application(), Configuration.Provider, QuotaRefreshDependenciesProvider {
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val appContainer: AppContainer by lazy {
        AppContainer.create(this)
    }

    override val workManagerConfiguration: Configuration =
        Configuration.Builder().build()

    override val refreshCoordinator: RefreshCoordinator
        get() = appContainer.refreshCoordinator

    override val exchangeRateRefresher: ExchangeRateRefresher
        get() = appContainer.exchangeRateRefresher

    val accountListUseCase: AccountListUseCase
        get() = appContainer.accountListUseCase

    val accountDeleteUseCase: AccountDeleteUseCase
        get() = appContainer.accountDeleteUseCase

    val accountSwitchUseCase: AccountSwitchUseCase
        get() = appContainer.accountSwitchUseCase

    val accountRenameUseCase: AccountRenameUseCase
        get() = appContainer.accountRenameUseCase

    val accountRefreshAllUseCase: com.kmnexus.codexmeter.ui.account.AccountRefreshAllUseCase
        get() = appContainer.accountRefreshAllUseCase

    val accountQuotaAlertEvaluationRequester: AccountQuotaAlertEvaluationRequester
        get() = appContainer.accountQuotaAlertEvaluationRequester

    val homeCurrentQuotaStateLoader: HomeCurrentQuotaStateLoader
        get() = appContainer.homeCurrentQuotaStateLoader

    val homeAppOpenRefreshUseCase: HomeAppOpenRefreshUseCase
        get() = appContainer.homeAppOpenRefreshUseCase

    val homeRefreshUseCase: HomeRefreshUseCase
        get() = appContainer.homeRefreshUseCase

    val deviceCodeLoginController: DeviceCodeLoginController
        get() = appContainer.deviceCodeLoginController

    val deviceCodeLoginNotifier: DeviceCodeLoginNotifier
        get() = appContainer.deviceCodeLoginNotifier

    val retentionPreferenceStore: RetentionPreferenceStore
        get() = appContainer.retentionPreferences

    val notificationPreferenceStore: NotificationPreferenceStore
        get() = appContainer.notificationPreferences

    val primaryQuotaWindowPreferenceStore: PrimaryQuotaWindowPreferenceStore
        get() = appContainer.primaryQuotaWindowPreferences

    val quotaHistoryClearUseCase: QuotaHistoryClearUseCase
        get() = appContainer.quotaHistoryClearUseCase

    val homeTrendHistoryLoader: HomeTrendHistoryLoader
        get() = appContainer.homeTrendHistoryLoader

    val backgroundRefreshStatusReader: SettingsBackgroundRefreshStatusReader
        get() = appContainer.backgroundRefreshStatusReader

    val settingsDiagnosticsReader: SettingsDiagnosticsReader
        get() = appContainer.settingsDiagnosticsReader

    val appUpdateChecker: AppUpdateCheckUseCase
        get() = appContainer.appUpdateChecker

    val appUpdateDownloader: AppUpdateDownloadUseCase
        get() = appContainer.appUpdateDownloader

    val widgetQuotaStateLoader: WidgetQuotaStateLoader
        get() = appContainer.widgetQuotaStateLoader

    val apiKeyLoginUseCase: SessionLoginUseCase
        get() = appContainer.apiKeyLoginUseCase

    val currencyPreferenceReader: CurrencyPreferenceReader
        get() = appContainer.currencyPreferences

    val currencyPreferenceStore: CurrencyPreferenceStore
        get() = appContainer.currencyPreferences

    val exchangeRateReader: ExchangeRateReader
        get() = appContainer.exchangeRateRepository

    val notificationWindowChoicesLoader: NotificationWindowChoicesLoader
        get() = appContainer.notificationWindowChoicesLoader

    override fun onCreate() {
        super.onCreate()
        AppLocaleController.ensureFollowsSystemLocale(this)
        appContainer.startupMaintenance.start(applicationScope)
        val scheduler = RefreshWorkScheduler.from(this)
        registerRefreshWork(scheduler)
        // Re-apply the user's configured cadence (15/30 min, or Manual = cancel) once prefs are read.
        applicationScope.launch {
            val minutes = runCatching {
                appContainer.notificationPreferences.notificationPreferences().backgroundRefreshIntervalMinutes
            }.getOrDefault(DEFAULT_REFRESH_INTERVAL_MINUTES)
            scheduler.applyIntervalMinutes(minutes)
        }
    }

    override suspend fun activeQuotaRefreshAccounts(): List<ProviderAccount> =
        appContainer.activeQuotaRefreshAccounts()

    companion object {
        fun registerRefreshWork(scheduler: RefreshWorkScheduler) {
            scheduler.schedulePeriodicRefresh()
        }
    }
}
