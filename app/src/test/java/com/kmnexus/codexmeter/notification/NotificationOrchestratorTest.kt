package com.kmnexus.codexmeter.notification

import android.app.PendingIntent
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
import com.kmnexus.codexmeter.domain.refresh.QuotaError
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
// Robolectric SDK 36 currently requires Java 21, while this project is pinned to Java 17.
@Config(sdk = [35])
class NotificationOrchestratorTest {
    private val orchestrator = NotificationOrchestrator()
    private val resetAt = Instant.parse("2026-05-23T17:00:00Z")

    @Test
    fun `channel ids match spec`() {
        assertEquals("quota_status", NotificationChannels.STATUS_CHANNEL_ID)
        assertEquals("quota_alerts", NotificationChannels.QUOTA_ALERTS_CHANNEL_ID)
        assertEquals("account_errors", NotificationChannels.ACCOUNT_ERRORS_CHANNEL_ID)
        assertEquals(
            listOf("quota_status", "quota_alerts", "account_errors"),
            NotificationChannels.definitions.map { it.id },
        )
    }

    @Test
    fun `created Android channels keep status quiet and alerts visible`() {
        val context = RuntimeEnvironment.getApplication()

        NotificationChannels.createAll(context)

        val notificationManager = context.getSystemService(android.app.NotificationManager::class.java)
        val statusChannel = notificationManager.getNotificationChannel(NotificationChannels.STATUS_CHANNEL_ID)
        val alertChannel = notificationManager.getNotificationChannel(NotificationChannels.QUOTA_ALERTS_CHANNEL_ID)
        val accountErrorChannel = notificationManager.getNotificationChannel(NotificationChannels.ACCOUNT_ERRORS_CHANNEL_ID)

        assertNotNull(statusChannel)
        assertEquals(android.app.NotificationManager.IMPORTANCE_LOW, statusChannel.importance)
        assertFalse(statusChannel.canShowBadge())
        assertNull(statusChannel.sound)
        assertFalse(statusChannel.shouldVibrate())
        assertNotNull(alertChannel)
        assertEquals(android.app.NotificationManager.IMPORTANCE_DEFAULT, alertChannel.importance)
        assertTrue(alertChannel.canShowBadge())
        assertNotNull(accountErrorChannel)
        assertEquals(android.app.NotificationManager.IMPORTANCE_DEFAULT, accountErrorChannel.importance)
        assertTrue(accountErrorChannel.canShowBadge())
    }

    @Test
    fun `permission unavailable produces no notification requests`() {
        val requests = orchestrator.buildRequests(
            state = state(),
            alertEvents = listOf(alertEvent()),
            options = NotificationRequestOptions(
                notificationPermissionAvailable = false,
                statusNotificationEnabled = true,
                quotaAlertsEnabled = true,
                accountErrorsEnabled = true,
            ),
        )

        assertTrue(requests.isEmpty())
    }

    @Test
    fun `disabled status notification produces no status request`() {
        val requests = orchestrator.buildRequests(
            state = state(),
            options = NotificationRequestOptions(
                notificationPermissionAvailable = true,
                statusNotificationEnabled = false,
                quotaAlertsEnabled = true,
                accountErrorsEnabled = true,
            ),
        )

        assertFalse(requests.any { it.channelId == NotificationChannels.STATUS_CHANNEL_ID })
    }

    @Test
    fun `alert and account error requests use expected channels`() {
        val requests = orchestrator.buildRequests(
            state = state(
                status = CurrentQuotaStatus.AuthRequired,
                error = QuotaError.AuthRequired(httpStatus = 401, diagnosticsDigest = "safe-digest"),
            ),
            alertEvents = listOf(alertEvent()),
            options = NotificationRequestOptions(
                notificationPermissionAvailable = true,
                statusNotificationEnabled = false,
                quotaAlertsEnabled = true,
                accountErrorsEnabled = true,
            ),
        )

        assertTrue(requests.any { it.channelId == NotificationChannels.QUOTA_ALERTS_CHANNEL_ID })
        assertTrue(requests.any { it.channelId == NotificationChannels.ACCOUNT_ERRORS_CHANNEL_ID })
    }

    @Test
    fun `first transient refresh failure does not create account error request`() {
        val requests = orchestrator.buildRequests(
            state = state(
                status = CurrentQuotaStatus.ErrorWithLastKnownGood,
                error = QuotaError.Network(diagnosticsDigest = "safe-digest"),
            ),
            options = NotificationRequestOptions(
                notificationPermissionAvailable = true,
                accountErrorsEnabled = true,
            ),
        )

        assertFalse(requests.any { it.channelId == NotificationChannels.ACCOUNT_ERRORS_CHANNEL_ID })
    }

    @Test
    fun `repeated refresh failure signal creates account error request`() {
        val requests = orchestrator.buildRequests(
            state = state(
                status = CurrentQuotaStatus.ErrorWithLastKnownGood,
                error = QuotaError.Network(diagnosticsDigest = "safe-digest"),
            ),
            options = NotificationRequestOptions(
                notificationPermissionAvailable = true,
                accountErrorsEnabled = true,
            ),
            accountErrorEvent = AccountErrorNotificationEvent(AccountErrorNotificationReason.RepeatedRefreshFailure),
        )

        assertTrue(requests.any { it.channelId == NotificationChannels.ACCOUNT_ERRORS_CHANNEL_ID })
    }

    @Test
    fun `warning alert notification id is stable when threshold changes`() {
        val firstRequest = orchestrator.buildRequests(
            state = state(),
            alertEvents = listOf(alertEvent(threshold = 8.0, remainingText = "8%")),
            options = NotificationRequestOptions(notificationPermissionAvailable = true),
        ).single { it.channelId == NotificationChannels.QUOTA_ALERTS_CHANNEL_ID }
        val secondRequest = orchestrator.buildRequests(
            state = state(),
            alertEvents = listOf(alertEvent(threshold = 12.0, remainingText = "8%")),
            options = NotificationRequestOptions(notificationPermissionAvailable = true),
        ).single { it.channelId == NotificationChannels.QUOTA_ALERTS_CHANNEL_ID }

        assertEquals(firstRequest.notificationId, secondRequest.notificationId)
    }

    @Test
    fun `quota alert notification id is stable per account window and level`() {
        val baseRequest = quotaAlertRequest(alertEvent())
        val thresholdChangedRequest = quotaAlertRequest(alertEvent(threshold = 12.0))
        val otherAccountRequest = quotaAlertRequest(alertEvent(localAccountId = "local-2"))
        val otherWindowRequest = quotaAlertRequest(alertEvent(windowId = QuotaWindowId("weekly")))
        val limitRequest = quotaAlertRequest(
            alertEvent(
                threshold = 0.0,
                remainingText = "0%",
                level = AlertLevel.Limit,
            ),
        )

        assertEquals(baseRequest.notificationId, thresholdChangedRequest.notificationId)
        assertNotEquals(baseRequest.notificationId, otherAccountRequest.notificationId)
        assertNotEquals(baseRequest.notificationId, otherWindowRequest.notificationId)
        assertNotEquals(baseRequest.notificationId, limitRequest.notificationId)
    }

    @Test
    fun `auth-required account error notification id is stable per account`() {
        val firstRequest = accountErrorRequest(localAccountId = "local-1")
        val repeatRequest = accountErrorRequest(localAccountId = "local-1")
        val otherAccountRequest = accountErrorRequest(localAccountId = "local-2")

        assertEquals(firstRequest.notificationId, repeatRequest.notificationId)
        assertNotEquals(firstRequest.notificationId, otherAccountRequest.notificationId)
    }

    @Test
    fun `repeated failure account error notification id is stable per account`() {
        val firstRequest = repeatedFailureAccountErrorRequest(localAccountId = "local-1")
        val repeatRequest = repeatedFailureAccountErrorRequest(localAccountId = "local-1")
        val otherAccountRequest = repeatedFailureAccountErrorRequest(localAccountId = "local-2")

        assertEquals(firstRequest.notificationId, repeatRequest.notificationId)
        assertNotEquals(firstRequest.notificationId, otherAccountRequest.notificationId)
    }

    @Test
    fun `notification content does not expose diagnostic secret fields`() {
        val forbiddenTerms = forbiddenDiagnosticTerms()
        // The account display name is intentionally user-facing now (it titles the notification), so
        // secrets are injected only through the digest fields, which must never reach rendered text.
        val requests = orchestrator.buildRequests(
            state = state(
                status = CurrentQuotaStatus.AuthRequired,
                accountDisplayName = "Codex Main",
                responseDigest = forbiddenTerms.take(4).joinToString(separator = " "),
                error = QuotaError.AuthRequired(
                    httpStatus = 401,
                    diagnosticsDigest = forbiddenTerms.joinToString(separator = " "),
                ),
            ),
            alertEvents = listOf(alertEvent()),
            options = NotificationRequestOptions(
                notificationPermissionAvailable = true,
                statusNotificationEnabled = true,
                quotaAlertsEnabled = true,
                accountErrorsEnabled = true,
            ),
        )

        val exposedContent = requests.joinToString(separator = "\n") { it.renderedContentForTest() }
        forbiddenTerms.forEach { forbidden ->
            assertFalse("Forbidden notification content leaked: $forbidden", exposedContent.contains(forbidden))
        }
    }

    @Test
    fun `status notification title names provider and account with quota in body`() {
        val statusRequest = orchestrator.buildRequests(
            state = state(accountDisplayName = "Work account"),
            options = NotificationRequestOptions(
                notificationPermissionAvailable = true,
                statusNotificationEnabled = true,
            ),
        ).single { it.channelId == NotificationChannels.STATUS_CHANNEL_ID }

        val title = statusRequest.title.renderedTextForTest()
        val body = statusRequest.body.renderedTextForTest()
        assertTrue("title should name the provider: $title", title.contains("Codex"))
        assertTrue("title should name the account: $title", title.contains("Work account"))
        // 62% used → 38% remaining is the figure shown.
        assertTrue("body should carry the quota figure: $body", body.contains("38"))
    }

    @Test
    fun `auth-required account error title names provider and account`() {
        val request = accountErrorRequest(localAccountId = "local-1")

        val title = request.title.renderedTextForTest()
        assertTrue("title should name the provider: $title", title.contains("Codex"))
        assertTrue("title should name the account: $title", title.contains("Codex Main"))
    }

    @Test
    fun `notification requests expose immutable pending intent metadata`() {
        val requests = orchestrator.buildRequests(
            state = state(),
            options = NotificationRequestOptions(
                notificationPermissionAvailable = true,
                statusNotificationEnabled = true,
            ),
        )

        assertTrue(requests.isNotEmpty())
        requests.forEach { request ->
            assertTrue(request.pendingIntent.flags and PendingIntent.FLAG_IMMUTABLE != 0)
        }
    }

    private fun NotificationRequest.renderedContentForTest(): String =
        listOf(title, body)
            .map { it.renderedTextForTest() }
            .joinToString(separator = " ")

    private fun NotificationText.renderedTextForTest(): String {
        val context = RuntimeEnvironment.getApplication()
        val resolvedArgs = formatArgs.map { arg ->
            if (arg is NotificationText) arg.renderedTextForTest() else arg
        }
        return context.getString(resourceId, *resolvedArgs.toTypedArray())
    }

    private fun forbiddenDiagnosticTerms(): List<String> =
        listOf(
            "access" + "_" + "token",
            "refresh" + "_" + "token",
            "id" + "_" + "token",
            "auth " + "code",
            "Cook" + "ie",
            "Author" + "ization",
            "auth" + ".json",
            "raw " + "usage API response body",
            "raw " + "response",
        )

    private fun alertEvent(
        localAccountId: String = "local-1",
        windowId: QuotaWindowId = QuotaWindowId("five_hour"),
        threshold: Double = 10.0,
        remainingText: String = "8%",
        level: AlertLevel = AlertLevel.Warning,
    ): QuotaAlertEvent =
        QuotaAlertEvent(
            key = AlertDedupeKey(
                providerId = ProviderId("codex"),
                localAccountId = LocalAccountId(localAccountId),
                windowId = windowId,
                resetAt = resetAt,
                threshold = threshold,
            ),
            accountDisplayName = "Codex Main",
            windowId = windowId,
            threshold = threshold,
            level = level,
            remainingText = remainingText,
            resetAt = resetAt,
        )

    private fun quotaAlertRequest(event: QuotaAlertEvent): NotificationRequest =
        orchestrator.buildRequests(
            state = state(),
            alertEvents = listOf(event),
            options = NotificationRequestOptions(notificationPermissionAvailable = true),
        ).single { it.channelId == NotificationChannels.QUOTA_ALERTS_CHANNEL_ID }

    private fun accountErrorRequest(localAccountId: String): NotificationRequest =
        orchestrator.buildRequests(
            state = state(
                status = CurrentQuotaStatus.AuthRequired,
                localAccountId = localAccountId,
                error = QuotaError.AuthRequired(httpStatus = 401, diagnosticsDigest = "safe-digest"),
            ),
            options = NotificationRequestOptions(
                notificationPermissionAvailable = true,
                accountErrorsEnabled = true,
            ),
        ).single { it.channelId == NotificationChannels.ACCOUNT_ERRORS_CHANNEL_ID }

    private fun repeatedFailureAccountErrorRequest(localAccountId: String): NotificationRequest =
        orchestrator.buildRequests(
            state = state(
                status = CurrentQuotaStatus.ErrorWithLastKnownGood,
                localAccountId = localAccountId,
                error = QuotaError.Network(diagnosticsDigest = "safe-digest"),
            ),
            options = NotificationRequestOptions(
                notificationPermissionAvailable = true,
                accountErrorsEnabled = true,
            ),
            accountErrorEvent = AccountErrorNotificationEvent(AccountErrorNotificationReason.RepeatedRefreshFailure),
        ).single { it.channelId == NotificationChannels.ACCOUNT_ERRORS_CHANNEL_ID }

    private fun state(
        status: CurrentQuotaStatus = CurrentQuotaStatus.Fresh,
        localAccountId: String = "local-1",
        accountDisplayName: String = "Codex Main",
        responseDigest: String? = "safe-digest",
        error: QuotaError? = null,
    ): CurrentQuotaState {
        val primaryWindow = quotaWindow()
        return CurrentQuotaState(
            status = status,
            freshness = when (status) {
                CurrentQuotaStatus.Expired -> CurrentQuotaFreshness.Expired
                CurrentQuotaStatus.PossiblyStale -> CurrentQuotaFreshness.PossiblyStale
                CurrentQuotaStatus.Fresh -> CurrentQuotaFreshness.Fresh
                else -> CurrentQuotaFreshness.Unknown
            },
            account = account(localAccountId = localAccountId, displayName = accountDisplayName),
            snapshot = quotaSnapshot(
                primaryWindow = primaryWindow,
                localAccountId = localAccountId,
                responseDigest = responseDigest,
            ),
            latestAttempt = null,
            primaryWindow = primaryWindow,
            secondaryWindows = emptyList(),
            primaryWindowCanAlert = true,
            error = error,
        )
    }

    private fun account(
        localAccountId: String,
        displayName: String,
    ): ProviderAccount =
        ProviderAccount.createNew(
            localAccountId = LocalAccountId(localAccountId),
            providerId = ProviderId("codex"),
            providerAccountId = ProviderAccountId("acct-$localAccountId"),
            displayName = displayName,
            now = Instant.parse("2026-05-23T09:00:00Z"),
        )

    private fun quotaSnapshot(
        primaryWindow: QuotaWindow,
        localAccountId: String,
        responseDigest: String?,
    ): QuotaSnapshot =
        QuotaSnapshot(
            snapshotId = SnapshotId("snapshot-1"),
            providerId = ProviderId("codex"),
            localAccountId = LocalAccountId(localAccountId),
            providerAccountId = ProviderAccountId("acct-$localAccountId"),
            fetchedAt = Instant.parse("2026-05-23T10:00:00Z"),
            source = QuotaSnapshotSource.ManualRefresh,
            planType = "plus",
            windows = listOf(primaryWindow),
            credits = null,
            responseDigest = responseDigest,
        )

    private fun quotaWindow(): QuotaWindow =
        QuotaWindow(
            windowId = QuotaWindowId("five_hour"),
            titleKey = "quota_window_five_hour",
            usedPercent = 62,
            resetAt = resetAt,
            limitWindowSeconds = 18_000,
            isPrimaryCandidate = true,
            availability = QuotaWindowAvailability.Available,
        )
}
