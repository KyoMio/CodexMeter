package com.kmnexus.codexmeter.notification

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
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AlertPolicyTest {
    private val policy = AlertPolicy()
    private val resetAt = Instant.parse("2026-05-23T12:00:00Z")

    @Test
    fun `30 percent remaining primary window creates no notification event`() {
        val events = policy.evaluate(state = state(usedPercent = 70))

        assertTrue(events.isEmpty())
    }

    @Test
    fun `10 percent remaining primary window creates warning event`() {
        val events = policy.evaluate(state = state(usedPercent = 90))

        assertEquals(1, events.size)
        assertEquals("Codex Main", events.single().accountDisplayName)
        assertEquals(QuotaWindowId("five_hour"), events.single().windowId)
        assertEquals(10.0, events.single().threshold, 0.0)
        assertEquals(AlertLevel.Warning, events.single().level)
        assertEquals("10%", events.single().remainingText)
        assertEquals(dedupeKey(threshold = 10.0), events.single().key)
    }

    @Test
    fun `zero percent remaining primary window creates limit event only`() {
        val events = policy.evaluate(state = state(usedPercent = 100))

        assertEquals(1, events.size)
        assertEquals(0.0, events.single().threshold, 0.0)
        assertEquals(AlertLevel.Limit, events.single().level)
        assertEquals("0%", events.single().remainingText)
        assertEquals(dedupeKey(threshold = 0.0), events.single().key)
    }

    @Test
    fun `missing primary window ignores high secondary window`() {
        val events = policy.evaluate(state = stateWithoutPrimaryWindow())

        assertTrue(events.isEmpty())
    }

    @Test
    fun `same reset and threshold does not duplicate`() {
        val events = policy.evaluate(
            state = state(usedPercent = 90),
            alreadyNotified = setOf(dedupeKey(threshold = 10.0)),
        )

        assertTrue(events.isEmpty())
    }

    @Test
    fun `new reset allows alert again`() {
        val newResetAt = Instant.parse("2026-05-23T17:00:00Z")

        val events = policy.evaluate(
            state = state(usedPercent = 90, resetAt = newResetAt),
            alreadyNotified = setOf(dedupeKey(threshold = 10.0)),
        )

        assertEquals(1, events.size)
        assertEquals(dedupeKey(threshold = 10.0, resetAt = newResetAt), events.single().key)
    }

    @Test
    fun `missing reset suppresses alert`() {
        val events = policy.evaluate(
            state = state(
                usedPercent = 90,
                resetAt = null,
                primaryWindowCanAlert = true,
            ),
        )

        assertTrue(events.isEmpty())
    }

    @Test
    fun `missing or invalid percent suppresses alert`() {
        val missingPercentEvents = policy.evaluate(
            state = state(
                usedPercent = null,
                primaryWindowCanAlert = true,
            ),
        )
        val invalidPercentEvents = policy.evaluate(
            state = state(
                usedPercent = 120,
                primaryWindowCanAlert = true,
            ),
        )

        assertTrue(missingPercentEvents.isEmpty())
        assertTrue(invalidPercentEvents.isEmpty())
    }

    @Test
    fun `evaluates both five hour and weekly windows when both are enabled`() {
        val events = policy.evaluate(
            state = state(
                usedPercent = 90,
                snapshotWindows = listOf(
                    quotaWindow(
                        windowId = QuotaWindowId("five_hour"),
                        usedPercent = 90,
                        resetAt = resetAt,
                    ),
                    quotaWindow(
                        windowId = QuotaWindowId("weekly"),
                        usedPercent = 100,
                        resetAt = Instant.parse("2026-05-25T00:00:00Z"),
                    ),
                ),
            ),
            enabledWindowIds = setOf(QuotaWindowId("five_hour"), QuotaWindowId("weekly")),
        )

        assertEquals(listOf(QuotaWindowId("five_hour"), QuotaWindowId("weekly")), events.map { it.windowId })
        assertEquals(listOf(AlertLevel.Warning, AlertLevel.Limit), events.map { it.level })
        assertEquals(
            listOf(
                dedupeKey(threshold = 10.0),
                dedupeKey(
                    windowId = QuotaWindowId("weekly"),
                    threshold = 0.0,
                    resetAt = Instant.parse("2026-05-25T00:00:00Z"),
                ),
            ),
            events.map { it.key },
        )
    }

    @Test
    fun `disabled window suppresses alert`() {
        val events = policy.evaluate(
            state = state(
                usedPercent = 50,
                snapshotWindows = listOf(
                    quotaWindow(
                        windowId = QuotaWindowId("five_hour"),
                        usedPercent = 50,
                        resetAt = resetAt,
                    ),
                    quotaWindow(
                        windowId = QuotaWindowId("weekly"),
                        usedPercent = 100,
                        resetAt = Instant.parse("2026-05-25T00:00:00Z"),
                    ),
                ),
            ),
            enabledWindowIds = setOf(QuotaWindowId("five_hour")),
        )

        assertTrue(events.isEmpty())
    }

    @Test
    fun `caution threshold crossing never emits notification`() {
        val events = policy.evaluate(
            state = state(usedPercent = 70),
            thresholds = AlertThresholds(caution = 30, warning = 10, limit = 0),
        )

        assertTrue(events.isEmpty())
    }

    @Test
    fun `threshold change emits new event for same reset when new threshold crosses warning`() {
        val events = policy.evaluate(
            state = state(usedPercent = 85),
            alreadyNotified = setOf(dedupeKey(threshold = 10.0)),
            thresholds = AlertThresholds(caution = 30, warning = 20, limit = 0),
        )

        assertEquals(1, events.size)
        assertEquals(20.0, events.single().threshold, 0.0)
        assertEquals(dedupeKey(threshold = 20.0), events.single().key)
    }

    private fun dedupeKey(
        windowId: QuotaWindowId = QuotaWindowId("five_hour"),
        threshold: Double,
        resetAt: Instant = this.resetAt,
    ): AlertDedupeKey =
        AlertDedupeKey(
            providerId = ProviderId("codex"),
            localAccountId = LocalAccountId("local-1"),
            windowId = windowId,
            resetAt = resetAt,
            threshold = threshold,
        )

    private fun state(
        usedPercent: Int?,
        resetAt: Instant? = this.resetAt,
        primaryWindowCanAlert: Boolean = usedPercent in 0..100 && resetAt != null,
        snapshotWindows: List<QuotaWindow>? = null,
    ): CurrentQuotaState {
        val primaryWindow = quotaWindow(
            windowId = QuotaWindowId("five_hour"),
            usedPercent = usedPercent,
            resetAt = resetAt,
        )
        return CurrentQuotaState(
            status = CurrentQuotaStatus.Fresh,
            freshness = CurrentQuotaFreshness.Fresh,
            account = account(),
            snapshot = quotaSnapshot(windows = snapshotWindows ?: listOf(primaryWindow)),
            latestAttempt = null,
            primaryWindow = primaryWindow,
            secondaryWindows = listOf(
                quotaWindow(
                    windowId = QuotaWindowId("weekly"),
                    usedPercent = 100,
                    resetAt = Instant.parse("2026-05-25T00:00:00Z"),
                ),
            ),
            primaryWindowCanAlert = primaryWindowCanAlert,
            error = null,
        )
    }

    private fun stateWithoutPrimaryWindow(): CurrentQuotaState {
        val secondaryWindow = quotaWindow(
            windowId = QuotaWindowId("weekly"),
            usedPercent = 100,
            resetAt = Instant.parse("2026-05-25T00:00:00Z"),
        )
        return CurrentQuotaState(
            status = CurrentQuotaStatus.Fresh,
            freshness = CurrentQuotaFreshness.Fresh,
            account = account(),
            snapshot = quotaSnapshot(windows = listOf(secondaryWindow)),
            latestAttempt = null,
            primaryWindow = null,
            secondaryWindows = listOf(secondaryWindow),
            primaryWindowCanAlert = false,
            error = null,
        )
    }

    private fun account(): ProviderAccount =
        ProviderAccount.createNew(
            localAccountId = LocalAccountId("local-1"),
            providerId = ProviderId("codex"),
            providerAccountId = ProviderAccountId("acct-1"),
            displayName = "Codex Main",
            now = Instant.parse("2026-05-23T09:00:00Z"),
        )

    private fun quotaSnapshot(primaryWindow: QuotaWindow): QuotaSnapshot =
        quotaSnapshot(windows = listOf(primaryWindow))

    private fun quotaSnapshot(windows: List<QuotaWindow>): QuotaSnapshot =
        QuotaSnapshot(
            snapshotId = SnapshotId("snapshot-1"),
            providerId = ProviderId("codex"),
            localAccountId = LocalAccountId("local-1"),
            providerAccountId = ProviderAccountId("acct-1"),
            fetchedAt = Instant.parse("2026-05-23T10:00:00Z"),
            source = QuotaSnapshotSource.ManualRefresh,
            planType = "plus",
            windows = windows,
            credits = null,
            responseDigest = "safe-digest",
        )

    private fun quotaWindow(
        windowId: QuotaWindowId,
        usedPercent: Int?,
        resetAt: Instant?,
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
}
