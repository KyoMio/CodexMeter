package com.kmnexus.codexmeter.ui.settings

import com.kmnexus.codexmeter.R
import com.kmnexus.codexmeter.domain.account.AccountListResult
import com.kmnexus.codexmeter.domain.account.AccountListUseCase
import com.kmnexus.codexmeter.domain.currency.CurrencyPreferenceStore
import com.kmnexus.codexmeter.domain.currency.CurrencyPreferences
import com.kmnexus.codexmeter.domain.model.LocalAccountId
import com.kmnexus.codexmeter.domain.model.ProviderAccount
import com.kmnexus.codexmeter.domain.model.ProviderAccountId
import com.kmnexus.codexmeter.domain.model.ProviderId
import com.kmnexus.codexmeter.domain.settings.RetentionPreference
import com.kmnexus.codexmeter.domain.settings.RetentionPreferenceStore
import com.kmnexus.codexmeter.domain.settings.NotificationPreferenceStore
import com.kmnexus.codexmeter.domain.settings.NotificationAccountSelection
import com.kmnexus.codexmeter.domain.settings.NotificationPreferences
import com.kmnexus.codexmeter.domain.model.QuotaWindowId
import com.kmnexus.codexmeter.domain.settings.QuotaHistoryClearResult
import com.kmnexus.codexmeter.domain.settings.QuotaHistoryClearUseCase
import com.kmnexus.codexmeter.domain.update.AppUpdateCheckResult
import com.kmnexus.codexmeter.domain.update.AppUpdateCheckUseCase
import com.kmnexus.codexmeter.domain.update.AppUpdateDownloadResult
import com.kmnexus.codexmeter.domain.update.AppUpdateDownloadUseCase
import com.kmnexus.codexmeter.domain.update.AppUpdateInfo
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestWatcher
import org.junit.runner.Description

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val viewModel = SettingsViewModel()

    @Test
    fun `primary window default is five hour`() {
        val uiState = viewModel.uiState.value

        assertEquals(com.kmnexus.codexmeter.domain.settings.DEFAULT_NOTIFICATION_WINDOW_ID, uiState.persistentNotification.selectedWindowId)
        assertEquals("five_hour", uiState.persistentNotification.selectedWindowId.value)
    }

    @Test
    fun `threshold ordering is enforced`() {
        viewModel.updateAlertThresholds(caution = 10, warning = 80)

        val thresholds = viewModel.uiState.value.alerts.thresholds
        assertEquals(10, thresholds.caution)
        assertEquals(9, thresholds.warning)
        assertEquals(0, thresholds.limit)
        assertTrue(thresholds.limit < thresholds.warning)
        assertTrue(thresholds.warning < thresholds.caution)
    }

    @Test
    fun `retention default is 30 days`() {
        val uiState = viewModel.uiState.value

        assertEquals(SettingsRetentionOption.ThirtyDays, uiState.data.retention)
        assertEquals(30, uiState.data.retention.days)
    }

    @Test
    fun `loading settings uses persisted retention preference`() = runTest {
        val viewModel = SettingsViewModel(retentionPreferenceStore = RecordingRetentionPreferenceStore(RetentionPreference.Forever))

        viewModel.loadSettings()
        runCurrent()

        assertEquals(SettingsRetentionOption.Forever, viewModel.uiState.value.data.retention)
    }

    @Test
    fun `loading settings uses persisted notification preferences`() = runTest {
        val viewModel = SettingsViewModel(
            notificationPreferenceStore = RecordingNotificationPreferenceStore(
                NotificationPreferences(
                    statusNotificationEnabled = false,
                    quotaAlertsEnabled = false,
                    accountErrorsEnabled = false,
                    cautionThreshold = 25,
                    warningThreshold = 8,
                ),
            ),
        )

        viewModel.loadSettings()
        runCurrent()

        val alerts = viewModel.uiState.value.alerts
        assertFalse(viewModel.uiState.value.persistentNotification.enabled)
        assertFalse(alerts.accountErrorsEnabled)
        assertEquals(25, alerts.thresholds.caution)
        assertEquals(8, alerts.thresholds.warning)
    }

    @Test
    fun `settings loads account choices for persistent notification`() = runTest {
        val viewModel = SettingsViewModel(
            accountListUseCase = RecordingAccountListUseCase(
                accounts = listOf(
                    account(localAccountId = "local-1", displayName = "Work"),
                    account(localAccountId = "local-2", displayName = "Personal"),
                ),
            ),
            notificationPreferenceStore = RecordingNotificationPreferenceStore(
                NotificationPreferences(
                    persistentNotificationAccount = NotificationAccountSelection(
                        providerId = ProviderId("codex"),
                        localAccountId = LocalAccountId("local-2"),
                    ),
                    persistentNotificationWindowId = QuotaWindowId("weekly"),
                ),
            ),
        )

        viewModel.loadSettings()
        runCurrent()

        val persistentNotification = viewModel.uiState.value.persistentNotification
        assertEquals(
            listOf("follow_current", "local-1", "local-2"),
            persistentNotification.accountChoices.map { it.stableId },
        )
        assertEquals(
            SettingsNotificationAccountSelection.Account(
                providerId = ProviderId("codex"),
                localAccountId = LocalAccountId("local-2"),
                displayName = "Personal",
            ),
            persistentNotification.accountSelection,
        )
        assertEquals(com.kmnexus.codexmeter.domain.model.QuotaWindowId("weekly"), persistentNotification.selectedWindowId)
    }

    @Test
    fun `updating retention persists selected preference`() = runTest {
        val store = RecordingRetentionPreferenceStore(RetentionPreference.ThirtyDays)
        val viewModel = SettingsViewModel(retentionPreferenceStore = store)

        viewModel.updateRetention(SettingsRetentionOption.SevenDays)
        runCurrent()

        assertEquals(SettingsRetentionOption.SevenDays, viewModel.uiState.value.data.retention)
        assertEquals(listOf(RetentionPreference.SevenDays), store.updates)
    }

    @Test
    fun `updating notification settings persists shared notification preferences`() = runTest {
        val store = RecordingNotificationPreferenceStore(NotificationPreferences())
        val viewModel = SettingsViewModel(notificationPreferenceStore = store)

        viewModel.setStatusNotificationEnabled(false)
        runCurrent()
        viewModel.setAccountErrorsEnabled(false)
        runCurrent()
        viewModel.updateAlertThresholds(caution = 25, warning = 8)
        runCurrent()

        assertEquals(
            NotificationPreferences(
                statusNotificationEnabled = false,
                accountErrorsEnabled = false,
                cautionThreshold = 25,
                warningThreshold = 8,
            ),
            store.updates.last(),
        )
    }

    @Test
    fun `local notification edit is not overwritten by late persisted load`() = runTest {
        val store = DelayedNotificationPreferenceStore(NotificationPreferences())
        val viewModel = SettingsViewModel(notificationPreferenceStore = store)

        viewModel.loadSettings()
        runCurrent()
        viewModel.setStatusNotificationEnabled(false)
        runCurrent()
        store.loadGate.complete(Unit)
        runCurrent()

        assertFalse(viewModel.uiState.value.persistentNotification.enabled)
        assertFalse(store.updates.last().statusNotificationEnabled)
    }

    @Test
    fun `changing persistent notification account and window persists notification-only settings`() = runTest {
        val store = RecordingNotificationPreferenceStore(
            NotificationPreferences(
                accountErrorsEnabled = false,
                cautionThreshold = 25,
                warningThreshold = 8,
            ),
        )
        val viewModel = SettingsViewModel(
            notificationPreferenceStore = store,
            accountListUseCase = RecordingAccountListUseCase(
                accounts = listOf(account(localAccountId = "local-2", displayName = "Personal")),
            ),
        )
        viewModel.loadSettings()
        runCurrent()

        viewModel.updatePersistentNotificationAccount(
            SettingsNotificationAccountSelection.Account(
                providerId = ProviderId("codex"),
                localAccountId = LocalAccountId("local-2"),
                displayName = "Personal",
            ),
        )
        runCurrent()
        viewModel.updatePersistentNotificationWindow(com.kmnexus.codexmeter.domain.model.QuotaWindowId("weekly"))
        runCurrent()

        assertEquals(
            NotificationAccountSelection(
                providerId = ProviderId("codex"),
                localAccountId = LocalAccountId("local-2"),
            ),
            store.updates.last().persistentNotificationAccount,
        )
        assertEquals(QuotaWindowId("weekly"), store.updates.last().persistentNotificationWindowId)
        assertFalse(store.updates.last().accountErrorsEnabled)
        assertEquals(25, store.updates.last().cautionThreshold)
        assertEquals(8, store.updates.last().warningThreshold)
    }

    @Test
    fun `retention persistence failure keeps previous option`() = runTest {
        val viewModel = SettingsViewModel(
            retentionPreferenceStore = ThrowingRetentionPreferenceStore(IllegalStateException("store failed")),
        )

        viewModel.updateRetention(SettingsRetentionOption.SevenDays)
        runCurrent()

        assertEquals(SettingsRetentionOption.ThirtyDays, viewModel.uiState.value.data.retention)
    }

    @Test
    fun `requesting visible choice dialogs records which settings popup is open`() {
        viewModel.requestChoiceDialog(SettingsChoiceDialog.NotificationWindow)
        assertEquals(SettingsChoiceDialog.NotificationWindow, viewModel.uiState.value.pendingChoiceDialog)

        viewModel.requestChoiceDialog(SettingsChoiceDialog.Retention)
        assertEquals(SettingsChoiceDialog.Retention, viewModel.uiState.value.pendingChoiceDialog)

        viewModel.dismissChoiceDialog()
        assertNull(viewModel.uiState.value.pendingChoiceDialog)
    }

    @Test
    fun `diagnostics copy model is redacted`() {
        val copyModel = viewModel.buildDiagnosticsCopyModel(
            SettingsDiagnosticsInput(
                providerId = "codex",
                accountDiagnosticId = "acct-safe-hash",
                currentState = "failed",
                lastAttemptStatus = "failed",
                unsafeDetails = "Authorization: Bearer should_not_leave\nCookie: should_not_leave",
            ),
        )

        assertTrue(copyModel.text.contains("providerId=codex"))
        assertTrue(copyModel.text.contains("accountDiagnosticId=acct-safe-hash"))
        assertTrue(copyModel.text.contains("[REDACTED]"))
        assertFalse(copyModel.text.contains("should_not_leave"))
    }

    @Test
    fun `diagnostics unsafe details collapse raw auth json payload`() {
        val copyModel = viewModel.buildDiagnosticsCopyModel(
            SettingsDiagnosticsInput(
                unsafeDetails = """
                    {"access_token":"access-secret","refresh_token":"refresh-secret","id_token":"id-secret"}
                """.trimIndent(),
            ),
        )

        assertTrue(copyModel.text.contains("details=[REDACTED]"))
        assertFalse(copyModel.text.contains("access_token"))
        assertFalse(copyModel.text.contains("refresh-secret"))
    }

    @Test
    fun `diagnostics unsafe details collapse raw provider response payload`() {
        val copyModel = viewModel.buildDiagnosticsCopyModel(
            SettingsDiagnosticsInput(
                unsafeDetails = "response body: {\"usage\":\"raw-provider-payload\",\"limit\":100}",
            ),
        )

        assertTrue(copyModel.text.contains("details=[REDACTED]"))
        assertFalse(copyModel.text.contains("raw-provider-payload"))
        assertFalse(copyModel.text.contains("response body"))
    }

    @Test
    fun `debug diagnostics include auth refresh and device-code fields without raw secrets`() {
        val copyModel = viewModel.buildDiagnosticsCopyModel(
            SettingsDiagnosticsInput(
                appVersion = "0.1.0-debug",
                buildType = "debug",
                androidSdk = "36",
                providerId = "codex",
                accountDiagnosticId = "sha256:abcdef123456",
                currentAccountSelectionStatus = "selected_account_present",
                accountStatus = "active",
                accountCount = 1,
                sessionEnvelopeStatus = "present",
                sessionProviderAccountIdStatus = "present",
                currentState = "failed",
                latestSnapshotStatus = "present",
                latestSnapshotSource = "device_code_login",
                latestSnapshotFetchedAt = "1700000000000",
                latestSnapshotDigestStatus = "present",
                lastSuccessfulRefreshAt = "1700000000000",
                lastAttemptStatus = "failed",
                lastAttemptTrigger = "manual",
                lastAttemptStartedAt = "1700000001000",
                lastAttemptFinishedAt = "1700000002000",
                safeErrorCode = "error_network",
                httpStatus = 500,
                retryable = true,
                userActionRequired = false,
                diagnosticsDigest = "codex_usage_http_500",
                deviceCodeLoginStatus = "validation_failed",
                deviceCodeLoginAttemptId = "sha256:fedcba654321",
                deviceCodeLoginSafeErrorCode = "error_network",
                deviceCodeLoginVerificationUriStatus = "official_auth_openai",
                deviceCodeLoginPollIntervalSeconds = 8,
                deviceCodeLoginExpiresAt = "2026-05-25T00:00:00Z",
                workManagerStatus = "quota_periodic_refresh:enqueued",
                notificationPermissionStatus = "granted",
                unsafeDetails = "Authorization: Bearer should_not_leave code=ONE-TIME-CODE",
            ),
        )

        val text = copyModel.text
        assertTrue(text.contains("sessionEnvelopeStatus=present"))
        assertTrue(text.contains("diagnosticsDigest=codex_usage_http_500"))
        assertTrue(text.contains("deviceCodeLoginStatus=validation_failed"))
        assertTrue(text.contains("deviceCodeLoginVerificationUriStatus=official_auth_openai"))
        assertTrue(text.contains("workManagerStatus=quota_periodic_refresh:enqueued"))
        assertTrue(text.contains("details=[REDACTED]"))
        assertFalse(text.contains("should_not_leave"))
        assertFalse(text.contains("ONE-TIME-CODE"))
    }

    @Test
    fun `copy diagnostics reads injected diagnostics source`() = runTest {
        val viewModel = SettingsViewModel(
            diagnosticsReader = SettingsDiagnosticsReader {
                SettingsDiagnosticsInput(
                    accountDiagnosticId = "sha256:from-reader",
                    currentAccountSelectionStatus = "selected_account_present",
                    deviceCodeLoginStatus = "failed",
                    deviceCodeLoginSafeErrorCode = "error_network",
                )
            },
        )

        val copyModel = viewModel.copyDiagnostics()

        assertEquals(copyModel, viewModel.uiState.value.diagnostics.copyModel)
        assertTrue(copyModel.text.contains("accountDiagnosticId=sha256:from-reader"))
        assertTrue(copyModel.text.contains("deviceCodeLoginStatus=failed"))
        assertTrue(copyModel.text.contains("deviceCodeLoginSafeErrorCode=error_network"))
    }

    @Test
    fun `confirming clear current history invokes clear use case and clears pending action`() = runTest {
        val clearUseCase = RecordingQuotaHistoryClearUseCase()
        val viewModel = SettingsViewModel(quotaHistoryClearUseCase = clearUseCase)

        viewModel.requestClearCurrentHistory()

        viewModel.confirmDataAction()
        runCurrent()

        val data = viewModel.uiState.value.data
        assertEquals(listOf(SettingsPendingDataAction.ClearCurrentHistory), clearUseCase.requests)
        assertEquals(SettingsPendingDataAction.ClearCurrentHistory, data.confirmedAction)
        assertNull(data.pendingAction)
    }

    @Test
    fun `confirming clear all history invokes clear use case and clears pending action`() = runTest {
        val clearUseCase = RecordingQuotaHistoryClearUseCase()
        val viewModel = SettingsViewModel(quotaHistoryClearUseCase = clearUseCase)

        viewModel.requestClearAllHistory()

        viewModel.confirmDataAction()
        runCurrent()

        val data = viewModel.uiState.value.data
        assertEquals(listOf(SettingsPendingDataAction.ClearAllHistory), clearUseCase.requests)
        assertEquals(SettingsPendingDataAction.ClearAllHistory, data.confirmedAction)
        assertNull(data.pendingAction)
    }

    @Test
    fun `copy diagnostics returns and stores redacted clipboard model`() {
        val copyModel = viewModel.copyDiagnostics(
            SettingsDiagnosticsInput(
                unsafeDetails = "Authorization: Bearer clipboard-secret",
            ),
        )

        assertEquals(copyModel, viewModel.uiState.value.diagnostics.copyModel)
        assertTrue(copyModel.text.contains("[REDACTED]"))
        assertFalse(copyModel.text.contains("clipboard-secret"))
    }

    @Test
    fun `request diagnostics recheck records separate diagnostics event`() = runTest {
        viewModel.requestDiagnosticsRecheck()
        runCurrent()

        val uiState = viewModel.uiState.value
        assertEquals(1, uiState.diagnostics.recheckRequestCount)
        assertTrue(uiState.diagnostics.copyModel?.text?.contains("currentAccountSelectionStatus=unknown") == true)
    }

    @Test
    fun `loading settings shows latest background refresh success`() = runTest {
        val viewModel = SettingsViewModel(
            backgroundRefreshStatusReader = RecordingBackgroundRefreshStatusReader(
                SettingsBackgroundRefreshStatus.Success,
            ),
        )

        viewModel.loadSettings()
        runCurrent()

        assertEquals(
            R.string.settings_refresh_last_result_success,
            viewModel.uiState.value.refresh.lastResultLabelResId,
        )
    }

    @Test
    fun `loading settings shows latest background refresh failure`() = runTest {
        val viewModel = SettingsViewModel(
            backgroundRefreshStatusReader = RecordingBackgroundRefreshStatusReader(
                SettingsBackgroundRefreshStatus.Failed,
            ),
        )

        viewModel.loadSettings()
        runCurrent()

        assertEquals(
            R.string.settings_refresh_last_result_failed,
            viewModel.uiState.value.refresh.lastResultLabelResId,
        )
    }

    @Test
    fun `stale persisted window id falls back to first loaded choice`() = runTest {
        val viewModel = SettingsViewModel(
            notificationPreferenceStore = RecordingNotificationPreferenceStore(
                NotificationPreferences(persistentNotificationWindowId = QuotaWindowId("nonexistent")),
            ),
            notificationWindowChoicesLoader = com.kmnexus.codexmeter.app.NotificationWindowChoicesLoader { _, _ ->
                listOf(com.kmnexus.codexmeter.app.NotificationWindowChoice(QuotaWindowId("balance")))
            },
        )

        viewModel.loadSettings()
        runCurrent()

        assertEquals(QuotaWindowId("balance"), viewModel.uiState.value.persistentNotification.selectedWindowId)
    }

    @Test
    fun `stale persisted window id is auto-persisted to the first loaded choice`() = runTest {
        val store = RecordingNotificationPreferenceStore(
            NotificationPreferences(persistentNotificationWindowId = QuotaWindowId("nonexistent")),
        )
        val viewModel = SettingsViewModel(
            notificationPreferenceStore = store,
            notificationWindowChoicesLoader = com.kmnexus.codexmeter.app.NotificationWindowChoicesLoader { _, _ ->
                listOf(com.kmnexus.codexmeter.app.NotificationWindowChoice(QuotaWindowId("balance")))
            },
        )

        viewModel.loadSettings()
        runCurrent()

        assertEquals(QuotaWindowId("balance"), viewModel.uiState.value.persistentNotification.selectedWindowId)
        assertEquals(QuotaWindowId("balance"), store.updates.last().persistentNotificationWindowId)
    }

    @Test
    fun `checking for app update exposes available release dialog`() = runTest {
        val updateInfo = appUpdateInfo()
        val checker = DelayedAppUpdateCheckUseCase(AppUpdateCheckResult.UpdateAvailable(updateInfo))
        val viewModel = SettingsViewModel(
            appUpdateChecker = checker,
            appUpdateDownloader = RecordingAppUpdateDownloadUseCase(),
            currentVersionName = "0.1.0-debug",
        )

        viewModel.checkForUpdates()
        runCurrent()

        assertTrue(viewModel.uiState.value.update.isChecking)
        checker.complete()
        runCurrent()

        val update = viewModel.uiState.value.update
        assertFalse(update.isChecking)
        assertEquals(updateInfo, update.pendingUpdate)
        assertEquals(R.string.settings_update_status_update_available, update.statusLabelResId)
    }

    @Test
    fun `download pending app update delegates to system downloader and clears dialog`() = runTest {
        val updateInfo = appUpdateInfo()
        val downloader = RecordingAppUpdateDownloadUseCase()
        val viewModel = SettingsViewModel(
            appUpdateChecker = ImmediateAppUpdateCheckUseCase(AppUpdateCheckResult.UpdateAvailable(updateInfo)),
            appUpdateDownloader = downloader,
            currentVersionName = "0.1.0",
        )

        viewModel.checkForUpdates()
        runCurrent()
        viewModel.downloadPendingUpdate()
        runCurrent()

        val update = viewModel.uiState.value.update
        assertEquals(listOf(updateInfo), downloader.requests)
        assertNull(update.pendingUpdate)
        assertEquals(R.string.settings_update_status_downloading, update.statusLabelResId)
    }

    @Test
    fun `currency target pick persists and updates ui state`() = runTest {
        val store = RecordingCurrencyPreferenceStore(CurrencyPreferences())
        val viewModel = SettingsViewModel(currencyPreferenceStore = store)

        viewModel.updateCurrencyTarget("CNY")
        runCurrent()

        val currency = viewModel.uiState.value.currency
        assertEquals("CNY", currency.targetCurrency)
        assertNull(viewModel.uiState.value.pendingChoiceDialog)
        assertEquals(CurrencyPreferences(targetCurrency = "CNY"), store.latest)
    }

    private class RecordingCurrencyPreferenceStore(
        private var preferences: CurrencyPreferences,
    ) : CurrencyPreferenceStore {
        var latest: CurrencyPreferences = preferences

        override suspend fun currencyPreferences(): CurrencyPreferences = preferences

        override suspend fun updateCurrencyPreferences(preferences: CurrencyPreferences) {
            this.preferences = preferences
            this.latest = preferences
        }
    }

    private class RecordingRetentionPreferenceStore(
        private var preference: RetentionPreference,
    ) : RetentionPreferenceStore {
        val updates = mutableListOf<RetentionPreference>()

        override suspend fun retentionPreference(): RetentionPreference = preference

        override suspend fun updateRetentionPreference(preference: RetentionPreference) {
            updates += preference
            this.preference = preference
        }
    }

    private class RecordingBackgroundRefreshStatusReader(
        private val status: SettingsBackgroundRefreshStatus,
    ) : SettingsBackgroundRefreshStatusReader {
        override suspend fun latestBackgroundRefreshStatus(): SettingsBackgroundRefreshStatus = status
    }

    private class RecordingQuotaHistoryClearUseCase : QuotaHistoryClearUseCase {
        val requests = mutableListOf<SettingsPendingDataAction>()

        override suspend fun clearCurrentAccountHistory(): QuotaHistoryClearResult {
            requests += SettingsPendingDataAction.ClearCurrentHistory
            return QuotaHistoryClearResult(deletedQuotaSnapshots = 1, deletedRefreshAttempts = 1)
        }

        override suspend fun clearAllHistory(): QuotaHistoryClearResult {
            requests += SettingsPendingDataAction.ClearAllHistory
            return QuotaHistoryClearResult(deletedQuotaSnapshots = 2, deletedRefreshAttempts = 2)
        }
    }

    private class ThrowingRetentionPreferenceStore(
        private val exception: Throwable,
    ) : RetentionPreferenceStore {
        override suspend fun retentionPreference(): RetentionPreference {
            throw exception
        }

        override suspend fun updateRetentionPreference(preference: RetentionPreference) {
            throw exception
        }
    }

    private class RecordingNotificationPreferenceStore(
        private var preferences: NotificationPreferences,
    ) : NotificationPreferenceStore {
        val updates = mutableListOf<NotificationPreferences>()

        override suspend fun notificationPreferences(): NotificationPreferences = preferences

        override suspend fun updateNotificationPreferences(preferences: NotificationPreferences) {
            updates += preferences
            this.preferences = preferences
        }
    }

    private class DelayedNotificationPreferenceStore(
        private val persistedPreferences: NotificationPreferences,
    ) : NotificationPreferenceStore {
        val loadGate = CompletableDeferred<Unit>()
        val updates = mutableListOf<NotificationPreferences>()

        override suspend fun notificationPreferences(): NotificationPreferences {
            loadGate.await()
            return persistedPreferences
        }

        override suspend fun updateNotificationPreferences(preferences: NotificationPreferences) {
            updates += preferences
        }
    }

    private class RecordingAccountListUseCase(
        private val accounts: List<ProviderAccount>,
    ) : AccountListUseCase {
        override suspend fun loadAccounts(): AccountListResult =
            AccountListResult(accounts = accounts, currentAccountId = accounts.firstOrNull()?.localAccountId)
    }

    private class DelayedAppUpdateCheckUseCase(
        private val result: AppUpdateCheckResult,
    ) : AppUpdateCheckUseCase {
        private val gate = CompletableDeferred<Unit>()

        override suspend fun checkForUpdate(currentVersionName: String): AppUpdateCheckResult {
            gate.await()
            return result
        }

        fun complete() {
            gate.complete(Unit)
        }
    }

    private class ImmediateAppUpdateCheckUseCase(
        private val result: AppUpdateCheckResult,
    ) : AppUpdateCheckUseCase {
        override suspend fun checkForUpdate(currentVersionName: String): AppUpdateCheckResult = result
    }

    private class RecordingAppUpdateDownloadUseCase : AppUpdateDownloadUseCase {
        val requests = mutableListOf<AppUpdateInfo>()

        override suspend fun download(update: AppUpdateInfo): AppUpdateDownloadResult {
            requests += update
            return AppUpdateDownloadResult.Enqueued(downloadId = 42L)
        }
    }

    private fun appUpdateInfo(): AppUpdateInfo =
        AppUpdateInfo(
            versionName = "v0.2.0",
            releasePageUrl = "https://github.com/KyoMio/CodexMeter/releases/tag/v0.2.0",
            apkDownloadUrl = "https://github.com/KyoMio/CodexMeter/releases/download/v0.2.0/CodexMeter-0.2.0.apk",
            apkFileName = "CodexMeter-0.2.0.apk",
        )

    private fun account(
        localAccountId: String,
        displayName: String,
    ): ProviderAccount =
        ProviderAccount.createNew(
            localAccountId = LocalAccountId(localAccountId),
            providerId = ProviderId("codex"),
            providerAccountId = ProviderAccountId("acct-$localAccountId"),
            displayName = displayName,
            now = java.time.Instant.parse("2026-05-23T09:00:00Z"),
        )

    class MainDispatcherRule : TestWatcher() {
        private val dispatcher = StandardTestDispatcher()

        override fun starting(description: Description) {
            Dispatchers.setMain(dispatcher)
        }

        override fun finished(description: Description) {
            Dispatchers.resetMain()
        }
    }
}
