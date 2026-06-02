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
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CurrentQuotaNotificationPublisherTest {
    private val now = Instant.parse("2026-05-24T06:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)

    @Test
    fun `publish posts status notification request when status is enabled`() = runTest {
        val sink = RecordingNotificationSink()
        val publisher = publisher(
            sink = sink,
            options = NotificationRequestOptions(
                notificationPermissionAvailable = true,
                statusNotificationEnabled = true,
                quotaAlertsEnabled = false,
                accountErrorsEnabled = false,
            ),
        )

        publisher.publish(state(usedPercent = 42))

        assertEquals(listOf(NotificationChannels.STATUS_CHANNEL_ID), sink.requests.map { it.channelId })
        assertEquals(listOf(1001), sink.requests.map { it.notificationId })
    }

    @Test
    fun `publish posts quota alert once and records dedupe state`() = runTest {
        val sink = RecordingNotificationSink()
        val alertStateStore = RecordingNotificationAlertStateStore()
        val publisher = publisher(
            sink = sink,
            alertStateStore = alertStateStore,
            options = NotificationRequestOptions(
                notificationPermissionAvailable = true,
                statusNotificationEnabled = false,
                quotaAlertsEnabled = true,
                accountErrorsEnabled = false,
            ),
        )

        publisher.publish(state(usedPercent = 92))
        publisher.publish(state(usedPercent = 92))

        assertEquals(listOf(NotificationChannels.QUOTA_ALERTS_CHANNEL_ID), sink.requests.map { it.channelId })
        assertEquals(1, alertStateStore.markedKeys.size)
        assertEquals(10.0, alertStateStore.markedKeys.single().threshold, 0.0)
        assertEquals(now, alertStateStore.notifiedAt.single())
    }

    @Test
    fun `publish evaluates quota alerts with persisted warning threshold`() = runTest {
        val sink = RecordingNotificationSink()
        val alertStateStore = RecordingNotificationAlertStateStore()
        val publisher = publisher(
            sink = sink,
            alertStateStore = alertStateStore,
            options = NotificationRequestOptions(
                notificationPermissionAvailable = true,
                statusNotificationEnabled = false,
                quotaAlertsEnabled = true,
                accountErrorsEnabled = false,
            ),
            thresholds = AlertThresholds(caution = 30, warning = 15, limit = 0),
        )

        publisher.publish(state(usedPercent = 86))

        assertEquals(listOf(NotificationChannels.QUOTA_ALERTS_CHANNEL_ID), sink.requests.map { it.channelId })
        assertEquals(15.0, alertStateStore.markedKeys.single().threshold, 0.0)
    }

    @Test
    fun `publish posts quota alert for enabled refreshed account window`() = runTest {
        val sink = RecordingNotificationSink()
        val alertStateStore = RecordingNotificationAlertStateStore()
        val publisher = publisher(
            sink = sink,
            alertStateStore = alertStateStore,
            options = NotificationRequestOptions(
                notificationPermissionAvailable = true,
                statusNotificationEnabled = false,
                quotaAlertsEnabled = true,
                accountErrorsEnabled = false,
            ),
            alertWindowPreferenceReader = StaticQuotaAlertWindowPreferenceReader(
                setOf(QuotaWindowId("weekly")),
            ),
        )

        publisher.publish(
            state(
                usedPercent = 40,
                snapshotWindows = listOf(
                    quotaWindow(
                        windowId = QuotaWindowId("five_hour"),
                        usedPercent = 40,
                    ),
                    quotaWindow(
                        windowId = QuotaWindowId("weekly"),
                        usedPercent = 100,
                        resetAt = Instant.parse("2026-05-31T00:00:00Z"),
                    ),
                ),
            ),
        )

        assertEquals(listOf(NotificationChannels.QUOTA_ALERTS_CHANNEL_ID), sink.requests.map { it.channelId })
        assertEquals(QuotaWindowId("weekly"), alertStateStore.markedKeys.single().windowId)
        assertEquals(0.0, alertStateStore.markedKeys.single().threshold, 0.0)
    }

    @Test
    fun `status notification loads configured state instead of refreshed account state`() = runTest {
        val sink = RecordingNotificationSink()
        val publisher = publisher(
            sink = sink,
            options = NotificationRequestOptions(
                notificationPermissionAvailable = true,
                statusNotificationEnabled = true,
                quotaAlertsEnabled = false,
                accountErrorsEnabled = false,
            ),
            statusNotificationStateLoader = StaticStatusNotificationStateLoader(
                state(
                    usedPercent = 25,
                    localAccountId = "local-configured",
                    displayName = "Configured",
                ),
            ),
        )

        publisher.publish(
            state(
                usedPercent = 92,
                localAccountId = "local-refreshed",
                displayName = "Refreshed",
            ),
        )

        val statusRequest = sink.requests.single()
        assertEquals(NotificationChannels.STATUS_CHANNEL_ID, statusRequest.channelId)
        // Title names the configured account; the configured quota (25 used → 75 remaining) is in the body.
        assertEquals(listOf("Codex", "Configured"), statusRequest.title.formatArgs)
        val quota = statusRequest.body.formatArgs[0] as NotificationText
        assertEquals(listOf(75), quota.formatArgs)
    }

    @Test
    fun `permission unavailable posts nothing and does not record alert dedupe`() = runTest {
        val sink = RecordingNotificationSink()
        val alertStateStore = RecordingNotificationAlertStateStore()
        val publisher = publisher(
            sink = sink,
            alertStateStore = alertStateStore,
            options = NotificationRequestOptions(
                notificationPermissionAvailable = false,
                statusNotificationEnabled = true,
                quotaAlertsEnabled = true,
                accountErrorsEnabled = true,
            ),
        )

        publisher.publish(state(usedPercent = 92))

        assertTrue(sink.requests.isEmpty())
        assertTrue(alertStateStore.markedKeys.isEmpty())
    }

    @Test
    fun `publish cancels existing status notification when status is disabled`() = runTest {
        val sink = RecordingNotificationSink()
        val publisher = publisher(
            sink = sink,
            options = NotificationRequestOptions(
                notificationPermissionAvailable = true,
                statusNotificationEnabled = false,
                quotaAlertsEnabled = false,
                accountErrorsEnabled = false,
            ),
        )

        publisher.publish(state(usedPercent = 42))

        assertTrue(sink.requests.isEmpty())
        assertEquals(listOf(1001), sink.cancelledNotificationIds)
    }

    @Test
    fun `publish posts repeated refresh failure account error from policy reader`() = runTest {
        val sink = RecordingNotificationSink()
        val publisher = publisher(
            sink = sink,
            options = NotificationRequestOptions(
                notificationPermissionAvailable = true,
                statusNotificationEnabled = false,
                quotaAlertsEnabled = false,
                accountErrorsEnabled = true,
            ),
            accountErrorEventReader = StaticAccountErrorEventReader(
                AccountErrorNotificationEvent(AccountErrorNotificationReason.RepeatedRefreshFailure),
            ),
        )

        publisher.publish(state(usedPercent = 42, status = CurrentQuotaStatus.ErrorWithLastKnownGood))

        assertEquals(listOf(NotificationChannels.ACCOUNT_ERRORS_CHANNEL_ID), sink.requests.map { it.channelId })
        assertEquals(R.string.notification_account_error_body_refresh_failed, sink.requests.single().body.resourceId)
    }

    @Test
    fun `publish records alert dedupe after alert post even when later request fails`() = runTest {
        val alertStateStore = RecordingNotificationAlertStateStore()
        val sink = ThrowingAfterAlertNotificationSink()
        val publisher = publisher(
            sink = sink,
            alertStateStore = alertStateStore,
            options = NotificationRequestOptions(
                notificationPermissionAvailable = true,
                statusNotificationEnabled = false,
                quotaAlertsEnabled = true,
                accountErrorsEnabled = true,
            ),
        )

        runCatching {
            publisher.publish(
                state(
                    usedPercent = 92,
                    status = CurrentQuotaStatus.AuthRequired,
                ),
            )
        }

        assertEquals(listOf(NotificationChannels.QUOTA_ALERTS_CHANNEL_ID), sink.postedChannels)
        assertEquals(10.0, alertStateStore.markedKeys.single().threshold, 0.0)
    }

    private fun publisher(
        sink: NotificationSink,
        alertStateStore: RecordingNotificationAlertStateStore = RecordingNotificationAlertStateStore(),
        options: NotificationRequestOptions,
        thresholds: AlertThresholds = AlertThresholds(),
        alertWindowPreferenceReader: QuotaAlertWindowPreferenceReader = PrimaryQuotaAlertWindowPreferenceReader,
        statusNotificationStateLoader: StatusNotificationStateLoader = PassthroughStatusNotificationStateLoader,
        accountErrorEventReader: AccountErrorEventReader = NoopAccountErrorEventReader,
    ): CurrentQuotaNotificationPublisher =
        CurrentQuotaNotificationPublisher(
            notificationSink = sink,
            alertStateStore = alertStateStore,
            optionsReader = StaticNotificationRequestOptionsReader(options),
            alertThresholdsReader = StaticAlertThresholdsReader(thresholds),
            alertWindowPreferenceReader = alertWindowPreferenceReader,
            statusNotificationStateLoader = statusNotificationStateLoader,
            accountErrorEventReader = accountErrorEventReader,
            clock = clock,
        )

    private fun state(
        usedPercent: Int,
        status: CurrentQuotaStatus = CurrentQuotaStatus.Fresh,
        localAccountId: String = "local-1",
        displayName: String = "Codex Main",
        snapshotWindows: List<QuotaWindow>? = null,
    ): CurrentQuotaState {
        val account = ProviderAccount.createNew(
            localAccountId = LocalAccountId(localAccountId),
            providerId = ProviderId("codex"),
            providerAccountId = ProviderAccountId("acct-$localAccountId"),
            displayName = displayName,
            now = now,
        )
        val primaryWindow = quotaWindow(
            windowId = QuotaWindowId("five_hour"),
            usedPercent = usedPercent,
        )
        val snapshot = QuotaSnapshot(
            snapshotId = SnapshotId("snapshot-1"),
            providerId = ProviderId("codex"),
            localAccountId = LocalAccountId(localAccountId),
            providerAccountId = ProviderAccountId("acct-$localAccountId"),
            fetchedAt = now,
            source = QuotaSnapshotSource.ManualRefresh,
            planType = "plus",
            windows = snapshotWindows ?: listOf(primaryWindow),
            credits = null,
            responseDigest = "safe-digest",
        )

        return CurrentQuotaState(
            status = status,
            freshness = CurrentQuotaFreshness.Fresh,
            account = account,
            snapshot = snapshot,
            latestAttempt = null,
            primaryWindow = primaryWindow,
            secondaryWindows = emptyList(),
            primaryWindowCanAlert = true,
            error = null,
        )
    }

    private fun quotaWindow(
        windowId: QuotaWindowId,
        usedPercent: Int,
        resetAt: Instant = Instant.parse("2026-05-24T10:00:00Z"),
    ): QuotaWindow =
        QuotaWindow(
            windowId = windowId,
            titleKey = "quota_window_${windowId.value}",
            usedPercent = usedPercent,
            resetAt = resetAt,
            limitWindowSeconds = 18_000,
            isPrimaryCandidate = true,
            availability = QuotaWindowAvailability.Available,
        )

    private class RecordingNotificationSink : NotificationSink {
        val requests = mutableListOf<NotificationRequest>()
        val cancelledNotificationIds = mutableListOf<Int>()

        override fun post(request: NotificationRequest) {
            requests += request
        }

        override fun cancel(notificationId: Int) {
            cancelledNotificationIds += notificationId
        }
    }

    private class ThrowingAfterAlertNotificationSink : NotificationSink {
        val postedChannels = mutableListOf<String>()

        override fun post(request: NotificationRequest) {
            if (request.channelId == NotificationChannels.ACCOUNT_ERRORS_CHANNEL_ID) {
                throw IllegalStateException("later request failed")
            }
            postedChannels += request.channelId
        }
    }

    private class RecordingNotificationAlertStateStore : NotificationAlertStateStore {
        val markedKeys = mutableListOf<AlertDedupeKey>()
        val notifiedAt = mutableListOf<Instant>()

        override suspend fun hasNotified(key: AlertDedupeKey): Boolean =
            key in markedKeys

        override suspend fun markNotified(event: QuotaAlertEvent, notifiedAt: Instant) {
            markedKeys += event.key
            this.notifiedAt += notifiedAt
        }
    }

    private class StaticQuotaAlertWindowPreferenceReader(
        private val enabledWindowIds: Set<QuotaWindowId>,
    ) : QuotaAlertWindowPreferenceReader {
        override suspend fun enabledWindowIds(state: CurrentQuotaState): Set<QuotaWindowId> =
            enabledWindowIds
    }

    private class StaticStatusNotificationStateLoader(
        private val state: CurrentQuotaState,
    ) : StatusNotificationStateLoader {
        override suspend fun loadStatusNotificationState(refreshedState: CurrentQuotaState): CurrentQuotaState =
            state
    }

    private class StaticAccountErrorEventReader(
        private val event: AccountErrorNotificationEvent?,
    ) : AccountErrorEventReader {
        override suspend fun accountErrorEvent(state: CurrentQuotaState): AccountErrorNotificationEvent? =
            event
    }
}
