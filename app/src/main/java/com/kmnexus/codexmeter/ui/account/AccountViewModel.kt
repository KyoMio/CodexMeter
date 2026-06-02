package com.kmnexus.codexmeter.ui.account

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.kmnexus.codexmeter.R
import com.kmnexus.codexmeter.domain.account.AccountDeleteUseCase
import com.kmnexus.codexmeter.domain.account.AccountListResult
import com.kmnexus.codexmeter.domain.account.AccountListUseCase
import com.kmnexus.codexmeter.domain.account.AccountRenameUseCase
import com.kmnexus.codexmeter.domain.account.AccountSwitchUseCase
import com.kmnexus.codexmeter.domain.account.NoopAccountDeleteUseCase
import com.kmnexus.codexmeter.domain.account.NoopAccountListUseCase
import com.kmnexus.codexmeter.domain.account.NoopAccountRenameUseCase
import com.kmnexus.codexmeter.domain.account.NoopAccountSwitchUseCase
import com.kmnexus.codexmeter.domain.model.AccountStatus
import com.kmnexus.codexmeter.domain.model.LocalAccountId
import com.kmnexus.codexmeter.domain.model.ProviderAccount
import com.kmnexus.codexmeter.providers.ProviderRegistry
import com.kmnexus.codexmeter.domain.model.ProviderId
import com.kmnexus.codexmeter.domain.model.QuotaWindowId
import com.kmnexus.codexmeter.domain.quota.Credits
import com.kmnexus.codexmeter.domain.quota.QuotaSnapshot
import com.kmnexus.codexmeter.domain.quota.QuotaWindow
import com.kmnexus.codexmeter.domain.quota.QuotaWindowAvailability
import com.kmnexus.codexmeter.domain.quota.QuotaWindowDisplayKind
import kotlin.math.roundToInt
import com.kmnexus.codexmeter.data.currency.ExchangeRateReader
import com.kmnexus.codexmeter.domain.currency.CurrencyPreferenceReader
import com.kmnexus.codexmeter.domain.currency.CurrencyPreferences
import com.kmnexus.codexmeter.domain.currency.ExchangeRates
import com.kmnexus.codexmeter.domain.currency.withConvertedBalance
import com.kmnexus.codexmeter.ui.quota.formatProviderBalance
import com.kmnexus.codexmeter.domain.settings.NotificationPreferenceStore
import com.kmnexus.codexmeter.domain.settings.NotificationPreferences
import com.kmnexus.codexmeter.ui.providerPlanDisplayName
import com.kmnexus.codexmeter.ui.quota.quotaWindowLabelRes
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class AccountContentStatus {
    Unauthenticated,
    HasAccounts,
}

enum class AccountStatusTone {
    Neutral,
    Success,
    Warning,
    Danger,
}

enum class AccountAddAccountIntent {
    LoginToCodex,
}

/** A one-shot request to re-authenticate a specific saved account, consumed by the route to navigate. */
data class AccountReloginRequest(
    val providerId: ProviderId,
    val localAccountId: LocalAccountId,
    /** The provider's account identity (Codex chatgpt_account_id); null when the provider has none. */
    val providerAccountId: String?,
)

data class AccountQuotaSummaryUi(
    val windowId: String,
    @get:StringRes val labelResId: Int,
    val percent: Int?,
    val valueText: String? = null,
    /** Non-null when the balance was converted: shows the provider-native amount. */
    val originalValueText: String? = null,
    /** Non-null for DeepSeek-style granted/topped-up breakdown (native currency). */
    val grantedText: String? = null,
    val toppedUpText: String? = null,
)

/** A per-window quota alert toggle (label + current on/off state). */
data class AccountAlertToggleUi(
    val windowId: String,
    @get:StringRes val labelResId: Int,
    val enabled: Boolean,
)

/** A single model's remaining quota inside a multi-model provider (MiniMax / Antigravity). */
data class AccountModelUi(
    val displayName: String,
    val remainingPercent: Int,
)

/** A group of models sharing a family (Claude / Gemini Pro / Gemini Flash / GPT-OSS). */
data class AccountModelFamilyUi(
    val name: String,
    val models: List<AccountModelUi>,
)

sealed class AccountCreditsUi {
    data class Balance(val amount: Double) : AccountCreditsUi()
    object Unlimited : AccountCreditsUi()
    object Unavailable : AccountCreditsUi()
}

data class AccountItemUi(
    val id: LocalAccountId,
    val displayName: String,
    val avatarInitial: String,
    val avatarColorKey: String,
    val isCurrent: Boolean,
    val status: AccountStatus,
    val tone: AccountStatusTone,
    @get:StringRes val badgeLabelResId: Int,
    @get:StringRes val statusLabelResId: Int,
    @get:StringRes val refreshSummaryResId: Int,
    val lastSuccessfulRefreshAt: Instant?,
    val planType: String?,
    val credits: AccountCreditsUi,
    val isExpanded: Boolean,
    val alertToggles: List<AccountAlertToggleUi>,
    val quotaSummaries: List<AccountQuotaSummaryUi>,
    val modelFamilies: List<AccountModelFamilyUi> = emptyList(),
    @param:DrawableRes val providerIconResId: Int? = null,
)

data class AccountDeleteConfirmationUi(
    val accountId: LocalAccountId,
    val displayName: String,
)

data class AccountRenameUi(
    val accountId: LocalAccountId,
    val displayName: String,
)

data class AccountUiState(
    val contentStatus: AccountContentStatus,
    val currentAccountId: LocalAccountId?,
    val currentAccount: AccountItemUi?,
    val accounts: List<AccountItemUi>,
    val showAddAccountSheet: Boolean,
    val selectedAddAccountIntent: AccountAddAccountIntent?,
    val pendingReloginRequest: AccountReloginRequest?,
    val pendingDeleteAccount: AccountDeleteConfirmationUi?,
    val pendingRenameAccount: AccountRenameUi?,
    val isRefreshing: Boolean = false,
) {
    companion object {
        val Empty = AccountUiState(
            contentStatus = AccountContentStatus.Unauthenticated,
            currentAccountId = null,
            currentAccount = null,
            accounts = emptyList(),
            showAddAccountSheet = false,
            selectedAddAccountIntent = null,
            pendingReloginRequest = null,
            pendingDeleteAccount = null,
            pendingRenameAccount = null,
        )
    }
}

class AccountViewModel(
    private val deleteUseCase: AccountDeleteUseCase,
    private val accountListUseCase: AccountListUseCase,
    private val switchUseCase: AccountSwitchUseCase,
    private val renameUseCase: AccountRenameUseCase,
    private val notificationPreferenceStore: NotificationPreferenceStore = AccountInMemoryNotificationPreferenceStore(),
    private val quotaAlertEvaluationRequester: AccountQuotaAlertEvaluationRequester =
        NoopAccountQuotaAlertEvaluationRequester,
    private val refreshAllUseCase: AccountRefreshAllUseCase = NoopAccountRefreshAllUseCase,
    private val currencyPreferenceReader: CurrencyPreferenceReader = NoopAccountCurrencyPreferenceReader,
    private val exchangeRateReader: ExchangeRateReader = NoopAccountExchangeRateReader,
) : ViewModel() {
    private val _uiState = MutableStateFlow(AccountUiState.Empty)
    private var isRefreshing = false
    private var accounts: List<ProviderAccount> = emptyList()
    private var latestQuotaSnapshots: Map<LocalAccountId, QuotaSnapshot> = emptyMap()
    private var notificationPreferences = NotificationPreferences()
    private var currentAccountId: LocalAccountId? = null
    private var showAddAccountSheet = false
    private var selectedAddAccountIntent: AccountAddAccountIntent? = null
    private var pendingReloginRequest: AccountReloginRequest? = null
    private var pendingDeleteAccountId: LocalAccountId? = null
    private var pendingRenameAccountId: LocalAccountId? = null
    private var expandedAccountIds: Set<LocalAccountId> = emptySet()
    private var targetCurrency: String = CurrencyPreferences.DEFAULT_TARGET_CURRENCY
    private var exchangeRates: ExchangeRates? = null

    val uiState: StateFlow<AccountUiState> = _uiState.asStateFlow()

    fun requestAddAccount() {
        showAddAccountSheet = true
        selectedAddAccountIntent = null
        publishState()
    }

    fun dismissAddAccount() {
        showAddAccountSheet = false
        selectedAddAccountIntent = null
        publishState()
    }

    fun selectAddAccountLogin() {
        showAddAccountSheet = false
        selectedAddAccountIntent = AccountAddAccountIntent.LoginToCodex
        publishState()
    }


    fun selectRelogin(id: LocalAccountId) {
        val account = accounts.firstOrNull { it.localAccountId == id } ?: return

        showAddAccountSheet = false
        pendingReloginRequest = AccountReloginRequest(
            providerId = account.providerId,
            localAccountId = account.localAccountId,
            providerAccountId = account.providerAccountId?.value,
        )
        publishState()
    }

    /** Clears the one-shot re-login request once the route has navigated. */
    fun consumeReloginRequest() {
        if (pendingReloginRequest == null) return
        pendingReloginRequest = null
        publishState()
    }

    fun setAccounts(
        accounts: List<ProviderAccount>,
        currentAccountId: LocalAccountId?,
        latestQuotaSnapshots: Map<LocalAccountId, QuotaSnapshot> = emptyMap(),
    ) {
        this.accounts = accounts
        this.latestQuotaSnapshots = latestQuotaSnapshots
        this.currentAccountId = currentAccountId.takeIf { id -> accounts.any { it.localAccountId == id } }
            ?: accounts.firstOrNull()?.localAccountId
        pendingDeleteAccountId = pendingDeleteAccountId.takeIf { id -> accounts.any { it.localAccountId == id } }
        pendingRenameAccountId = pendingRenameAccountId.takeIf { id -> accounts.any { it.localAccountId == id } }
        expandedAccountIds = expandedAccountIds.filterTo(mutableSetOf()) { id ->
            accounts.any { it.localAccountId == id }
        }
        publishState()
    }

    suspend fun loadAccounts() {
        val result = accountListUseCase.loadAccounts()
        notificationPreferences = notificationPreferenceStore.notificationPreferences()
        targetCurrency = runCatching { currencyPreferenceReader.currencyPreferences().targetCurrency }
            .getOrDefault(CurrencyPreferences.DEFAULT_TARGET_CURRENCY)
        exchangeRates = runCatching { exchangeRateReader.currentRates() }.getOrNull()
        setAccounts(
            accounts = result.accounts,
            currentAccountId = result.currentAccountId,
            latestQuotaSnapshots = result.latestQuotaSnapshots,
        )
    }

    /** Pull-to-refresh: refresh every connected account in parallel, then reload the list. */
    fun refreshAllAccounts() {
        if (isRefreshing) return
        isRefreshing = true
        publishState()
        viewModelScope.launch {
            try {
                runCatching { refreshAllUseCase.refreshAll() }
                loadAccounts()
            } finally {
                isRefreshing = false
                publishState()
            }
        }
    }

    suspend fun switchCurrentAccount(id: LocalAccountId) {
        val account = accounts.firstOrNull { it.localAccountId == id } ?: return

        val switched = try {
            switchUseCase.switchCurrentAccount(
                providerId = account.providerId,
                localAccountId = account.localAccountId,
            )
        } catch (exception: CancellationException) {
            throw exception
        } catch (_: Exception) {
            false
        }
        if (!switched) return

        currentAccountId = id
        publishState()
    }

    suspend fun renameAccount(id: LocalAccountId, newName: String) {
        val safeName = newName.trim()
        if (safeName.isEmpty()) return
        val accountToRename = accounts.firstOrNull { it.localAccountId == id } ?: return

        val renamedAccount = try {
            renameUseCase.renameAccount(
                providerId = accountToRename.providerId,
                localAccountId = accountToRename.localAccountId,
                displayName = safeName,
            )
        } catch (exception: CancellationException) {
            throw exception
        } catch (_: Exception) {
            null
        }
        if (renamedAccount == null) {
            publishState()
            return
        }

        accounts = accounts.map { account ->
            if (account.localAccountId == id) renamedAccount else account
        }
        if (pendingRenameAccountId == id) {
            pendingRenameAccountId = null
        }
        publishState()
    }

    fun requestRenameAccount(id: LocalAccountId) {
        if (accounts.none { it.localAccountId == id }) return

        pendingRenameAccountId = id
        publishState()
    }

    fun cancelRename() {
        pendingRenameAccountId = null
        publishState()
    }

    fun requestDeleteAccount(id: LocalAccountId) {
        if (accounts.none { it.localAccountId == id }) return

        pendingDeleteAccountId = id
        publishState()
    }

    fun cancelDelete() {
        pendingDeleteAccountId = null
        publishState()
    }

    fun toggleAccountExpanded(id: LocalAccountId) {
        if (accounts.none { it.localAccountId == id }) return

        expandedAccountIds = if (id in expandedAccountIds) {
            expandedAccountIds - id
        } else {
            expandedAccountIds + id
        }
        publishState()
    }

    suspend fun setQuotaAlertEnabled(
        id: LocalAccountId,
        windowId: QuotaWindowId,
        enabled: Boolean,
    ) {
        val account = accounts.firstOrNull { it.localAccountId == id } ?: return
        val latestPreferences = try {
            notificationPreferenceStore.notificationPreferences()
        } catch (exception: CancellationException) {
            throw exception
        } catch (_: Exception) {
            notificationPreferences
        }
        val updatedPreferences = latestPreferences.withAccountQuotaAlertEnabled(
            providerId = account.providerId,
            localAccountId = account.localAccountId,
            windowId = windowId,
            enabled = enabled,
        )

        try {
            notificationPreferenceStore.updateNotificationPreferences(updatedPreferences)
        } catch (exception: CancellationException) {
            throw exception
        } catch (_: Exception) {
            return
        }

        notificationPreferences = updatedPreferences
        publishState()
        if (enabled) {
            try {
                quotaAlertEvaluationRequester.requestQuotaAlertEvaluation(
                    providerId = account.providerId,
                    localAccountId = account.localAccountId,
                    windowId = windowId,
                )
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: Exception) {
                // Alert evaluation is best-effort; the persisted setting will be honored on the next refresh.
            }
        }
    }

    suspend fun confirmDelete() {
        val accountId = pendingDeleteAccountId ?: return
        val accountToDelete = accounts.firstOrNull { it.localAccountId == accountId }
        if (accountToDelete == null) {
            pendingDeleteAccountId = null
            publishState()
            return
        }

        try {
            deleteUseCase.deleteAccount(
                providerId = accountToDelete.providerId,
                localAccountId = accountToDelete.localAccountId,
            )
        } catch (exception: CancellationException) {
            throw exception
        } catch (_: Exception) {
            publishState()
            return
        }

        accounts = accounts.filterNot { it.localAccountId == accountId }
        pendingDeleteAccountId = null
        if (pendingRenameAccountId == accountId) {
            pendingRenameAccountId = null
        }
        expandedAccountIds = expandedAccountIds - accountId
        if (currentAccountId == accountId || accounts.none { it.localAccountId == currentAccountId }) {
            currentAccountId = accounts.firstOrNull()?.localAccountId
        }
        publishState()
    }

    companion object {
        // Up to three windows so providers with a third surface (Claude 5h/7d/extra-usage,
        // z.ai 5h/weekly/MCP) show it in the account expanded view.
        private const val MAX_SUMMARY_WINDOWS = 3

        fun factory(
            deleteUseCase: AccountDeleteUseCase,
            accountListUseCase: AccountListUseCase,
            switchUseCase: AccountSwitchUseCase = NoopAccountSwitchUseCase,
            renameUseCase: AccountRenameUseCase = NoopAccountRenameUseCase,
            notificationPreferenceStore: NotificationPreferenceStore = AccountInMemoryNotificationPreferenceStore(),
            quotaAlertEvaluationRequester: AccountQuotaAlertEvaluationRequester =
                NoopAccountQuotaAlertEvaluationRequester,
            refreshAllUseCase: AccountRefreshAllUseCase = NoopAccountRefreshAllUseCase,
            currencyPreferenceReader: CurrencyPreferenceReader = NoopAccountCurrencyPreferenceReader,
            exchangeRateReader: ExchangeRateReader = NoopAccountExchangeRateReader,
        ): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    AccountViewModel(
                        deleteUseCase = deleteUseCase,
                        accountListUseCase = accountListUseCase,
                        switchUseCase = switchUseCase,
                        renameUseCase = renameUseCase,
                        notificationPreferenceStore = notificationPreferenceStore,
                        quotaAlertEvaluationRequester = quotaAlertEvaluationRequester,
                        refreshAllUseCase = refreshAllUseCase,
                        currencyPreferenceReader = currencyPreferenceReader,
                        exchangeRateReader = exchangeRateReader,
                    ) as T
            }
    }

    private fun publishState() {
        val accountItems = accounts.map {
            it.toUiItem(
                isCurrent = it.localAccountId == currentAccountId,
                latestQuotaSnapshot = latestQuotaSnapshots[it.localAccountId],
                notificationPreferences = notificationPreferences,
                isExpanded = it.localAccountId in expandedAccountIds,
            )
        }
        _uiState.value = AccountUiState(
            contentStatus = if (accountItems.isEmpty()) {
                AccountContentStatus.Unauthenticated
            } else {
                AccountContentStatus.HasAccounts
            },
            currentAccountId = currentAccountId,
            currentAccount = accountItems.firstOrNull { it.id == currentAccountId },
            accounts = accountItems,
            showAddAccountSheet = showAddAccountSheet,
            selectedAddAccountIntent = selectedAddAccountIntent,
            pendingReloginRequest = pendingReloginRequest,
            pendingDeleteAccount = pendingDeleteAccountId?.let { id ->
                accountItems.firstOrNull { it.id == id }?.let { account ->
                    AccountDeleteConfirmationUi(accountId = account.id, displayName = account.displayName)
                }
            },
            pendingRenameAccount = pendingRenameAccountId?.let { id ->
                accountItems.firstOrNull { it.id == id }?.let { account ->
                    AccountRenameUi(accountId = account.id, displayName = account.displayName)
                }
            },
            isRefreshing = isRefreshing,
        )
    }

    private fun ProviderAccount.toUiItem(
        isCurrent: Boolean,
        latestQuotaSnapshot: QuotaSnapshot?,
        notificationPreferences: NotificationPreferences,
        isExpanded: Boolean,
    ): AccountItemUi {
        val tone = status.toTone()
        return AccountItemUi(
            id = localAccountId,
            displayName = displayName,
            avatarInitial = avatarInitial,
            avatarColorKey = avatarColorKey,
            isCurrent = isCurrent,
            status = status,
            tone = tone,
            badgeLabelResId = badgeLabelResId(isCurrent = isCurrent),
            statusLabelResId = status.labelResId(),
            refreshSummaryResId = if (lastSuccessfulRefreshAt == null) {
                R.string.account_last_refresh_unavailable
            } else {
                R.string.account_last_refresh_format
            },
            lastSuccessfulRefreshAt = lastSuccessfulRefreshAt,
            planType = providerPlanDisplayName(providerId, latestQuotaSnapshot?.planType),
            credits = latestQuotaSnapshot?.credits.toUiCredits(),
            isExpanded = isExpanded,
            alertToggles = buildAlertToggles(latestQuotaSnapshot, notificationPreferences),
            quotaSummaries = buildQuotaSummaries(latestQuotaSnapshot),
            modelFamilies = buildModelFamilies(providerId, latestQuotaSnapshot),
            providerIconResId = ProviderRegistry.iconFor(providerId),
        )
    }

    private fun buildModelFamilies(providerId: ProviderId, snapshot: QuotaSnapshot?): List<AccountModelFamilyUi> {
        val buckets = snapshot?.windows
            ?.filter { it.displayKind == QuotaWindowDisplayKind.MultiModelFraction }
            ?.flatMap { it.modelBuckets }
            ?.filter { it.resetAt != null } // hide internal/code-completion models (chat_*, tab_*)
            .orEmpty()
        if (buckets.isEmpty()) return emptyList()
        return buckets
            .groupBy { modelFamilyOf(providerId, it.modelId) }
            .filterKeys { it != null }
            .map { (family, familyBuckets) ->
                AccountModelFamilyUi(
                    name = family!!,
                    models = familyBuckets
                        .sortedBy { it.displayName }
                        .map { bucket ->
                            AccountModelUi(
                                displayName = bucket.displayName,
                                remainingPercent = (bucket.remainingFraction * 100).roundToInt().coerceIn(0, 100),
                            )
                        },
                )
            }
    }

    /** Classifies a model id into a display family; null hides it. Rules differ per provider. */
    private fun modelFamilyOf(providerId: ProviderId, modelId: String): String? {
        val id = modelId.lowercase()
        return when (providerId) {
            ProviderRegistry.MINIMAX -> miniMaxModelFamily(id)
            else -> antigravityModelFamily(id)
        }
    }

    private fun antigravityModelFamily(id: String): String? =
        when {
            id.contains("agent") || id.contains("image") -> null
            id.startsWith("chat_") || id.startsWith("tab_") -> null
            id.startsWith("claude") -> "Claude"
            id.startsWith("gpt-oss") -> "GPT-OSS"
            id.contains("gemini") && id.contains("pro") -> "Gemini Pro"
            id.contains("gemini") && id.contains("flash") -> "Gemini Flash"
            else -> null
        }

    /** MiniMax surfaces every non-text-gen model, mirroring CodexBar's service categories. */
    private fun miniMaxModelFamily(id: String): String =
        when {
            id.contains("speech") || id.contains("tts") -> "Speech"
            id.contains("hailuo") || id.contains("video") -> "Video"
            id.contains("image") -> "Image"
            id.contains("music") || id.contains("lyrics") -> "Music"
            else -> "Other"
        }

    private fun displayableWindows(snapshot: QuotaSnapshot?): List<QuotaWindow> =
        snapshot?.windows.orEmpty().filter {
            it.availability == QuotaWindowAvailability.Available &&
                (it.displayPercent != null || it.balanceAmount != null || (it.usedCount != null && it.limitCount != null))
        }

    private fun buildQuotaSummaries(snapshot: QuotaSnapshot?): List<AccountQuotaSummaryUi> =
        displayableWindows(snapshot).take(MAX_SUMMARY_WINDOWS).map { window ->
            val converted = window.withConvertedBalance(targetCurrency, exchangeRates)
            val nativeCurrency = converted.originalBalanceCurrency ?: converted.balanceCurrency
            AccountQuotaSummaryUi(
                windowId = converted.windowId.value,
                labelResId = quotaWindowLabelRes(converted.windowId.value),
                percent = if (converted.displayKind == QuotaWindowDisplayKind.Balance) null else converted.displayPercent,
                valueText = converted.summaryValueText(),
                originalValueText = formatProviderBalance(converted.originalBalanceAmount, converted.originalBalanceCurrency),
                grantedText = formatProviderBalance(converted.grantedBalance, nativeCurrency),
                toppedUpText = formatProviderBalance(converted.toppedUpBalance, nativeCurrency),
            )
        }

    private fun ProviderAccount.buildAlertToggles(
        snapshot: QuotaSnapshot?,
        notificationPreferences: NotificationPreferences,
    ): List<AccountAlertToggleUi> =
        displayableWindows(snapshot).take(MAX_SUMMARY_WINDOWS).map { window ->
            AccountAlertToggleUi(
                windowId = window.windowId.value,
                labelResId = quotaWindowLabelRes(window.windowId.value),
                enabled = notificationPreferences.isQuotaAlertEnabled(
                    providerId = providerId,
                    localAccountId = localAccountId,
                    windowId = window.windowId,
                ),
            )
        }

    private fun QuotaWindow.summaryValueText(): String? =
        when {
            // Claude extra-usage is a spend meter: show the "$used / $limit" figure (carried in
            // subLabel) instead of a remaining-percent, which would read as ambiguous here.
            windowId.value == "claude_extra_usage" -> subLabel
            displayKind == QuotaWindowDisplayKind.Balance -> formatProviderBalance(balanceAmount, balanceCurrency)
            displayKind == QuotaWindowDisplayKind.UsageCount ->
                if (usedCount != null && limitCount != null) "$usedCount / $limitCount" else null
            else -> null
        }

    @StringRes
    private fun ProviderAccount.badgeLabelResId(isCurrent: Boolean): Int =
        when {
            isCurrent -> R.string.account_badge_current
            status == AccountStatus.NeedsReauth -> R.string.account_badge_needs_reauth
            status == AccountStatus.Disabled -> R.string.account_badge_disabled
            else -> R.string.account_badge_available
        }

    @StringRes
    private fun AccountStatus.labelResId(): Int =
        when (this) {
            AccountStatus.Active -> R.string.account_status_active
            AccountStatus.NeedsReauth -> R.string.account_status_needs_reauth
            AccountStatus.Disabled -> R.string.account_status_disabled
            AccountStatus.Deleted -> R.string.account_status_deleted
        }

    private fun AccountStatus.toTone(): AccountStatusTone =
        when (this) {
            AccountStatus.Active -> AccountStatusTone.Success
            AccountStatus.NeedsReauth -> AccountStatusTone.Warning
            AccountStatus.Disabled -> AccountStatusTone.Neutral
            AccountStatus.Deleted -> AccountStatusTone.Danger
        }


    private fun Credits?.toUiCredits(): AccountCreditsUi =
        when {
            this == null || !hasCredits -> AccountCreditsUi.Unavailable
            unlimited -> AccountCreditsUi.Unlimited
            balance != null -> AccountCreditsUi.Balance(balance)
            else -> AccountCreditsUi.Unavailable
        }

}

internal object NoopAccountCurrencyPreferenceReader : CurrencyPreferenceReader {
    override suspend fun currencyPreferences() = CurrencyPreferences()
}

internal object NoopAccountExchangeRateReader : ExchangeRateReader {
    override suspend fun currentRates() = null
}

fun interface AccountQuotaAlertEvaluationRequester {
    suspend fun requestQuotaAlertEvaluation(
        providerId: ProviderId,
        localAccountId: LocalAccountId,
        windowId: QuotaWindowId,
    )
}

object NoopAccountQuotaAlertEvaluationRequester : AccountQuotaAlertEvaluationRequester {
    override suspend fun requestQuotaAlertEvaluation(
        providerId: ProviderId,
        localAccountId: LocalAccountId,
        windowId: QuotaWindowId,
    ) = Unit
}

/** Refreshes every connected account's quota in parallel (pull-to-refresh on the Account screen). */
fun interface AccountRefreshAllUseCase {
    suspend fun refreshAll()
}

object NoopAccountRefreshAllUseCase : AccountRefreshAllUseCase {
    override suspend fun refreshAll() = Unit
}

internal class AccountInMemoryNotificationPreferenceStore : NotificationPreferenceStore {
    private var preferences = NotificationPreferences()

    override suspend fun notificationPreferences(): NotificationPreferences = preferences

    override suspend fun updateNotificationPreferences(preferences: NotificationPreferences) {
        this.preferences = preferences
    }
}
