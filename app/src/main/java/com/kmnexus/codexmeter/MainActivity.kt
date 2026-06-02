package com.kmnexus.codexmeter

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.kmnexus.codexmeter.ui.navigation.CodexMeterNavHost
import com.kmnexus.codexmeter.ui.navigation.CodexMeterRoute
import com.kmnexus.codexmeter.ui.theme.CodexMeterFontScheme
import com.kmnexus.codexmeter.ui.theme.CodexMeterTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val codexMeterApp = application as CodexMeterApp
        val startDestination = CodexMeterRoute.startRouteForLaunchDestination(
            intent?.getStringExtra(CodexMeterRoute.EXTRA_LAUNCH_DESTINATION),
        )
        setContent {
            CodexMeterTheme(fontScheme = CodexMeterFontScheme.MonoFocusGeistMono) {
                CodexMeterNavHost(
                    startDestination = startDestination,
                    deviceCodeLoginController = codexMeterApp.deviceCodeLoginController,
                    deviceCodeLoginNotifier = codexMeterApp.deviceCodeLoginNotifier,
                    accountListUseCase = codexMeterApp.accountListUseCase,
                    accountDeleteUseCase = codexMeterApp.accountDeleteUseCase,
                    accountSwitchUseCase = codexMeterApp.accountSwitchUseCase,
                    accountRenameUseCase = codexMeterApp.accountRenameUseCase,
                    accountQuotaAlertEvaluationRequester = codexMeterApp.accountQuotaAlertEvaluationRequester,
                    accountRefreshAllUseCase = codexMeterApp.accountRefreshAllUseCase,
                    homeCurrentQuotaStateLoader = codexMeterApp.homeCurrentQuotaStateLoader,
                    homeAppOpenRefreshUseCase = codexMeterApp.homeAppOpenRefreshUseCase,
                    homeRefreshUseCase = codexMeterApp.homeRefreshUseCase,
                    homeTrendHistoryLoader = codexMeterApp.homeTrendHistoryLoader,
                    retentionPreferenceStore = codexMeterApp.retentionPreferenceStore,
                    notificationPreferenceStore = codexMeterApp.notificationPreferenceStore,
                    backgroundRefreshStatusReader = codexMeterApp.backgroundRefreshStatusReader,
                    settingsDiagnosticsReader = codexMeterApp.settingsDiagnosticsReader,
                    quotaHistoryClearUseCase = codexMeterApp.quotaHistoryClearUseCase,
                    appUpdateChecker = codexMeterApp.appUpdateChecker,
                    appUpdateDownloader = codexMeterApp.appUpdateDownloader,
                    apiKeyLoginUseCase = codexMeterApp.apiKeyLoginUseCase,
                    currencyPreferenceReader = codexMeterApp.currencyPreferenceReader,
                    exchangeRateReader = codexMeterApp.exchangeRateReader,
                    notificationWindowChoicesLoader = codexMeterApp.notificationWindowChoicesLoader,
                    currencyPreferenceStore = codexMeterApp.currencyPreferenceStore,
                )
            }
        }
    }
}
