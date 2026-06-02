package com.kmnexus.codexmeter.domain.quota

import com.kmnexus.codexmeter.domain.model.AccountStatus
import com.kmnexus.codexmeter.domain.model.ProviderAccount
import com.kmnexus.codexmeter.domain.model.QuotaWindowId
import com.kmnexus.codexmeter.domain.refresh.QuotaError
import com.kmnexus.codexmeter.domain.refresh.RefreshAttempt
import com.kmnexus.codexmeter.domain.refresh.RefreshAttemptStatus
import com.kmnexus.codexmeter.domain.settings.DEFAULT_PRIMARY_QUOTA_WINDOW_ID
import java.time.Duration
import java.time.Instant

class CurrentQuotaStateFactory {
    fun create(
        account: ProviderAccount?,
        latestSnapshot: QuotaSnapshot?,
        latestAttempt: RefreshAttempt?,
        now: Instant,
        primaryWindowId: QuotaWindowId = DEFAULT_PRIMARY_WINDOW_ID,
    ): CurrentQuotaState {
        if (account == null) {
            return CurrentQuotaState(
                status = CurrentQuotaStatus.Unauthenticated,
                freshness = CurrentQuotaFreshness.Unknown,
                account = null,
                snapshot = null,
                latestAttempt = null,
                primaryWindow = null,
                secondaryWindows = emptyList(),
                primaryWindowCanAlert = false,
                error = null,
            )
        }

        val snapshot = latestSnapshot?.takeIf { it.belongsTo(account) }
        val attempt = latestAttempt?.takeIf { it.belongsTo(account) }
        val primaryWindow = snapshot?.windows?.firstOrNull { it.windowId == primaryWindowId }
        val freshness = snapshot?.freshnessAt(now) ?: CurrentQuotaFreshness.Unknown
        val error = when {
            account.status == AccountStatus.NeedsReauth -> QuotaError.AuthRequired(
                httpStatus = null,
                diagnosticsDigest = null,
            )
            else -> attempt?.toQuotaError()
        }
        val status = deriveStatus(
            account = account,
            snapshot = snapshot,
            latestError = error,
            freshness = freshness,
        )

        return CurrentQuotaState(
            status = status,
            freshness = freshness,
            account = account,
            snapshot = snapshot,
            latestAttempt = attempt,
            primaryWindow = primaryWindow,
            secondaryWindows = snapshot.secondaryWindows(primaryWindowId),
            primaryWindowCanAlert = primaryWindow?.canAlert() ?: false,
            error = error,
        )
    }

    private fun deriveStatus(
        account: ProviderAccount,
        snapshot: QuotaSnapshot?,
        latestError: QuotaError?,
        freshness: CurrentQuotaFreshness,
    ): CurrentQuotaStatus =
        when {
            account.status == AccountStatus.NeedsReauth -> CurrentQuotaStatus.AuthRequired
            latestError is QuotaError.AuthRequired -> CurrentQuotaStatus.AuthRequired
            snapshot == null -> CurrentQuotaStatus.NoData
            latestError != null -> CurrentQuotaStatus.ErrorWithLastKnownGood
            else -> freshness.toStatus()
        }

    private fun CurrentQuotaFreshness.toStatus(): CurrentQuotaStatus =
        when (this) {
            CurrentQuotaFreshness.Unknown -> CurrentQuotaStatus.NoData
            CurrentQuotaFreshness.Fresh -> CurrentQuotaStatus.Fresh
            CurrentQuotaFreshness.PossiblyStale -> CurrentQuotaStatus.PossiblyStale
            CurrentQuotaFreshness.Expired -> CurrentQuotaStatus.Expired
        }

    private fun QuotaSnapshot.freshnessAt(now: Instant): CurrentQuotaFreshness {
        val age = Duration.between(fetchedAt, now)
        return when {
            !age.isNegative && age >= EXPIRED_AFTER -> CurrentQuotaFreshness.Expired
            age > POSSIBLY_STALE_AFTER -> CurrentQuotaFreshness.PossiblyStale
            else -> CurrentQuotaFreshness.Fresh
        }
    }

    private fun RefreshAttempt.toQuotaError(): QuotaError? {
        if (status != RefreshAttemptStatus.Failed) return null
        return if (requiresAuth()) {
            QuotaError.AuthRequired(
                httpStatus = httpStatus,
                diagnosticsDigest = diagnosticsDigest,
            )
        } else {
            QuotaError.Network(diagnosticsDigest = diagnosticsDigest)
        }
    }

    private fun RefreshAttempt.requiresAuth(): Boolean =
        userActionRequired == true ||
            httpStatus in AUTH_REQUIRED_HTTP_STATUSES ||
            errorCode in AUTH_REQUIRED_ERROR_CODES

    private fun QuotaSnapshot.belongsTo(account: ProviderAccount): Boolean =
        providerId == account.providerId && localAccountId == account.localAccountId

    private fun RefreshAttempt.belongsTo(account: ProviderAccount): Boolean =
        providerId == account.providerId && localAccountId == account.localAccountId

    private fun QuotaSnapshot?.secondaryWindows(primaryWindowId: QuotaWindowId): List<QuotaWindow> =
        this?.windows.orEmpty().filterNot { it.windowId == primaryWindowId }

    companion object {
        val DEFAULT_PRIMARY_WINDOW_ID = DEFAULT_PRIMARY_QUOTA_WINDOW_ID

        private val POSSIBLY_STALE_AFTER = Duration.ofMinutes(30)
        private val EXPIRED_AFTER = Duration.ofHours(2)
        private val AUTH_REQUIRED_HTTP_STATUSES = setOf(401, 403)
        private val AUTH_REQUIRED_ERROR_CODES = setOf(
            "auth_required",
            "reauth_required",
            "invalid_grant",
            "refresh_token_expired",
            "refresh_token_invalid",
            "refresh_token_invalidated",
            "refresh_token_reused",
            "refresh_token_revoked",
        )
    }
}
