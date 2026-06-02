package com.kmnexus.codexmeter.notification

import com.kmnexus.codexmeter.R
import com.kmnexus.codexmeter.domain.model.LocalAccountId
import com.kmnexus.codexmeter.domain.model.ProviderAccount
import com.kmnexus.codexmeter.domain.model.ProviderAccountId
import com.kmnexus.codexmeter.domain.model.ProviderId
import com.kmnexus.codexmeter.domain.model.QuotaWindowId
import com.kmnexus.codexmeter.domain.model.SnapshotId
import com.kmnexus.codexmeter.domain.quota.CurrentQuotaFreshness
import com.kmnexus.codexmeter.domain.quota.CurrentQuotaState
import com.kmnexus.codexmeter.domain.quota.CurrentQuotaStatus
import com.kmnexus.codexmeter.domain.quota.QuotaSnapshot
import com.kmnexus.codexmeter.domain.quota.QuotaSnapshotSource
import com.kmnexus.codexmeter.domain.quota.QuotaWindow
import com.kmnexus.codexmeter.domain.quota.QuotaWindowAvailability
import com.kmnexus.codexmeter.domain.quota.QuotaWindowDisplayKind
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Test

class NotificationOrchestratorBalanceTest {
    private val orchestrator = NotificationOrchestrator()

    @Test
    fun `balance window uses balance format string with formatted amount`() {
        val state = balanceState(amount = "9.49", currency = "USD")

        val requests = orchestrator.buildRequests(
            state = state,
            options = NotificationRequestOptions(
                notificationPermissionAvailable = true,
                statusNotificationEnabled = true,
            ),
        )

        val statusRequest = requests.single { it.notificationId == NotificationOrchestrator.STATUS_NOTIFICATION_ID }
        // Title now carries the provider·account identity; the balance figure lives in the body.
        assertEquals(R.string.notification_status_title_identity_format, statusRequest.title.resourceId)
        val quota = statusRequest.body.quotaArg()
        assertEquals(R.string.notification_status_title_balance_format, quota.resourceId)
        assertEquals(listOf("\$9.49"), quota.formatArgs)
    }

    @Test
    fun `balance window with null amount omits quota from body`() {
        // A Balance window has no usedPercent, so displayPercent is null; with no balance figure the
        // body must drop the quota clause entirely rather than fall back to a percent.
        val state = balanceState(amount = null, currency = "USD")

        val requests = orchestrator.buildRequests(
            state = state,
            options = NotificationRequestOptions(
                notificationPermissionAvailable = true,
                statusNotificationEnabled = true,
            ),
        )

        val statusRequest = requests.single { it.notificationId == NotificationOrchestrator.STATUS_NOTIFICATION_ID }
        assertEquals(R.string.notification_status_title_identity_format, statusRequest.title.resourceId)
        assertEquals(R.string.notification_status_body_fresh, statusRequest.body.resourceId)
    }

    @Test
    fun `percent window still uses percent format`() {
        val window = QuotaWindow(
            windowId = QuotaWindowId("five_hour"),
            titleKey = "quota_window_five_hour",
            usedPercent = 62,
            resetAt = Instant.parse("2026-05-23T17:00:00Z"),
            limitWindowSeconds = 18_000,
            isPrimaryCandidate = true,
            availability = QuotaWindowAvailability.Available,
            displayKind = QuotaWindowDisplayKind.Percent,
        )
        val state = stateWithWindow(window)

        val requests = orchestrator.buildRequests(
            state = state,
            options = NotificationRequestOptions(
                notificationPermissionAvailable = true,
                statusNotificationEnabled = true,
            ),
        )

        val statusRequest = requests.single { it.notificationId == NotificationOrchestrator.STATUS_NOTIFICATION_ID }
        assertEquals(R.string.notification_status_title_identity_format, statusRequest.title.resourceId)
        val quota = statusRequest.body.quotaArg()
        assertEquals(R.string.notification_status_title_percent_format, quota.resourceId)
        assertEquals(listOf(38), quota.formatArgs) // 100 - 62 = 38
    }

    /** The body is "<quota> · <status>"; pull out the nested quota NotificationText. */
    private fun NotificationText.quotaArg(): NotificationText {
        assertEquals(R.string.notification_status_body_with_quota_format, resourceId)
        return formatArgs[0] as NotificationText
    }

    private fun balanceState(amount: String?, currency: String?): CurrentQuotaState {
        val window = QuotaWindow(
            windowId = QuotaWindowId("balance"),
            titleKey = "window_label_balance",
            usedPercent = null,
            resetAt = null,
            limitWindowSeconds = null,
            isPrimaryCandidate = true,
            availability = QuotaWindowAvailability.Available,
            displayKind = QuotaWindowDisplayKind.Balance,
            balanceAmount = amount,
            balanceCurrency = currency,
        )
        return stateWithWindow(window)
    }

    private fun stateWithWindow(window: QuotaWindow): CurrentQuotaState {
        val account = ProviderAccount.createNew(
            localAccountId = LocalAccountId("local-1"),
            providerId = ProviderId("deepseek"),
            providerAccountId = ProviderAccountId("acct-local-1"),
            displayName = "DeepSeek Main",
            now = Instant.parse("2026-05-23T09:00:00Z"),
        )
        val snapshot = QuotaSnapshot(
            snapshotId = SnapshotId("snapshot-1"),
            providerId = ProviderId("deepseek"),
            localAccountId = LocalAccountId("local-1"),
            providerAccountId = ProviderAccountId("acct-local-1"),
            fetchedAt = Instant.parse("2026-05-23T10:00:00Z"),
            source = QuotaSnapshotSource.ManualRefresh,
            planType = null,
            windows = listOf(window),
            credits = null,
            responseDigest = "safe-digest",
        )
        return CurrentQuotaState(
            status = CurrentQuotaStatus.Fresh,
            freshness = CurrentQuotaFreshness.Fresh,
            account = account,
            snapshot = snapshot,
            latestAttempt = null,
            primaryWindow = window,
            secondaryWindows = emptyList(),
            primaryWindowCanAlert = true,
            error = null,
        )
    }
}
