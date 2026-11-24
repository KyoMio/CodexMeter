package com.kmnexus.codexmeter.ui.settings

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.kmnexus.codexmeter.BuildConfig
import com.kmnexus.codexmeter.R
import com.kmnexus.codexmeter.domain.account.AccountListUseCase
import com.kmnexus.codexmeter.domain.account.NoopAccountListUseCase
import com.kmnexus.codexmeter.domain.diagnostics.DiagnosticsRedactor
import com.kmnexus.codexmeter.domain.diagnostics.DiagnosticsTimeFormatter
import com.kmnexus.codexmeter.domain.model.AccountStatus
import com.kmnexus.codexmeter.domain.model.LocalAccountId
import com.kmnexus.codexmeter.domain.model.ProviderId
import com.kmnexus.codexmeter.domain.model.QuotaWindowId
import com.kmnexus.codexmeter.app.NotificationWindowChoice
import com.kmnexus.codexmeter.app.NotificationWindowChoicesLoader
import com.kmnexus.codexmeter.domain.currency.CurrencyPreferenceStore
import com.kmnexus.codexmeter.domain.currency.CurrencyPreferences
import com.kmnexus.codexmeter.domain.theme.AppearancePreferenceStore
import com.kmnexus.codexmeter.domain.theme.ThemeMode
import com.kmnexus.codexmeter.domain.settings.DEFAULT_BALANCE_CAUTION_THRESHOLD
import com.kmnexus.codexmeter.domain.settings.DEFAULT_BALANCE_WARNING_THRESHOLD
import com.kmnexus.codexmeter.domain.settings.DEFAULT_CAUTION_THRESHOLD
import com.kmnexus.codexmeter.domain.settings.DEFAULT_NOTIFICATION_WINDOW_ID
import com.kmnexus.codexmeter.domain.settings.DEFAULT_WARNING_THRESHOLD
import com.kmnexus.codexmeter.domain.settings.LIMIT_THRESHOLD
import com.kmnexus.codexmeter.domain.settings.NoopQuotaHistoryClearUseCase
import com.kmnexus.codexmeter.domain.settings.NotificationAccountSelection
import com.kmnexus.codexmeter.domain.settings.NotificationPreferenceStore
import com.kmnexus.codexmeter.domain.settings.NotificationPreferences
import com.kmnexus.codexmeter.domain.settings.QuotaHistoryClearUseCase
import com.kmnexus.codexmeter.domain.settings.RetentionPreference
import com.kmnexus.codexmeter.domain.settings.RetentionPreferenceStore
import com.kmnexus.codexmeter.ui.quota.quotaWindowLabelRes
import com.kmnexus.codexmeter.domain.update.AppUpdateCheckResult
import com.kmnexus.codexmeter.domain.update.AppUpdateCheckUseCase
import com.kmnexus.codexmeter.domain.update.AppUpdateDownloadResult
import com.kmnexus.codexmeter.domain.update.AppUpdateDownloadUseCase
import com.kmnexus.codexmeter.domain.update.AppUpdateInfo
import com.kmnexus.codexmeter.domain.update.AppVersionComparator
import com.kmnexus.codexmeter.domain.update.NoopAppUpdateCheckUseCase
import com.kmnexus.codexmeter.domain.update.NoopAppUpdateDownloadUseCase
import com.kmnexus.codexmeter.domain.update.NoopUpdatePreferenceStore
import com.kmnexus.codexmeter.domain.update.UpdatePreferenceStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class SettingsNotificationWindowChoice(
    val windowId: QuotaWindowId,
    @get:StringRes val labelResId: Int,
)

enum class SettingsRetentionOption(val days: Int?, @get:StringRes val labelResId: Int) {
    SevenDays(7, R.string.settings_retention_7_days),
    ThirtyDays(30, R.string.settings_retention_30_days),
    NinetyDays(90, R.string.settings_retention_90_days),
    Forever(null, R.string.settings_retention_forever),
}

enum class SettingsPendingDataAction(@get:StringRes val titleResId: Int, @get:StringRes val messageResId: Int) {
    ClearCurrentHistory(
        R.string.settings_data_clear_current_confirm_title,
        R.string.settings_data_clear_current_confirm_message,
    ),
    ClearAllHistory(
        R.string.settings_data_clear_all_confirm_title,
        R.string.settings_data_clear_all_confirm_message,
    ),
}

enum class SettingsRefreshInterval(val minutes: Int, @get:StringRes val labelResId: Int) {
    Fifteen(15, R.string.settings_refresh_interval_15min),
    Thirty(30, R.string.settings_refresh_interval_30min),
    Manual(0, R.string.settings_refresh_interval_manual);

    companion object {
        fun fromMinutes(minutes: Int): SettingsRefreshInterval =
            entries.firstOrNull { it.minutes == minutes } ?: Fifteen
    }
}

enum class SettingsChoiceDialog {
    NotificationAccount,
    NotificationWindow,
    Retention,
    RefreshInterval,
    CurrencyTarget,
}

data class SettingsAlertThresholdsUi(
    val caution: Int = DEFAULT_CAUTION_THRESHOLD,
    val warning: Int = DEFAULT_WARNING_THRESHOLD,
    val limit: Int = LIMIT_THRESHOLD,
)

data class SettingsAlertsUi(
    val statusNotificationEnabled: Boolean = true,
    val quotaAlertsEnabled: Boolean = true,
    val accountErrorsEnabled: Boolean = true,
    val thresholds: SettingsAlertThresholdsUi = SettingsAlertThresholdsUi(),
    // Balance-type providers (e.g. DeepSeek) alert on a currency amount, not a percent.
    val balanceCaution: Double = DEFAULT_BALANCE_CAUTION_THRESHOLD,
    val balanceWarning: Double = DEFAULT_BALANCE_WARNING_THRESHOLD,
)

/** One lockable notification target. No "follow" option remains: the account is fixed. */
data class SettingsNotificationAccountSelection(
    val providerId: ProviderId,
    val localAccountId: LocalAccountId,
    val displayName: String,
)

data class SettingsNotificationAccountChoice(
    val selection: SettingsNotificationAccountSelection,
) {
    val stableId: String = selection.localAccountId.value
}

data class SettingsPersistentNotificationUi(
    val enabled: Boolean = true,
    /** Null until the migration locks a concrete account or the device has no accounts. */
    val accountSelection: SettingsNotificationAccountSelection? = null,
    val accountChoices: List<SettingsNotificationAccountChoice> = emptyList(),
    val selectedWindowId: QuotaWindowId = DEFAULT_NOTIFICATION_WINDOW_ID,
    val windowChoices: List<SettingsNotificationWindowChoice> = emptyList(),
)

data class SettingsRefreshUi(
    val backgroundRefreshEnabled: Boolean = true,
    val interval: SettingsRefreshInterval = SettingsRefreshInterval.Fifteen,
    @get:StringRes val lastResultLabelResId: Int = R.string.settings_refresh_last_result_no_attempts,
)

data class SettingsCurrencyUi(
    val targetCurrency: String = CurrencyPreferences.DEFAULT_TARGET_CURRENCY,
    val supportedCurrencies: List<String> = CurrencyPreferences.SUPPORTED_TARGET_CURRENCIES,
)

data class SettingsAppearanceUi(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
)

/** Reschedules the background refresh worker when the interval changes (0 minutes = cancel/manual). */
fun interface BackgroundRefreshScheduler {
    fun applyIntervalMinutes(minutes: Int)
}

object NoopBackgroundRefreshScheduler : BackgroundRefreshScheduler {
    override fun applyIntervalMinutes(minutes: Int) = Unit
}

/** Schedules or cancels the daily update-check worker when the auto-check toggle changes. */
fun interface UpdateCheckScheduler {
    fun setAutoCheckEnabled(enabled: Boolean)
}

object NoopUpdateCheckScheduler : UpdateCheckScheduler {
    override fun setAutoCheckEnabled(enabled: Boolean) = Unit
}

internal object NoopCurrencyPreferenceStore : CurrencyPreferenceStore {
    override suspend fun currencyPreferences(): CurrencyPreferences = CurrencyPreferences()
    override suspend fun updateCurrencyPreferences(preferences: CurrencyPreferences) = Unit
}

internal object NoopAppearancePreferenceStore : AppearancePreferenceStore {
    override val themeMode: Flow<ThemeMode> = flowOf(ThemeMode.SYSTEM)

    override suspend fun setThemeMode(mode: ThemeMode) = Unit
}

data class SettingsDataUi(
    val retention: SettingsRetentionOption = SettingsRetentionOption.ThirtyDays,
    val pendingAction: SettingsPendingDataAction? = null,
    val confirmedAction: SettingsPendingDataAction? = null,
)

data class SettingsDiagnosticsUi(
    val isExpanded: Boolean = false,
    val copyModel: SettingsDiagnosticsCopyModel? = null,
    val recheckRequestCount: Int = 0,
)

data class SettingsUpdateUi(
    val isChecking: Boolean = false,
    @get:StringRes val statusLabelResId: Int? = null,
    val pendingUpdate: AppUpdateInfo? = null,
    val autoCheckEnabled: Boolean = true,
    val notifyOnUpdateEnabled: Boolean = true,
    val availableUpdate: AppUpdateInfo? = null,
)

data class SettingsAboutUi(
    val versionName: String = "0.1.0",
    val buildName: String = "MVP",
)

data class SettingsUiState(
    val persistentNotification: SettingsPersistentNotificationUi = SettingsPersistentNotificationUi(),
    val alerts: SettingsAlertsUi = SettingsAlertsUi(),
    val refresh: SettingsRefreshUi = SettingsRefreshUi(),
    val currency: SettingsCurrencyUi = SettingsCurrencyUi(),
    val appearance: SettingsAppearanceUi = SettingsAppearanceUi(),
    val data: SettingsDataUi = SettingsDataUi(),
    val diagnostics: SettingsDiagnosticsUi = SettingsDiagnosticsUi(),
    val about: SettingsAboutUi = SettingsAboutUi(),
    val update: SettingsUpdateUi = SettingsUpdateUi(),
    val pendingChoiceDialog: SettingsChoiceDialog? = null,
)

enum class SettingsBackgroundRefreshStatus(@get:StringRes val labelResId: Int) {
    NoCurrentAccount(R.string.settings_refresh_last_result_no_account),
    NoAttempts(R.string.settings_refresh_last_result_no_attempts),
    Success(R.string.settings_refresh_last_result_success),
    Failed(R.string.settings_refresh_last_result_failed),
    Retrying(R.string.settings_refresh_last_result_retrying),
    Skipped(R.string.settings_refresh_last_result_skipped),
    Cancelled(R.string.settings_refresh_last_result_cancelled),
}

fun interface SettingsBackgroundRefreshStatusReader {
    suspend fun latestBackgroundRefreshStatus(): SettingsBackgroundRefreshStatus
}

data class DiagnosticsAccountSummary(
    val providerId: String,
    val accountIdHash: String,
    val status: String,
    val lastAttemptStatus: String,
    val lastAttemptErrorCode: String? = null,
    val lastAttemptAt: String? = null,
    val lastSuccessfulRefreshAt: String? = null,
    val latestSnapshotAt: String? = null,
)

data class DiagnosticsAccountAlertConfig(
    val providerId: String,
    val accountIdHash: String,
    val enabledWindowIds: List<String>,
)

/** 结构化 WorkManager 诊断；由采集层填充，由 buildDiagnosticsCopyModel 渲染。 */
data class WorkManagerDiagnostics(
    val periodicStatus: String,
    val periodicRunAttemptCount: Int? = null,
    val periodicNextScheduleAtMillis: Long? = null,
    val periodicStopReason: String? = null,
    val onceStatus: String? = null,
)

data class SettingsDiagnosticsInput(
    val appVersion: String = "0.1.0",
    val buildType: String = "debug",
    val androidSdk: String? = null,
    val providerId: String = "codex",
    val accountDiagnosticId: String = "not_connected",
    val currentAccountSelectionStatus: String = "unknown",
    val accountStatus: String? = null,
    val accountCount: Int? = null,
    val sessionEnvelopeStatus: String? = null,
    val sessionProviderAccountIdStatus: String? = null,
    val currentState: String = "noData",
    val latestSnapshotStatus: String? = null,
    val latestSnapshotSource: String? = null,
    val latestSnapshotFetchedAt: String? = null,
    val latestSnapshotDigestStatus: String? = null,
    val lastSuccessfulRefreshAt: String = "unavailable",
    val lastAttemptStatus: String = "none",
    val lastAttemptTrigger: String? = null,
    val lastAttemptStartedAt: String? = null,
    val lastAttemptFinishedAt: String? = null,
    val safeErrorCode: String? = null,
    val httpStatus: Int? = null,
    val retryable: Boolean? = null,
    val userActionRequired: Boolean? = null,
    val diagnosticsDigest: String? = null,
    val deviceCodeLoginStatus: String? = null,
    val deviceCodeLoginAttemptId: String? = null,
    val deviceCodeLoginSafeErrorCode: String? = null,
    val deviceCodeLoginVerificationUriStatus: String? = null,
    val deviceCodeLoginPollIntervalSeconds: Int? = null,
    val deviceCodeLoginExpiresAt: String? = null,
    val workManagerStatus: String = "not_wired",
    val notificationPermissionStatus: String = "unknown",
    // A: 参考时刻
    val generatedAtMillis: Long? = null,
    // B: 运行环境
    val androidRelease: String? = null,
    val deviceModel: String? = null,
    val locale: String? = null,
    val batteryOptimizationIgnored: Boolean? = null,
    val backgroundRestricted: String? = null,
    val networkType: String? = null,
    val dataSaver: String? = null,
    val appFirstInstallAt: String? = null,
    val appLastUpdateAt: String? = null,
    // C: WorkManager 加深
    val workManagerRunAttemptCount: Int? = null,
    val workManagerNextScheduleAt: String? = null,
    val workManagerStopReason: String? = null,
    val onceWorkManagerStatus: String? = null,
    // D: 连续失败
    val consecutiveFailures: Int? = null,
    // E: 全账号摘要
    val accountSummaries: List<DiagnosticsAccountSummary> = emptyList(),
    // F: App 配置
    val roomSchemaVersion: Int? = null,
    val retentionDays: String? = null,
    val refreshIntervalMinutes: Int? = null,
    val statusNotificationEnabled: Boolean? = null,
    val quotaAlertsEnabled: Boolean? = null,
    val alertThresholds: String? = null,
    val accountAlertConfigs: List<DiagnosticsAccountAlertConfig> = emptyList(),
    val widgetCount: Int? = null,
    val unsafeDetails: String? = null,
)

data class SettingsDiagnosticsCopyModel(val text: String)

fun interface SettingsDiagnosticsReader {
    suspend fun diagnosticsInput(): SettingsDiagnosticsInput
}

object DefaultSettingsDiagnosticsReader : SettingsDiagnosticsReader {
    override suspend fun diagnosticsInput(): SettingsDiagnosticsInput = SettingsDiagnosticsInput()
}

class SettingsViewModel(
    private val accountListUseCase: AccountListUseCase = NoopAccountListUseCase,
    private val retentionPreferenceStore: RetentionPreferenceStore = InMemoryRetentionPreferenceStore(),
    private val notificationPreferenceStore: NotificationPreferenceStore = InMemoryNotificationPreferenceStore(),
    private val backgroundRefreshStatusReader: SettingsBackgroundRefreshStatusReader =
        InMemoryBackgroundRefreshStatusReader(),
    private val diagnosticsReader: SettingsDiagnosticsReader = DefaultSettingsDiagnosticsReader,
    private val quotaHistoryClearUseCase: QuotaHistoryClearUseCase = NoopQuotaHistoryClearUseCase,
    private val appUpdateChecker: AppUpdateCheckUseCase = NoopAppUpdateCheckUseCase,
    private val appUpdateDownloader: AppUpdateDownloadUseCase = NoopAppUpdateDownloadUseCase,
    private val backgroundRefreshScheduler: BackgroundRefreshScheduler = NoopBackgroundRefreshScheduler,
    private val currentVersionName: String = BuildConfig.VERSION_NAME,
    private val notificationWindowChoicesLoader: NotificationWindowChoicesLoader =
        NotificationWindowChoicesLoader { _, _ -> emptyList() },
    private val currencyPreferenceStore: CurrencyPreferenceStore = NoopCurrencyPreferenceStore,
    private val appearancePreferenceStore: AppearancePreferenceStore = NoopAppearancePreferenceStore,
    private val updatePreferenceStore: UpdatePreferenceStore = NoopUpdatePreferenceStore,
    private val updateCheckScheduler: UpdateCheckScheduler = NoopUpdateCheckScheduler,
) : ViewModel() {
    private val _uiState = MutableStateFlow(
        SettingsUiState(
            about = SettingsAboutUi(
                versionName = currentVersionName,
                buildName = BuildConfig.BUILD_TYPE,
            ),
        ),
    )
    private val notificationPreferenceWriteMutex = Mutex()
    /**
     * Guards local edits only while a notification-preferences write is in flight or completed
     * during a store read: a late-arriving read must not overwrite the edit the user just made,
     * but once writes settle the next loadSettings applies the persisted values — including
     * rebinding done elsewhere, such as the account-deletion notification fallback.
     */
    private var notificationPreferenceWritesInFlight = 0
    private var notificationPreferenceWritesCommitted = 0
    private var latestNotificationPreferences = NotificationPreferences()

    /**
     * Same late-read protection for the other persisted settings: a loadSettings read started
     * before a local write must not apply its stale result afterwards. Bumped by every writer of
     * the corresponding store (user edits and internal writes alike); the read captures the
     * counter before it starts and applies only if it still matches.
     */
    private var retentionStoreWriteVersion = 0
    private var currencyStoreWriteVersion = 0
    private var themeStoreWriteVersion = 0
    private var updatePrefsStoreWriteVersion = 0

    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    fun setStatusNotificationEnabled(enabled: Boolean) {
        val nextAlerts = _uiState.value.alerts.copy(statusNotificationEnabled = enabled)
        notificationPreferenceWritesInFlight += 1
        update { state ->
            state.copy(
                alerts = nextAlerts,
                persistentNotification = state.persistentNotification.copy(enabled = enabled),
            )
        }
        viewModelScope.launch {
            persistNotificationPreferences(releasesLocalEdit = true) { it.copy(statusNotificationEnabled = enabled) }
        }
    }

    fun setAccountErrorsEnabled(enabled: Boolean) {
        val nextAlerts = _uiState.value.alerts.copy(accountErrorsEnabled = enabled)
        notificationPreferenceWritesInFlight += 1
        update { state ->
            state.copy(alerts = nextAlerts)
        }
        viewModelScope.launch {
            persistNotificationPreferences(releasesLocalEdit = true) { it.copy(accountErrorsEnabled = enabled) }
        }
    }

    fun updateAlertThresholds(caution: Int, warning: Int) {
        val nextAlerts = _uiState.value.alerts.copy(thresholds = normalizedThresholds(caution, warning))
        notificationPreferenceWritesInFlight += 1
        update { state ->
            state.copy(alerts = nextAlerts)
        }
        viewModelScope.launch {
            persistNotificationPreferences(releasesLocalEdit = true) {
                    it.copy(
                        cautionThreshold = nextAlerts.thresholds.caution,
                        warningThreshold = nextAlerts.thresholds.warning,
                        limitThreshold = nextAlerts.thresholds.limit,
                    )
                }
        }
    }

    fun updateBalanceThresholds(caution: Double, warning: Double) {
        val (safeCaution, safeWarning) = normalizedBalanceThresholds(caution, warning)
        val nextAlerts = _uiState.value.alerts.copy(balanceCaution = safeCaution, balanceWarning = safeWarning)
        notificationPreferenceWritesInFlight += 1
        update { state -> state.copy(alerts = nextAlerts) }
        viewModelScope.launch {
            persistNotificationPreferences(releasesLocalEdit = true) { it.copy(balanceCautionThreshold = safeCaution, balanceWarningThreshold = safeWarning) }
        }
    }

    fun updatePersistentNotificationAccount(selection: SettingsNotificationAccountSelection) {
        notificationPreferenceWritesInFlight += 1
        update { state ->
            state.copy(
                persistentNotification = state.persistentNotification.copy(accountSelection = selection),
                pendingChoiceDialog = null,
            )
        }
        viewModelScope.launch {
            persistNotificationPreferences(releasesLocalEdit = true) { it.copy(persistentNotificationAccount = selection.toNotificationAccountSelection()) }
            loadAndApplyWindowChoices(selection)
        }
    }

    fun updatePersistentNotificationWindow(windowId: QuotaWindowId) {
        notificationPreferenceWritesInFlight += 1
        update { state ->
            state.copy(
                persistentNotification = state.persistentNotification.copy(selectedWindowId = windowId),
                pendingChoiceDialog = null,
            )
        }
        viewModelScope.launch {
            persistNotificationPreferences(releasesLocalEdit = true) { it.copy(persistentNotificationWindowId = windowId) }
        }
    }

    /**
     * Loads the quota windows valid for [selection]. The lookup may return after the user (or an
     * external rebind) already moved the binding elsewhere — a late result must not apply the old
     * account's windows nor persist them, so both the apply and the auto-persist re-check the
     * binding and simply drop stale results.
     */
    private suspend fun loadAndApplyWindowChoices(selection: SettingsNotificationAccountSelection?) {
        val providerId = selection?.providerId
        val localAccountId = selection?.localAccountId
        val choices = notificationWindowChoicesLoader.windowChoices(providerId, localAccountId)
            .map { it.toSettingsNotificationWindowChoice() }
        if (!_uiState.value.persistentNotification.accountSelection.sameAccountAs(selection)) return
        var needsPersist = false
        var resolvedWindowId: QuotaWindowId? = null
        update { state ->
            if (!state.persistentNotification.accountSelection.sameAccountAs(selection)) {
                return@update state
            }
            val currentId = state.persistentNotification.selectedWindowId
            val resolvedId = if (choices.isNotEmpty() && choices.none { it.windowId == currentId }) {
                choices.first().windowId
            } else {
                currentId
            }
            if (choices.isNotEmpty() && resolvedId != currentId) {
                needsPersist = true
                resolvedWindowId = resolvedId
            }
            state.copy(
                persistentNotification = state.persistentNotification.copy(
                    windowChoices = choices,
                    selectedWindowId = resolvedId,
                ),
            )
        }
        val finalWindowId = resolvedWindowId
        if (needsPersist && finalWindowId != null) {
            persistNotificationPreferences { stored ->
                // Re-check the binding inside the write transform: it may have changed (again)
                // between the UI check and this persist.
                val bound = stored.persistentNotificationAccount
                val stillBound = when {
                    selection == null -> bound == null
                    bound == null -> false
                    else -> bound.providerId == selection.providerId &&
                        bound.localAccountId == selection.localAccountId
                }
                if (stillBound) {
                    stored.copy(persistentNotificationWindowId = finalWindowId)
                } else {
                    stored
                }
            }
        }
    }

    fun setBackgroundRefreshEnabled(enabled: Boolean) {
        update { state ->
            state.copy(refresh = state.refresh.copy(backgroundRefreshEnabled = enabled))
        }
    }

    fun updateRefreshInterval(interval: SettingsRefreshInterval) {
        notificationPreferenceWritesInFlight += 1
        update { state ->
            state.copy(
                refresh = state.refresh.copy(interval = interval),
                pendingChoiceDialog = null,
            )
        }
        backgroundRefreshScheduler.applyIntervalMinutes(interval.minutes)
        viewModelScope.launch {
            persistNotificationPreferences(releasesLocalEdit = true) { it.copy(backgroundRefreshIntervalMinutes = interval.minutes) }
        }
    }

    fun updateCurrencyTarget(currency: String) {
        currencyStoreWriteVersion += 1
        update { it.copy(currency = it.currency.copy(targetCurrency = currency), pendingChoiceDialog = null) }
        viewModelScope.launch {
            currencyPreferenceStore.updateCurrencyPreferences(CurrencyPreferences(currency))
        }
    }

    fun updateThemeMode(mode: ThemeMode) {
        themeStoreWriteVersion += 1
        update { it.copy(appearance = it.appearance.copy(themeMode = mode)) }
        viewModelScope.launch {
            appearancePreferenceStore.setThemeMode(mode)
        }
    }

    fun loadSettings() {
        viewModelScope.launch {
            val retentionVersionBeforeRead = retentionStoreWriteVersion
            runCatching {
                retentionPreferenceStore.retentionPreference().toSettingsRetentionOption()
            }.onSuccess { retention ->
                if (retentionStoreWriteVersion == retentionVersionBeforeRead) {
                    update { state ->
                        state.copy(
                            data = state.data.copy(
                                retention = retention,
                            ),
                        )
                    }
                }
            }
            runCatching {
                accountListUseCase.loadAccounts()
            }.onSuccess { accountList ->
                update { state ->
                    val accountChoices = accountList.accounts
                        .filterNot { it.status == AccountStatus.Deleted }
                        .map { account ->
                            SettingsNotificationAccountChoice(
                                SettingsNotificationAccountSelection(
                                    providerId = account.providerId,
                                    localAccountId = account.localAccountId,
                                    displayName = account.displayName,
                                ),
                            )
                        }
                    state.copy(
                        persistentNotification = state.persistentNotification.copy(
                            accountChoices = accountChoices,
                            accountSelection = state.persistentNotification.accountSelection
                                .withDisplayNameFrom(accountChoices),
                        ),
                    )
                }
            }
            runCatching {
                backgroundRefreshStatusReader.latestBackgroundRefreshStatus()
            }.onSuccess { status ->
                update { state ->
                    state.copy(
                        refresh = state.refresh.copy(lastResultLabelResId = status.labelResId),
                    )
                }
            }
            val currencyVersionBeforeRead = currencyStoreWriteVersion
            runCatching {
                currencyPreferenceStore.currencyPreferences()
            }.onSuccess { currencyPreferences ->
                if (currencyStoreWriteVersion == currencyVersionBeforeRead) {
                    update { state ->
                        state.copy(
                            currency = SettingsCurrencyUi(targetCurrency = currencyPreferences.targetCurrency),
                        )
                    }
                }
            }
            val themeVersionBeforeRead = themeStoreWriteVersion
            runCatching {
                appearancePreferenceStore.themeMode.first()
            }.onSuccess { themeMode ->
                if (themeStoreWriteVersion == themeVersionBeforeRead) {
                    update { state ->
                        state.copy(appearance = state.appearance.copy(themeMode = themeMode))
                    }
                }
            }
            val writesCommittedBeforeRead = notificationPreferenceWritesCommitted
            runCatching {
                notificationPreferenceStore.notificationPreferences()
            }.onSuccess { preferences ->
                if (notificationPreferenceWritesInFlight == 0 &&
                    notificationPreferenceWritesCommitted == writesCommittedBeforeRead
                ) {
                    latestNotificationPreferences = preferences
                    update { state ->
                        val resolvedAccountSelection = preferences.persistentNotificationAccount
                            .toSettingsNotificationAccountSelection(
                                state.persistentNotification.accountChoices,
                            )
                        state.copy(
                            persistentNotification = state.persistentNotification.copy(
                                enabled = preferences.statusNotificationEnabled,
                                accountSelection = resolvedAccountSelection,
                                selectedWindowId = preferences.persistentNotificationWindowId,
                            ),
                            alerts = preferences.toSettingsAlertsUi(),
                            refresh = state.refresh.copy(
                                interval = SettingsRefreshInterval.fromMinutes(
                                    preferences.backgroundRefreshIntervalMinutes,
                                ),
                            ),
                        )
                    }
                    // Load window choices for the resolved account selection.
                    val resolvedAccountSelection = _uiState.value.persistentNotification.accountSelection
                    loadAndApplyWindowChoices(resolvedAccountSelection)
                }
            }
            val updatePrefsVersionBeforeRead = updatePrefsStoreWriteVersion
            runCatching {
                updatePreferenceStore.preferences()
            }.onSuccess { prefs ->
                if (updatePrefsStoreWriteVersion == updatePrefsVersionBeforeRead) {
                    val available = prefs.availableUpdate?.takeIf {
                        AppVersionComparator.isRemoteVersionNewer(currentVersionName, it.versionName)
                    }
                    if (available == null && prefs.availableUpdate != null) {
                        runCatching {
                            updatePrefsStoreWriteVersion += 1
                            updatePreferenceStore.setAvailableUpdate(null)
                        }
                    }
                    update { state ->
                        state.copy(
                            update = state.update.copy(
                                autoCheckEnabled = prefs.autoCheckEnabled,
                                notifyOnUpdateEnabled = prefs.notifyOnUpdateEnabled,
                                availableUpdate = available,
                            ),
                        )
                    }
                }
            }
        }
    }

    fun updateRetention(retention: SettingsRetentionOption) {
        retentionStoreWriteVersion += 1
        viewModelScope.launch {
            runCatching {
                retentionPreferenceStore.updateRetentionPreference(retention.toRetentionPreference())
            }.onSuccess {
                update { state ->
                    state.copy(
                        data = state.data.copy(retention = retention),
                        pendingChoiceDialog = null,
                    )
                }
            }
        }
    }

    fun requestChoiceDialog(dialog: SettingsChoiceDialog) {
        update { state ->
            state.copy(pendingChoiceDialog = dialog)
        }
    }

    fun dismissChoiceDialog() {
        update { state ->
            state.copy(pendingChoiceDialog = null)
        }
    }

    fun requestClearCurrentHistory() {
        update { state ->
            state.copy(data = state.data.copy(pendingAction = SettingsPendingDataAction.ClearCurrentHistory))
        }
    }

    fun requestClearAllHistory() {
        update { state ->
            state.copy(data = state.data.copy(pendingAction = SettingsPendingDataAction.ClearAllHistory))
        }
    }

    fun cancelDataAction() {
        update { state ->
            state.copy(data = state.data.copy(pendingAction = null))
        }
    }

    fun confirmDataAction() {
        val action = _uiState.value.data.pendingAction ?: return
        viewModelScope.launch {
            val result = runCatching {
                when (action) {
                    SettingsPendingDataAction.ClearCurrentHistory ->
                        quotaHistoryClearUseCase.clearCurrentAccountHistory()
                    SettingsPendingDataAction.ClearAllHistory ->
                        quotaHistoryClearUseCase.clearAllHistory()
                }
            }
            if (result.isSuccess) {
                update { state ->
                    state.copy(data = state.data.copy(pendingAction = null, confirmedAction = action))
                }
            }
        }
    }

    fun toggleDiagnosticsExpanded() {
        update { state ->
            state.copy(diagnostics = state.diagnostics.copy(isExpanded = !state.diagnostics.isExpanded))
        }
    }

    suspend fun copyDiagnostics(): SettingsDiagnosticsCopyModel =
        copyDiagnostics(diagnosticsReader.diagnosticsInput())

    fun copyDiagnostics(input: SettingsDiagnosticsInput): SettingsDiagnosticsCopyModel {
        val copyModel = buildDiagnosticsCopyModel(input)
        update { state ->
            state.copy(diagnostics = state.diagnostics.copy(copyModel = copyModel))
        }
        return copyModel
    }

    fun requestDiagnosticsRecheck() {
        viewModelScope.launch {
            val copyModel = copyDiagnostics()
            update { state ->
                state.copy(
                    diagnostics = state.diagnostics.copy(
                        copyModel = copyModel,
                        recheckRequestCount = state.diagnostics.recheckRequestCount + 1,
                    ),
                )
            }
        }
    }

    fun checkForUpdates() {
        if (_uiState.value.update.isChecking) return
        update { state ->
            state.copy(
                update = state.update.copy(
                    isChecking = true,
                    statusLabelResId = R.string.settings_update_status_checking,
                    pendingUpdate = null,
                ),
            )
        }
        viewModelScope.launch {
            val result = runCatching {
                appUpdateChecker.checkForUpdate(currentVersionName)
            }.getOrElse {
                AppUpdateCheckResult.Failure("app_update_check_failed")
            }
            update { state -> state.copy(update = result.toSettingsUpdateUi(state.update)) }
            when (result) {
                is AppUpdateCheckResult.UpdateAvailable ->
                    runCatching {
                        updatePrefsStoreWriteVersion += 1
                        updatePreferenceStore.setAvailableUpdate(result.update)
                    }
                AppUpdateCheckResult.UpToDate ->
                    runCatching {
                        updatePrefsStoreWriteVersion += 1
                        updatePreferenceStore.setAvailableUpdate(null)
                    }
                else -> Unit
            }
        }
    }

    fun dismissPendingUpdate() {
        update { state ->
            state.copy(update = state.update.copy(pendingUpdate = null))
        }
    }

    fun setAutoCheckUpdates(enabled: Boolean) {
        updatePrefsStoreWriteVersion += 1
        update { state -> state.copy(update = state.update.copy(autoCheckEnabled = enabled)) }
        updateCheckScheduler.setAutoCheckEnabled(enabled)
        viewModelScope.launch {
            runCatching { updatePreferenceStore.setAutoCheckEnabled(enabled) }
        }
    }

    fun setNotifyOnUpdate(enabled: Boolean) {
        updatePrefsStoreWriteVersion += 1
        update { state -> state.copy(update = state.update.copy(notifyOnUpdateEnabled = enabled)) }
        viewModelScope.launch {
            runCatching { updatePreferenceStore.setNotifyOnUpdateEnabled(enabled) }
        }
    }

    fun showAvailableUpdateDialog() {
        viewModelScope.launch {
            val available = runCatching { updatePreferenceStore.preferences().availableUpdate }
                .getOrNull() ?: _uiState.value.update.availableUpdate ?: return@launch
            update { state ->
                state.copy(update = state.update.copy(pendingUpdate = available))
            }
        }
    }

    fun downloadPendingUpdate() {
        val pendingUpdate = _uiState.value.update.pendingUpdate ?: return
        viewModelScope.launch {
            val result = runCatching {
                appUpdateDownloader.download(pendingUpdate)
            }.getOrElse {
                AppUpdateDownloadResult.Failure("app_update_download_failed")
            }
            update { state ->
                state.copy(update = result.toSettingsUpdateUi(state.update))
            }
        }
    }

    fun buildDiagnosticsCopyModel(input: SettingsDiagnosticsInput): SettingsDiagnosticsCopyModel {
        val now = input.generatedAtMillis
        fun ts(value: String?): String? = DiagnosticsTimeFormatter.render(value, now)
        fun age(value: String?): String? = DiagnosticsTimeFormatter.renderAgeOnly(value, now)

        val rawDiagnostics = buildString {
            appendLine("## GENERATED")
            appendLine("generatedAt=${DiagnosticsTimeFormatter.render(now?.toString(), null) ?: "unavailable"}")

            appendLine("## ENVIRONMENT")
            appendLine("appVersion=${input.appVersion}")
            appendLine("buildType=${input.buildType}")
            input.androidSdk?.let { appendLine("androidSdk=$it") }
            input.androidRelease?.let { appendLine("androidRelease=$it") }
            input.deviceModel?.let { appendLine("deviceModel=$it") }
            input.locale?.let { appendLine("locale=$it") }
            input.batteryOptimizationIgnored?.let { appendLine("batteryOptimizationIgnored=$it") }
            input.backgroundRestricted?.let { appendLine("backgroundRestricted=$it") }
            input.networkType?.let { appendLine("networkType=$it") }
            input.dataSaver?.let { appendLine("dataSaver=$it") }
            ts(input.appFirstInstallAt)?.let { appendLine("appFirstInstallAt=$it") }
            ts(input.appLastUpdateAt)?.let { appendLine("appLastUpdateAt=$it") }

            appendLine("## WORKMANAGER")
            appendLine("workManagerStatus=${input.workManagerStatus}")
            input.workManagerRunAttemptCount?.let { appendLine("workManagerRunAttemptCount=$it") }
            ts(input.workManagerNextScheduleAt)?.let { appendLine("workManagerNextScheduleAt=$it") }
            input.workManagerStopReason?.let { appendLine("workManagerStopReason=$it") }
            input.onceWorkManagerStatus?.let { appendLine("onceWorkManagerStatus=$it") }
            appendLine("notificationPermissionStatus=${input.notificationPermissionStatus}")

            appendLine("## CONFIG")
            input.roomSchemaVersion?.let { appendLine("roomSchemaVersion=$it") }
            input.retentionDays?.let { appendLine("retentionDays=$it") }
            input.refreshIntervalMinutes?.let { appendLine("refreshIntervalMinutes=$it") }
            input.statusNotificationEnabled?.let { appendLine("statusNotificationEnabled=$it") }
            input.quotaAlertsEnabled?.let { appendLine("quotaAlertsEnabled=$it") }
            input.alertThresholds?.let { appendLine("alertThresholds=$it") }
            if (input.accountAlertConfigs.isNotEmpty()) {
                appendLine("accountAlertConfigs:")
                input.accountAlertConfigs.forEach { config ->
                    appendLine(
                        "  ${config.providerId}/${config.accountIdHash}=" +
                            "[${config.enabledWindowIds.joinToString(",")}]",
                    )
                }
            }
            input.widgetCount?.let { appendLine("widgetCount=$it") }

            appendLine("## ACCOUNTS")
            input.accountSummaries.forEach { summary ->
                val attempt = if (summary.lastAttemptStatus == "none") {
                    "none"
                } else {
                    val ageText = age(summary.lastAttemptAt)
                    val parts = listOfNotNull(summary.lastAttemptErrorCode, ageText).joinToString(", ")
                    "${summary.lastAttemptStatus}($parts)"
                }
                appendLine(
                    "${summary.providerId}/${summary.accountIdHash} status=${summary.status} " +
                        "lastAttempt=$attempt lastSuccess=${age(summary.lastSuccessfulRefreshAt) ?: "unavailable"} " +
                        "snapshot=${age(summary.latestSnapshotAt) ?: "missing"}",
                )
            }

            appendLine("## DEFAULT ACCOUNT")
            appendLine("providerId=${input.providerId}")
            appendLine("accountDiagnosticId=${input.accountDiagnosticId}")
            appendLine("currentAccountSelectionStatus=${input.currentAccountSelectionStatus}")
            input.accountStatus?.let { appendLine("accountStatus=$it") }
            input.accountCount?.let { appendLine("accountCount=$it") }
            input.consecutiveFailures?.let { appendLine("consecutiveFailures=$it") }
            input.sessionEnvelopeStatus?.let { appendLine("sessionEnvelopeStatus=$it") }
            input.sessionProviderAccountIdStatus?.let { appendLine("sessionProviderAccountIdStatus=$it") }
            appendLine("currentState=${input.currentState}")
            input.latestSnapshotStatus?.let { appendLine("latestSnapshotStatus=$it") }
            input.latestSnapshotSource?.let { appendLine("latestSnapshotSource=$it") }
            ts(input.latestSnapshotFetchedAt)?.let { appendLine("latestSnapshotFetchedAt=$it") }
            input.latestSnapshotDigestStatus?.let { appendLine("latestSnapshotDigestStatus=$it") }
            appendLine("lastSuccessfulRefreshAt=${ts(input.lastSuccessfulRefreshAt) ?: input.lastSuccessfulRefreshAt}")
            appendLine("lastAttemptStatus=${input.lastAttemptStatus}")
            input.lastAttemptTrigger?.let { appendLine("lastAttemptTrigger=$it") }
            ts(input.lastAttemptStartedAt)?.let { appendLine("lastAttemptStartedAt=$it") }
            ts(input.lastAttemptFinishedAt)?.let { appendLine("lastAttemptFinishedAt=$it") }
            input.safeErrorCode?.let { appendLine("safeErrorCode=$it") }
            input.httpStatus?.let { appendLine("httpStatus=$it") }
            input.retryable?.let { appendLine("retryable=$it") }
            input.userActionRequired?.let { appendLine("userActionRequired=$it") }
            input.diagnosticsDigest?.let { appendLine("diagnosticsDigest=$it") }
            input.deviceCodeLoginStatus?.let { appendLine("deviceCodeLoginStatus=$it") }
            input.deviceCodeLoginAttemptId?.let { appendLine("deviceCodeLoginAttemptId=$it") }
            input.deviceCodeLoginSafeErrorCode?.let { appendLine("deviceCodeLoginSafeErrorCode=$it") }
            input.deviceCodeLoginVerificationUriStatus?.let { appendLine("deviceCodeLoginVerificationUriStatus=$it") }
            input.deviceCodeLoginPollIntervalSeconds?.let { appendLine("deviceCodeLoginPollIntervalSeconds=$it") }
            input.deviceCodeLoginExpiresAt?.let { appendLine("deviceCodeLoginExpiresAt=$it") }
            input.unsafeDetails?.takeIf { it.isNotBlank() }?.let { appendLine("details=[REDACTED]") }
        }.trim()

        return SettingsDiagnosticsCopyModel(text = DiagnosticsRedactor.redact(rawDiagnostics))
    }

    companion object {
        fun factory(
            accountListUseCase: AccountListUseCase = NoopAccountListUseCase,
            retentionPreferenceStore: RetentionPreferenceStore,
            notificationPreferenceStore: NotificationPreferenceStore = InMemoryNotificationPreferenceStore(),
            backgroundRefreshStatusReader: SettingsBackgroundRefreshStatusReader =
                InMemoryBackgroundRefreshStatusReader(),
            diagnosticsReader: SettingsDiagnosticsReader = DefaultSettingsDiagnosticsReader,
            quotaHistoryClearUseCase: QuotaHistoryClearUseCase = NoopQuotaHistoryClearUseCase,
            appUpdateChecker: AppUpdateCheckUseCase = NoopAppUpdateCheckUseCase,
            appUpdateDownloader: AppUpdateDownloadUseCase = NoopAppUpdateDownloadUseCase,
            backgroundRefreshScheduler: BackgroundRefreshScheduler = NoopBackgroundRefreshScheduler,
            currentVersionName: String = BuildConfig.VERSION_NAME,
            notificationWindowChoicesLoader: NotificationWindowChoicesLoader =
                NotificationWindowChoicesLoader { _, _ -> emptyList() },
            currencyPreferenceStore: CurrencyPreferenceStore = NoopCurrencyPreferenceStore,
            appearancePreferenceStore: AppearancePreferenceStore = NoopAppearancePreferenceStore,
            updatePreferenceStore: UpdatePreferenceStore = NoopUpdatePreferenceStore,
            updateCheckScheduler: UpdateCheckScheduler = NoopUpdateCheckScheduler,
        ): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    SettingsViewModel(
                        accountListUseCase = accountListUseCase,
                        retentionPreferenceStore = retentionPreferenceStore,
                        notificationPreferenceStore = notificationPreferenceStore,
                        backgroundRefreshStatusReader = backgroundRefreshStatusReader,
                        diagnosticsReader = diagnosticsReader,
                        quotaHistoryClearUseCase = quotaHistoryClearUseCase,
                        appUpdateChecker = appUpdateChecker,
                        appUpdateDownloader = appUpdateDownloader,
                        backgroundRefreshScheduler = backgroundRefreshScheduler,
                        currentVersionName = currentVersionName,
                        notificationWindowChoicesLoader = notificationWindowChoicesLoader,
                        currencyPreferenceStore = currencyPreferenceStore,
                        appearancePreferenceStore = appearancePreferenceStore,
                        updatePreferenceStore = updatePreferenceStore,
                        updateCheckScheduler = updateCheckScheduler,
                    ) as T
            }
    }

    private fun update(transform: (SettingsUiState) -> SettingsUiState) {
        _uiState.value = transform(_uiState.value)
    }

    private fun normalizedThresholds(caution: Int, warning: Int): SettingsAlertThresholdsUi {
        val safeCaution = caution.coerceIn(CAUTION_MIN, CAUTION_MAX)
        val safeWarning = warning.coerceIn(WARNING_MIN, minOf(WARNING_MAX, safeCaution - 1))
        return SettingsAlertThresholdsUi(caution = safeCaution, warning = safeWarning)
    }

    private fun normalizedBalanceThresholds(caution: Double, warning: Double): Pair<Double, Double> {
        val safeCaution = caution.coerceIn(BALANCE_MIN, BALANCE_MAX)
        // Warning (urgent) must stay at or below caution.
        val safeWarning = warning.coerceIn(BALANCE_MIN, safeCaution)
        return safeCaution to safeWarning
    }

    /**
     * [releasesLocalEdit] must be true exactly for writes that incremented
     * [notificationPreferenceWritesInFlight] at the call site — internal follow-up writes (like
     * the window re-resolution persist) must not consume a user edit's guard slot.
     */
    private suspend fun persistNotificationPreferences(
        releasesLocalEdit: Boolean = false,
        transform: (NotificationPreferences) -> NotificationPreferences,
    ) {
        notificationPreferenceWriteMutex.withLock {
            try {
                val nextPreferences = transform(latestNotificationPreferences)
                runCatching {
                    notificationPreferenceStore.updateNotificationPreferences(nextPreferences)
                }.onSuccess {
                    latestNotificationPreferences = nextPreferences
                    notificationPreferenceWritesCommitted += 1
                }
            } finally {
                if (releasesLocalEdit && notificationPreferenceWritesInFlight > 0) {
                    notificationPreferenceWritesInFlight -= 1
                }
            }
        }
    }
}

private fun AppUpdateCheckResult.toSettingsUpdateUi(previous: SettingsUpdateUi): SettingsUpdateUi =
    when (this) {
        is AppUpdateCheckResult.UpdateAvailable -> previous.copy(
            isChecking = false,
            statusLabelResId = R.string.settings_update_status_update_available,
            pendingUpdate = update,
            availableUpdate = update,
        )
        AppUpdateCheckResult.UpToDate -> previous.copy(
            isChecking = false,
            statusLabelResId = R.string.settings_update_status_latest,
            pendingUpdate = null,
            availableUpdate = null,
        )
        AppUpdateCheckResult.NoRelease -> previous.copy(
            isChecking = false,
            statusLabelResId = R.string.settings_update_status_no_release,
        )
        AppUpdateCheckResult.NoApkAsset -> previous.copy(
            isChecking = false,
            statusLabelResId = R.string.settings_update_status_no_apk,
        )
        is AppUpdateCheckResult.Failure -> previous.copy(
            isChecking = false,
            statusLabelResId = R.string.settings_update_status_failed,
        )
    }

private fun AppUpdateDownloadResult.toSettingsUpdateUi(previous: SettingsUpdateUi): SettingsUpdateUi =
    when (this) {
        is AppUpdateDownloadResult.Enqueued -> previous.copy(
            isChecking = false,
            statusLabelResId = R.string.settings_update_status_downloading,
            pendingUpdate = null,
        )
        is AppUpdateDownloadResult.Failure -> previous.copy(
            isChecking = false,
            statusLabelResId = R.string.settings_update_status_download_failed,
            pendingUpdate = null,
        )
    }

private fun RetentionPreference.toSettingsRetentionOption(): SettingsRetentionOption =
    when (this) {
        RetentionPreference.SevenDays -> SettingsRetentionOption.SevenDays
        RetentionPreference.ThirtyDays -> SettingsRetentionOption.ThirtyDays
        RetentionPreference.NinetyDays -> SettingsRetentionOption.NinetyDays
        RetentionPreference.Forever -> SettingsRetentionOption.Forever
    }

private fun SettingsRetentionOption.toRetentionPreference(): RetentionPreference =
    when (this) {
        SettingsRetentionOption.SevenDays -> RetentionPreference.SevenDays
        SettingsRetentionOption.ThirtyDays -> RetentionPreference.ThirtyDays
        SettingsRetentionOption.NinetyDays -> RetentionPreference.NinetyDays
        SettingsRetentionOption.Forever -> RetentionPreference.Forever
    }

private fun NotificationWindowChoice.toSettingsNotificationWindowChoice(): SettingsNotificationWindowChoice =
    SettingsNotificationWindowChoice(
        windowId = windowId,
        labelResId = quotaWindowLabelRes(windowId.value, limitWindowSeconds),
    )

private fun SettingsNotificationAccountSelection?.sameAccountAs(
    other: SettingsNotificationAccountSelection?,
): Boolean = when {
    this == null -> other == null
    other == null -> false
    else -> providerId == other.providerId && localAccountId == other.localAccountId
}

private fun SettingsNotificationAccountSelection?.toNotificationAccountSelection(): NotificationAccountSelection? =
    this?.let {
        NotificationAccountSelection(
            providerId = it.providerId,
            localAccountId = it.localAccountId,
        )
    }

private fun NotificationAccountSelection?.toSettingsNotificationAccountSelection(
    choices: List<SettingsNotificationAccountChoice>,
): SettingsNotificationAccountSelection? {
    this ?: return null
    return choices.map { it.selection }
        .firstOrNull { it.providerId == providerId && it.localAccountId == localAccountId }
        ?: SettingsNotificationAccountSelection(
            providerId = providerId,
            localAccountId = localAccountId,
            displayName = localAccountId.value,
        )
}

private fun SettingsNotificationAccountSelection?.withDisplayNameFrom(
    choices: List<SettingsNotificationAccountChoice>,
): SettingsNotificationAccountSelection? =
    this?.let { selected ->
        choices.map { it.selection }
            .firstOrNull {
                it.providerId == selected.providerId && it.localAccountId == selected.localAccountId
            } ?: selected
    }

private fun NotificationPreferences.toSettingsAlertsUi(): SettingsAlertsUi =
    SettingsAlertsUi(
        statusNotificationEnabled = statusNotificationEnabled,
        quotaAlertsEnabled = quotaAlertsEnabled,
        accountErrorsEnabled = accountErrorsEnabled,
        thresholds = SettingsAlertThresholdsUi(
            caution = cautionThreshold,
            warning = warningThreshold,
            limit = limitThreshold,
        ),
        balanceCaution = balanceCautionThreshold,
        balanceWarning = balanceWarningThreshold,
    )

internal class InMemoryRetentionPreferenceStore : RetentionPreferenceStore {
    private var preference = RetentionPreference.ThirtyDays

    override suspend fun retentionPreference(): RetentionPreference = preference

    override suspend fun updateRetentionPreference(preference: RetentionPreference) {
        this.preference = preference
    }
}

internal class InMemoryNotificationPreferenceStore : NotificationPreferenceStore {
    private var preferences = NotificationPreferences()

    override suspend fun notificationPreferences(): NotificationPreferences = preferences

    override suspend fun updateNotificationPreferences(preferences: NotificationPreferences) {
        this.preferences = preferences
    }
}

internal class InMemoryBackgroundRefreshStatusReader : SettingsBackgroundRefreshStatusReader {
    override suspend fun latestBackgroundRefreshStatus(): SettingsBackgroundRefreshStatus =
        SettingsBackgroundRefreshStatus.NoAttempts
}

private const val CAUTION_MIN = 2
private const val CAUTION_MAX = 99
private const val WARNING_MIN = 1
private const val WARNING_MAX = 98
private const val BALANCE_MIN = 0.0
private const val BALANCE_MAX = 1000.0
