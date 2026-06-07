package com.kmnexus.codexmeter.widget

import com.kmnexus.codexmeter.domain.quota.CurrentQuotaState
import com.kmnexus.codexmeter.domain.quota.CurrentQuotaStatus
import com.kmnexus.codexmeter.domain.quota.QuotaWindow
import com.kmnexus.codexmeter.domain.quota.QuotaWindowAvailability
import com.kmnexus.codexmeter.domain.quota.QuotaWindowDisplayKind
import com.kmnexus.codexmeter.domain.settings.NotificationPreferences
import com.kmnexus.codexmeter.providers.ProviderRegistry

class WidgetQuotaStateFactory(
    private val providerDisplayName: String = UNCONFIGURED_HEADER,
) {
    /** 微件未选账号时的引导态。 */
    fun unconfigured(hasAccounts: Boolean): WidgetQuotaState =
        WidgetQuotaState(
            status = WidgetQuotaStatus.NoAccount,
            providerName = providerDisplayName,
            providerId = null,
            localAccountId = null,
            accountName = null,
            tone = WidgetQuotaTone.Neutral,
            clickTarget = if (hasAccounts) WidgetClickTarget.Home else WidgetClickTarget.AddAccount,
            fields = emptyList(),
            isUnconfigured = true,
            hasAccounts = hasAccounts,
        )

    fun create(
        state: CurrentQuotaState,
        notificationPreferences: NotificationPreferences = NotificationPreferences(),
        selectedWindowIds: List<String> = emptyList(),
    ): WidgetQuotaState {
        val account = state.account
        if (account == null) {
            return unconfigured(hasAccounts = false)
        }
        val fields = state.buildFields(selectedWindowIds, notificationPreferences)
        val firstField = fields.firstOrNull()
        return WidgetQuotaState(
            status = state.status.toWidgetStatus(),
            providerName = ProviderRegistry.displayNameFor(account.providerId),
            providerIconRes = ProviderRegistry.iconFor(account.providerId),
            providerId = account.providerId.value,
            localAccountId = account.localAccountId.value,
            accountName = account.displayName,
            tone = state.headerTone(firstField),
            clickTarget = WidgetClickTarget.Home,
            fields = fields,
            isUnconfigured = false,
            hasAccounts = true,
        )
    }

    private fun CurrentQuotaState.buildFields(
        selectedWindowIds: List<String>,
        notificationPreferences: NotificationPreferences,
    ): List<WidgetField> {
        val windows = snapshot?.windows.orEmpty()
        val selected = if (selectedWindowIds.isEmpty()) {
            windows
        } else {
            windows.filter { selectedWindowIds.contains(it.windowId.value) }
        }
        return selected
            .filter { it.isDisplayable() }
            .take(WIDGET_MAX_FIELDS)
            .map { window ->
                val isBalance = window.displayKind == QuotaWindowDisplayKind.Balance
                WidgetField(
                    windowId = window.windowId.value,
                    isBalance = isBalance,
                    percent = if (isBalance) null else window.displayPercent,
                    balanceAmount = if (isBalance) window.balanceAmount else null,
                    balanceCurrency = if (isBalance) window.balanceCurrency else null,
                    resetAt = window.resetAt,
                    tone = window.percentTone(notificationPreferences),
                )
            }
    }

    private fun CurrentQuotaStatus.toWidgetStatus(): WidgetQuotaStatus =
        when (this) {
            CurrentQuotaStatus.Unauthenticated -> WidgetQuotaStatus.NoAccount
            CurrentQuotaStatus.Loading,
            CurrentQuotaStatus.Fresh -> WidgetQuotaStatus.Fresh
            CurrentQuotaStatus.PossiblyStale -> WidgetQuotaStatus.PossiblyStale
            CurrentQuotaStatus.Expired -> WidgetQuotaStatus.Expired
            CurrentQuotaStatus.AuthRequired -> WidgetQuotaStatus.AuthRequired
            CurrentQuotaStatus.ErrorWithLastKnownGood -> WidgetQuotaStatus.ErrorWithLastKnownGood
            CurrentQuotaStatus.NoData -> WidgetQuotaStatus.NoData
        }

    private fun CurrentQuotaState.headerTone(firstField: WidgetField?): WidgetQuotaTone =
        when (status) {
            CurrentQuotaStatus.Unauthenticated,
            CurrentQuotaStatus.PossiblyStale,
            CurrentQuotaStatus.Expired,
            CurrentQuotaStatus.NoData -> WidgetQuotaTone.Neutral
            CurrentQuotaStatus.AuthRequired -> WidgetQuotaTone.Danger
            CurrentQuotaStatus.ErrorWithLastKnownGood -> WidgetQuotaTone.Warning
            CurrentQuotaStatus.Loading,
            CurrentQuotaStatus.Fresh -> firstField?.tone ?: WidgetQuotaTone.Neutral
        }

    private fun QuotaWindow.percentTone(notificationPreferences: NotificationPreferences): WidgetQuotaTone {
        if (displayKind == QuotaWindowDisplayKind.Balance) return WidgetQuotaTone.Neutral
        val percent = displayPercent
        return when {
            availability != QuotaWindowAvailability.Available || percent == null -> WidgetQuotaTone.Neutral
            percent <= notificationPreferences.limitThreshold -> WidgetQuotaTone.Danger
            percent <= notificationPreferences.warningThreshold -> WidgetQuotaTone.Danger
            percent <= notificationPreferences.cautionThreshold -> WidgetQuotaTone.Warning
            else -> WidgetQuotaTone.Success
        }
    }

    private fun QuotaWindow.isDisplayable(): Boolean =
        when (displayKind) {
            QuotaWindowDisplayKind.Balance ->
                availability == QuotaWindowAvailability.Available && !balanceAmount.isNullOrBlank()
            else ->
                availability == QuotaWindowAvailability.Available && displayPercent != null
        }

    private companion object {
        // Header shown on the unconfigured/no-account widget: app branding, not a single provider,
        // since CodexMeter now supports multiple AI providers.
        const val UNCONFIGURED_HEADER = "CodexMeter"
    }
}
