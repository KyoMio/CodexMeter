package com.kmnexus.codexmeter.domain.settings

import com.kmnexus.codexmeter.domain.model.LocalAccountId
import com.kmnexus.codexmeter.domain.model.ProviderId
import com.kmnexus.codexmeter.domain.model.QuotaWindowId

data class NotificationPreferences(
    val statusNotificationEnabled: Boolean = true,
    val quotaAlertsEnabled: Boolean = true,
    val accountErrorsEnabled: Boolean = true,
    val cautionThreshold: Int = DEFAULT_CAUTION_THRESHOLD,
    val warningThreshold: Int = DEFAULT_WARNING_THRESHOLD,
    val limitThreshold: Int = LIMIT_THRESHOLD,
    val balanceCautionThreshold: Double = DEFAULT_BALANCE_CAUTION_THRESHOLD,
    val balanceWarningThreshold: Double = DEFAULT_BALANCE_WARNING_THRESHOLD,
    // Background refresh cadence in minutes; 0 = manual (no periodic refresh).
    val backgroundRefreshIntervalMinutes: Int = DEFAULT_REFRESH_INTERVAL_MINUTES,
    val persistentNotificationAccount: NotificationAccountSelection? = null,
    val persistentNotificationWindowId: QuotaWindowId = DEFAULT_NOTIFICATION_WINDOW_ID,
    val accountQuotaAlertSettings: Map<AccountQuotaAlertKey, Boolean> = emptyMap(),
) {
    init {
        require(cautionThreshold in CAUTION_THRESHOLD_RANGE) { "caution threshold must be in 2..99" }
        require(warningThreshold in WARNING_THRESHOLD_RANGE) { "warning threshold must be in 1..98" }
        require(limitThreshold == LIMIT_THRESHOLD) { "limit threshold is fixed at 0" }
        require(LIMIT_THRESHOLD < warningThreshold) { "warning threshold must be higher than limit" }
        require(warningThreshold < cautionThreshold) { "caution threshold must be higher than warning" }
        // Any provider window id is allowed here; SUPPORTED_NOTIFICATION_WINDOW_IDS is still
        // used by withLegacyPrimaryWindowAlertForAccount for migration logic.
        require(backgroundRefreshIntervalMinutes >= 0) { "refresh interval minutes must be non-negative" }
    }

    fun isQuotaAlertEnabled(
        providerId: ProviderId,
        localAccountId: LocalAccountId,
        windowId: QuotaWindowId,
    ): Boolean =
        accountQuotaAlertSettings[AccountQuotaAlertKey(providerId, localAccountId, windowId)]
            ?: (windowId == DEFAULT_ACCOUNT_QUOTA_ALERT_WINDOW_ID)

    fun withAccountQuotaAlertEnabled(
        providerId: ProviderId,
        localAccountId: LocalAccountId,
        windowId: QuotaWindowId,
        enabled: Boolean,
    ): NotificationPreferences =
        copy(
            accountQuotaAlertSettings = accountQuotaAlertSettings +
                (AccountQuotaAlertKey(providerId, localAccountId, windowId) to enabled),
        )

    fun withLegacyPrimaryWindowAlertForAccount(
        providerId: ProviderId,
        localAccountId: LocalAccountId,
        legacyPrimaryWindowId: QuotaWindowId,
    ): NotificationPreferences =
        copy(
            accountQuotaAlertSettings = accountQuotaAlertSettings +
                SUPPORTED_NOTIFICATION_WINDOW_IDS.associate { windowId ->
                    AccountQuotaAlertKey(providerId, localAccountId, windowId) to
                        (windowId == legacyPrimaryWindowId)
                },
        )
}

data class NotificationAccountSelection(
    val providerId: ProviderId,
    val localAccountId: LocalAccountId,
)

data class AccountQuotaAlertKey(
    val providerId: ProviderId,
    val localAccountId: LocalAccountId,
    val windowId: QuotaWindowId,
)

interface NotificationPreferenceReader {
    suspend fun notificationPreferences(): NotificationPreferences
}

interface NotificationPreferenceStore : NotificationPreferenceReader {
    suspend fun updateNotificationPreferences(preferences: NotificationPreferences)
}

const val DEFAULT_CAUTION_THRESHOLD = 30
const val DEFAULT_WARNING_THRESHOLD = 10
const val LIMIT_THRESHOLD = 0
const val DEFAULT_BALANCE_CAUTION_THRESHOLD = 5.0
const val DEFAULT_BALANCE_WARNING_THRESHOLD = 1.0
const val DEFAULT_REFRESH_INTERVAL_MINUTES = 15
val CAUTION_THRESHOLD_RANGE = 2..99
val WARNING_THRESHOLD_RANGE = 1..98
val DEFAULT_NOTIFICATION_WINDOW_ID = QuotaWindowId("five_hour")
val DEFAULT_ACCOUNT_QUOTA_ALERT_WINDOW_ID = QuotaWindowId("five_hour")
val SUPPORTED_NOTIFICATION_WINDOW_IDS = setOf(
    QuotaWindowId("five_hour"),
    QuotaWindowId("weekly"),
)
