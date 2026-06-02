package com.kmnexus.codexmeter.notification

import com.kmnexus.codexmeter.domain.model.LocalAccountId
import com.kmnexus.codexmeter.domain.model.ProviderAccount
import com.kmnexus.codexmeter.domain.model.ProviderId
import com.kmnexus.codexmeter.domain.model.QuotaWindowId
import com.kmnexus.codexmeter.domain.quota.CurrentQuotaState
import com.kmnexus.codexmeter.domain.quota.QuotaWindowAvailability
import com.kmnexus.codexmeter.domain.quota.QuotaWindow
import com.kmnexus.codexmeter.domain.quota.QuotaWindowDisplayKind
import java.time.Instant

data class AlertThresholds(
    val caution: Int = 30,
    val warning: Int = 10,
    val limit: Int = 0,
    val balanceCaution: Double? = null,
    val balanceWarning: Double? = null,
) {
    init {
        require(caution in 0..100) { "caution threshold must be within 0..100" }
        require(warning in 0..100) { "warning threshold must be within 0..100" }
        require(limit in 0..100) { "limit threshold must be within 0..100" }
        require(limit < warning) { "warning threshold must be higher than limit" }
        require(warning < caution) { "caution threshold must be higher than warning" }
        if (balanceCaution != null && balanceWarning != null) {
            require(balanceCaution > 0) { "balanceCaution must be positive" }
            require(balanceWarning > 0) { "balanceWarning must be positive" }
            require(balanceWarning < balanceCaution) { "balanceCaution must be higher than balanceWarning" }
        }
    }
}

enum class AlertLevel {
    Caution,
    Warning,
    Limit,
}

data class AlertDedupeKey(
    val providerId: ProviderId,
    val localAccountId: LocalAccountId,
    val windowId: QuotaWindowId,
    val resetAt: Instant?,        // nullable for Balance (no reset)
    val threshold: Double,        // Double for Balance amounts
)

data class QuotaAlertEvent(
    val key: AlertDedupeKey,
    val accountDisplayName: String,
    val windowId: QuotaWindowId,
    val threshold: Double,
    val level: AlertLevel,
    val remainingText: String,    // "5%" or "¥3.00" or "50/500"
    val resetAt: Instant?,
)

class AlertPolicy {
    fun evaluate(
        state: CurrentQuotaState,
        alreadyNotified: Set<AlertDedupeKey> = emptySet(),
        thresholds: AlertThresholds = AlertThresholds(),
        enabledWindowIds: Set<QuotaWindowId> = state.primaryWindow?.windowId?.let(::setOf).orEmpty(),
    ): List<QuotaAlertEvent> {
        state.account ?: return emptyList()
        val windows = state.snapshot?.windows.orEmpty()
        return windows.mapNotNull { window ->
            evaluateWindow(
                state = state,
                window = window,
                enabledWindowIds = enabledWindowIds,
                alreadyNotified = alreadyNotified,
                thresholds = thresholds,
            )
        }
    }

    private fun evaluateWindow(
        state: CurrentQuotaState,
        window: QuotaWindow,
        enabledWindowIds: Set<QuotaWindowId>,
        alreadyNotified: Set<AlertDedupeKey>,
        thresholds: AlertThresholds,
    ): QuotaAlertEvent? {
        val account = state.account ?: return null
        if (window.windowId !in enabledWindowIds) return null
        if (window.availability != QuotaWindowAvailability.Available) return null
        if (window.windowId == state.primaryWindow?.windowId && !state.primaryWindowCanAlert) return null

        return when (window.displayKind) {
            QuotaWindowDisplayKind.Balance -> evaluateBalanceWindow(account, window, alreadyNotified, thresholds)
            QuotaWindowDisplayKind.UsageCount -> evaluateCountWindow(account, window, alreadyNotified, thresholds)
            QuotaWindowDisplayKind.Percent, QuotaWindowDisplayKind.MultiModelFraction ->
                evaluatePercentWindow(account, window, alreadyNotified, thresholds)
        }
    }

    private fun evaluateBalanceWindow(
        account: ProviderAccount,
        window: QuotaWindow,
        alreadyNotified: Set<AlertDedupeKey>,
        thresholds: AlertThresholds,
    ): QuotaAlertEvent? {
        val amount = window.balanceAmount?.toDoubleOrNull() ?: return null
        val warning = thresholds.balanceWarning ?: return null
        val caution = thresholds.balanceCaution ?: return null
        val (threshold, level) = when {
            amount <= 0 -> thresholds.limit.toDouble() to AlertLevel.Limit
            amount <= warning -> warning to AlertLevel.Warning
            amount <= caution -> caution to AlertLevel.Caution
            else -> return null
        }
        val key = AlertDedupeKey(
            providerId = account.providerId,
            localAccountId = account.localAccountId,
            windowId = window.windowId,
            resetAt = null,
            threshold = threshold,
        )
        if (key in alreadyNotified) return null
        return QuotaAlertEvent(
            key = key,
            accountDisplayName = account.displayName,
            windowId = window.windowId,
            threshold = threshold,
            level = level,
            remainingText = "$amount",
            resetAt = null,
        )
    }

    private fun evaluateCountWindow(
        account: ProviderAccount,
        window: QuotaWindow,
        alreadyNotified: Set<AlertDedupeKey>,
        thresholds: AlertThresholds,
    ): QuotaAlertEvent? {
        val used = window.usedCount ?: return null
        val limit = window.limitCount ?: return null
        if (limit <= 0) return null
        val remainingPercent = ((limit - used).toDouble() / limit * 100).toInt()
        val (threshold, level) = when {
            remainingPercent <= thresholds.limit -> thresholds.limit.toDouble() to AlertLevel.Limit
            remainingPercent <= thresholds.warning -> thresholds.warning.toDouble() to AlertLevel.Warning
            else -> return null
        }
        val key = AlertDedupeKey(
            providerId = account.providerId,
            localAccountId = account.localAccountId,
            windowId = window.windowId,
            resetAt = window.resetAt,
            threshold = threshold,
        )
        if (key in alreadyNotified) return null
        return QuotaAlertEvent(
            key = key,
            accountDisplayName = account.displayName,
            windowId = window.windowId,
            threshold = threshold,
            level = level,
            remainingText = "$used/$limit",
            resetAt = window.resetAt,
        )
    }

    private fun evaluatePercentWindow(
        account: ProviderAccount,
        window: QuotaWindow,
        alreadyNotified: Set<AlertDedupeKey>,
        thresholds: AlertThresholds,
    ): QuotaAlertEvent? {
        val usedPercent = window.usedPercent ?: return null
        val remainingPercent = window.remainingPercent ?: return null
        val resetAt = window.resetAt ?: return null
        if (usedPercent !in 0..100) return null
        val (threshold, level) = when {
            remainingPercent <= thresholds.limit -> thresholds.limit.toDouble() to AlertLevel.Limit
            remainingPercent <= thresholds.warning -> thresholds.warning.toDouble() to AlertLevel.Warning
            else -> return null
        }
        val key = AlertDedupeKey(
            providerId = account.providerId,
            localAccountId = account.localAccountId,
            windowId = window.windowId,
            resetAt = resetAt,
            threshold = threshold,
        )
        if (key in alreadyNotified) return null
        return QuotaAlertEvent(
            key = key,
            accountDisplayName = account.displayName,
            windowId = window.windowId,
            threshold = threshold,
            level = level,
            remainingText = "$remainingPercent%",
            resetAt = resetAt,
        )
    }
}
