package com.kmnexus.codexmeter.notification

import com.kmnexus.codexmeter.domain.quota.CurrentQuotaState
import com.kmnexus.codexmeter.domain.quota.CurrentQuotaStatus

class AccountErrorPolicy(
    private val repeatedFailureThreshold: Int = REPEATED_FAILURE_THRESHOLD,
) {
    fun evaluate(
        state: CurrentQuotaState,
        consecutiveFailureCount: Int,
    ): AccountErrorNotificationEvent? =
        when {
            state.status == CurrentQuotaStatus.AuthRequired ->
                AccountErrorNotificationEvent(AccountErrorNotificationReason.AuthRequired)
            consecutiveFailureCount >= repeatedFailureThreshold ->
                AccountErrorNotificationEvent(AccountErrorNotificationReason.RepeatedRefreshFailure)
            else -> null
        }

    companion object {
        const val REPEATED_FAILURE_THRESHOLD = 3
    }
}
