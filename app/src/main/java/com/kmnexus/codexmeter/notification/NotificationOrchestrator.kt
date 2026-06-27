package com.kmnexus.codexmeter.notification

import android.app.PendingIntent
import androidx.annotation.StringRes
import com.kmnexus.codexmeter.R
import com.kmnexus.codexmeter.domain.quota.CurrentQuotaState
import com.kmnexus.codexmeter.domain.quota.CurrentQuotaStatus
import com.kmnexus.codexmeter.domain.quota.QuotaWindowDisplayKind
import com.kmnexus.codexmeter.providers.ProviderRegistry
import com.kmnexus.codexmeter.ui.quota.formatProviderBalance

data class NotificationRequestOptions(
    val notificationPermissionAvailable: Boolean,
    val statusNotificationEnabled: Boolean = false,
    val quotaAlertsEnabled: Boolean = true,
    val accountErrorsEnabled: Boolean = true,
)

data class NotificationText(
    @field:StringRes val resourceId: Int,
    val formatArgs: List<Any> = emptyList(),
)

data class NotificationPendingIntentMetadata(
    val requestCode: Int,
    val flags: Int,
    val destination: NotificationDestination = NotificationDestination.Home,
    val externalUrl: String? = null,
)

enum class NotificationDestination {
    Home,
    AddAccount,
    ExternalUrl,
    AppUpdate,
}

enum class NotificationActionType {
    CopyDeviceCode,
}

data class NotificationActionIntentMetadata(
    val requestCode: Int,
    val flags: Int,
    val action: NotificationActionType,
    val attemptId: String? = null,
)

data class NotificationAction(
    val title: NotificationText,
    val intent: NotificationActionIntentMetadata,
)

enum class AccountErrorNotificationReason {
    AuthRequired,
    RepeatedRefreshFailure,
}

data class AccountErrorNotificationEvent(
    val reason: AccountErrorNotificationReason,
)

data class NotificationRequest(
    val notificationId: Int,
    val channelId: String,
    val title: NotificationText,
    val body: NotificationText,
    val pendingIntent: NotificationPendingIntentMetadata,
    val ongoing: Boolean,
    val actions: List<NotificationAction> = emptyList(),
    val timeoutAfterMillis: Long? = null,
)

class NotificationOrchestrator {
    fun buildRequests(
        state: CurrentQuotaState,
        alertEvents: List<QuotaAlertEvent> = emptyList(),
        options: NotificationRequestOptions,
        accountErrorEvent: AccountErrorNotificationEvent? = null,
    ): List<NotificationRequest> {
        if (!options.notificationPermissionAvailable) {
            return emptyList()
        }

        return buildList {
            if (options.statusNotificationEnabled) {
                add(statusRequest(state))
            }
            if (options.quotaAlertsEnabled) {
                addAll(alertEvents.map(::quotaAlertRequest))
            }
            if (options.accountErrorsEnabled) {
                accountErrorRequest(state, accountErrorEvent)?.let(::add)
            }
        }
    }

    private fun statusRequest(state: CurrentQuotaState): NotificationRequest =
        NotificationRequest(
            notificationId = STATUS_NOTIFICATION_ID,
            channelId = NotificationChannels.STATUS_CHANNEL_ID,
            title = identityTitle(state, fallbackResId = R.string.notification_status_title),
            body = statusBody(state),
            pendingIntent = homePendingIntent(STATUS_PENDING_INTENT_REQUEST_CODE),
            ongoing = true,
        )

    /**
     * "<provider>·<account>" so the persistent notification names which connected account it tracks
     * (the quota figure now lives in the body). Falls back to a generic title when no account is set.
     */
    private fun identityTitle(state: CurrentQuotaState, @StringRes fallbackResId: Int): NotificationText {
        val account = state.account ?: return NotificationText(resourceId = fallbackResId)
        return NotificationText(
            resourceId = R.string.notification_status_title_identity_format,
            formatArgs = listOf(
                ProviderRegistry.displayNameFor(account.providerId),
                account.displayName,
            ),
        )
    }

    /** Body pairs the remaining-quota figure (when known) with the freshness/status clause. */
    private fun statusBody(state: CurrentQuotaState): NotificationText {
        val statusText = NotificationText(statusBodyResource(state.status))
        val quota = quotaText(state) ?: return statusText
        return NotificationText(
            resourceId = R.string.notification_status_body_with_quota_format,
            formatArgs = listOf(quota, statusText),
        )
    }

    private fun quotaText(state: CurrentQuotaState): NotificationText? {
        val window = state.primaryWindow ?: return null
        if (window.displayKind == QuotaWindowDisplayKind.Balance) {
            val balance = formatProviderBalance(window.balanceAmount, window.balanceCurrency) ?: return null
            return NotificationText(
                resourceId = R.string.notification_status_title_balance_format,
                formatArgs = listOf(balance),
            )
        }
        val percent = window.displayPercent ?: return null
        return NotificationText(
            resourceId = R.string.notification_status_title_percent_format,
            formatArgs = listOf(percent),
        )
    }

    @StringRes
    private fun statusBodyResource(status: CurrentQuotaStatus): Int =
        when (status) {
            CurrentQuotaStatus.Unauthenticated -> R.string.notification_status_body_unauthenticated
            CurrentQuotaStatus.Loading -> R.string.notification_status_body_loading
            CurrentQuotaStatus.Fresh -> R.string.notification_status_body_fresh
            CurrentQuotaStatus.PossiblyStale -> R.string.notification_status_body_possibly_stale
            CurrentQuotaStatus.Expired -> R.string.notification_status_body_expired
            CurrentQuotaStatus.AuthRequired -> R.string.notification_status_body_auth_required
            CurrentQuotaStatus.ErrorWithLastKnownGood -> R.string.notification_status_body_error
            CurrentQuotaStatus.NoData -> R.string.notification_status_body_no_data
        }

    private fun quotaAlertRequest(event: QuotaAlertEvent): NotificationRequest =
        when (event.level) {
            AlertLevel.Caution -> NotificationRequest(
                notificationId = quotaAlertNotificationId(event),
                channelId = NotificationChannels.QUOTA_ALERTS_CHANNEL_ID,
                title = NotificationText(
                    resourceId = R.string.notification_alert_warning_title_percent_format,
                    formatArgs = listOf(event.remainingText),
                ),
                body = NotificationText(resourceId = R.string.notification_alert_warning_body),
                pendingIntent = homePendingIntent(QUOTA_ALERT_PENDING_INTENT_REQUEST_CODE),
                ongoing = false,
            )
            AlertLevel.Warning -> NotificationRequest(
                notificationId = quotaAlertNotificationId(event),
                channelId = NotificationChannels.QUOTA_ALERTS_CHANNEL_ID,
                title = NotificationText(
                    resourceId = R.string.notification_alert_warning_title_percent_format,
                    formatArgs = listOf(event.remainingText),
                ),
                body = NotificationText(resourceId = R.string.notification_alert_warning_body),
                pendingIntent = homePendingIntent(QUOTA_ALERT_PENDING_INTENT_REQUEST_CODE),
                ongoing = false,
            )
            AlertLevel.Limit -> NotificationRequest(
                notificationId = quotaAlertNotificationId(event),
                channelId = NotificationChannels.QUOTA_ALERTS_CHANNEL_ID,
                title = NotificationText(resourceId = R.string.notification_alert_limit_title),
                body = NotificationText(resourceId = R.string.notification_alert_limit_body),
                pendingIntent = homePendingIntent(QUOTA_ALERT_PENDING_INTENT_REQUEST_CODE),
                ongoing = false,
            )
        }

    private fun accountErrorRequest(
        state: CurrentQuotaState,
        accountErrorEvent: AccountErrorNotificationEvent?,
    ): NotificationRequest? {
        val reason = when {
            state.status == CurrentQuotaStatus.AuthRequired -> AccountErrorNotificationReason.AuthRequired
            accountErrorEvent?.reason == AccountErrorNotificationReason.RepeatedRefreshFailure ->
                AccountErrorNotificationReason.RepeatedRefreshFailure
            else -> return null
        }
        val bodyResId = when (reason) {
            AccountErrorNotificationReason.AuthRequired -> R.string.notification_account_error_body_auth_required
            AccountErrorNotificationReason.RepeatedRefreshFailure -> R.string.notification_account_error_body_refresh_failed
        }

        return NotificationRequest(
            notificationId = accountErrorNotificationId(state),
            channelId = NotificationChannels.ACCOUNT_ERRORS_CHANNEL_ID,
            title = identityTitle(state, fallbackResId = R.string.notification_account_error_title),
            body = NotificationText(resourceId = bodyResId),
            pendingIntent = homePendingIntent(ACCOUNT_ERROR_PENDING_INTENT_REQUEST_CODE),
            ongoing = false,
        )
    }

    private fun quotaAlertNotificationId(event: QuotaAlertEvent): Int =
        stableNotificationId(
            base = QUOTA_ALERT_NOTIFICATION_ID_BASE,
            event.key.providerId.value,
            event.key.localAccountId.value,
            event.windowId.value,
            event.level.name,
        )

    private fun accountErrorNotificationId(state: CurrentQuotaState): Int {
        val account = state.account ?: return ACCOUNT_ERROR_NOTIFICATION_ID_BASE
        return stableNotificationId(
            base = ACCOUNT_ERROR_NOTIFICATION_ID_BASE,
            account.providerId.value,
            account.localAccountId.value,
        )
    }

    private fun stableNotificationId(
        base: Int,
        vararg parts: String,
    ): Int =
        base + Math.floorMod(parts.joinToString(separator = "|").hashCode(), NOTIFICATION_ID_BUCKET_SIZE)

    private fun homePendingIntent(requestCode: Int): NotificationPendingIntentMetadata =
        NotificationPendingIntentMetadata(
            requestCode = requestCode,
            flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    companion object {
        const val STATUS_NOTIFICATION_ID = 1_001
        const val AUTH_LOGIN_NOTIFICATION_ID = 3_002
        const val APP_UPDATE_NOTIFICATION_ID = 4_001
        private const val QUOTA_ALERT_NOTIFICATION_ID_BASE = 20_000
        private const val ACCOUNT_ERROR_NOTIFICATION_ID_BASE = 30_000
        private const val NOTIFICATION_ID_BUCKET_SIZE = 10_000
        private const val STATUS_PENDING_INTENT_REQUEST_CODE = 11
        private const val QUOTA_ALERT_PENDING_INTENT_REQUEST_CODE = 21
        private const val ACCOUNT_ERROR_PENDING_INTENT_REQUEST_CODE = 31
    }
}
