package com.kmnexus.codexmeter.domain.quota

import com.kmnexus.codexmeter.domain.model.LocalAccountId
import com.kmnexus.codexmeter.domain.model.ProviderAccount
import com.kmnexus.codexmeter.domain.model.ProviderAccountId
import com.kmnexus.codexmeter.domain.model.ProviderId
import com.kmnexus.codexmeter.domain.model.QuotaWindowId
import com.kmnexus.codexmeter.domain.model.RefreshAttemptId
import com.kmnexus.codexmeter.domain.model.SnapshotId
import com.kmnexus.codexmeter.domain.refresh.QuotaError
import com.kmnexus.codexmeter.domain.refresh.RefreshAttempt
import com.kmnexus.codexmeter.domain.refresh.RefreshAttemptStatus
import com.kmnexus.codexmeter.domain.refresh.RefreshTrigger
import java.time.Duration
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class CurrentQuotaStateFactoryTest {
    private val now = Instant.parse("2026-05-23T10:00:00Z")
    private val factory = CurrentQuotaStateFactory()

    @Test
    fun `no account derives unauthenticated state`() {
        val state = factory.create(
            account = null,
            latestSnapshot = null,
            latestAttempt = null,
            now = now,
        )

        assertEquals(CurrentQuotaStatus.Unauthenticated, state.status)
        assertEquals(CurrentQuotaFreshness.Unknown, state.freshness)
        assertNull(state.account)
        assertNull(state.snapshot)
        assertNull(state.primaryWindow)
    }

    @Test
    fun `snapshot under thirty minutes derives fresh state`() {
        val snapshot = quotaSnapshot(fetchedAt = now.minus(Duration.ofMinutes(29)))

        val state = factory.create(
            account = account(),
            latestSnapshot = snapshot,
            latestAttempt = null,
            now = now,
        )

        assertEquals(CurrentQuotaStatus.Fresh, state.status)
        assertEquals(CurrentQuotaFreshness.Fresh, state.freshness)
        assertSame(snapshot, state.snapshot)
        assertEquals(QuotaWindowId("five_hour"), state.primaryWindow?.windowId)
    }

    @Test
    fun `snapshot over thirty minutes derives possibly stale state`() {
        val state = factory.create(
            account = account(),
            latestSnapshot = quotaSnapshot(fetchedAt = now.minus(Duration.ofMinutes(31))),
            latestAttempt = null,
            now = now,
        )

        assertEquals(CurrentQuotaStatus.PossiblyStale, state.status)
        assertEquals(CurrentQuotaFreshness.PossiblyStale, state.freshness)
    }

    @Test
    fun `snapshot at two hours derives expired state`() {
        val state = factory.create(
            account = account(),
            latestSnapshot = quotaSnapshot(fetchedAt = now.minus(Duration.ofHours(2))),
            latestAttempt = null,
            now = now,
        )

        assertEquals(CurrentQuotaStatus.Expired, state.status)
        assertEquals(CurrentQuotaFreshness.Expired, state.freshness)
    }

    @Test
    fun `failed refresh keeps last known good quota visible`() {
        val snapshot = quotaSnapshot(fetchedAt = now.minus(Duration.ofMinutes(5)))
        val failedAttempt = refreshAttempt(
            status = RefreshAttemptStatus.Failed,
            httpStatus = 503,
            retryable = true,
            userActionRequired = false,
        )

        val state = factory.create(
            account = account(),
            latestSnapshot = snapshot,
            latestAttempt = failedAttempt,
            now = now,
        )

        assertEquals(CurrentQuotaStatus.ErrorWithLastKnownGood, state.status)
        assertEquals(CurrentQuotaFreshness.Fresh, state.freshness)
        assertSame(snapshot, state.snapshot)
        assertEquals(38, state.primaryWindow?.displayPercent)
        assertSame(failedAttempt, state.latestAttempt)
        assertTrue(state.error is QuotaError.Network)
    }

    @Test
    fun `auth required attempt wins over existing snapshot`() {
        val snapshot = quotaSnapshot(fetchedAt = now.minus(Duration.ofMinutes(5)))
        val authAttempt = refreshAttempt(
            status = RefreshAttemptStatus.Failed,
            httpStatus = 401,
            retryable = false,
            userActionRequired = true,
        )

        val state = factory.create(
            account = account(),
            latestSnapshot = snapshot,
            latestAttempt = authAttempt,
            now = now,
        )

        assertEquals(CurrentQuotaStatus.AuthRequired, state.status)
        assertEquals(CurrentQuotaFreshness.Fresh, state.freshness)
        assertSame(snapshot, state.snapshot)
        assertTrue(state.error is QuotaError.AuthRequired)
    }

    @Test
    fun `terminal refresh error code requires auth while preserving snapshot`() {
        val snapshot = quotaSnapshot(fetchedAt = now.minus(Duration.ofMinutes(5)))
        val terminalAttempt = refreshAttempt(
            status = RefreshAttemptStatus.Failed,
            httpStatus = null,
            retryable = false,
            userActionRequired = false,
            errorCode = "invalid_grant",
        )

        val state = factory.create(
            account = account(),
            latestSnapshot = snapshot,
            latestAttempt = terminalAttempt,
            now = now,
        )

        assertEquals(CurrentQuotaStatus.AuthRequired, state.status)
        assertEquals(CurrentQuotaFreshness.Fresh, state.freshness)
        assertSame(snapshot, state.snapshot)
        assertTrue(state.error is QuotaError.AuthRequired)
    }

    @Test
    fun `missing primary window is not alertable`() {
        val state = factory.create(
            account = account(),
            latestSnapshot = quotaSnapshot(
                fetchedAt = now.minus(Duration.ofMinutes(5)),
                primaryWindow = quotaWindow(
                    windowId = QuotaWindowId("five_hour"),
                    usedPercent = null,
                    resetAt = null,
                    availability = QuotaWindowAvailability.Missing,
                ),
            ),
            latestAttempt = null,
            now = now,
        )

        assertEquals(CurrentQuotaStatus.Fresh, state.status)
        assertEquals(false, state.primaryWindowCanAlert)
    }

    @Test
    fun `out of range primary window percent is not alertable`() {
        val state = factory.create(
            account = account(),
            latestSnapshot = quotaSnapshot(
                fetchedAt = now.minus(Duration.ofMinutes(5)),
                primaryWindow = quotaWindow(
                    windowId = QuotaWindowId("five_hour"),
                    usedPercent = 120,
                    resetAt = Instant.parse("2026-05-23T12:00:00Z"),
                    availability = QuotaWindowAvailability.Available,
                ),
            ),
            latestAttempt = null,
            now = now,
        )

        assertEquals(CurrentQuotaStatus.Fresh, state.status)
        assertEquals(0, state.primaryWindow?.displayPercent)
        assertEquals(false, state.primaryWindowCanAlert)
    }

    @Test
    fun `balance window with positive amount is alertable`() {
        val primaryWindowId = QuotaWindowId("balance")
        val state = factory.create(
            account = account(),
            latestSnapshot = quotaSnapshot(
                fetchedAt = now.minus(Duration.ofMinutes(5)),
                primaryWindow = QuotaWindow(
                    windowId = primaryWindowId,
                    titleKey = "test",
                    usedPercent = null,
                    resetAt = null,
                    limitWindowSeconds = null,
                    isPrimaryCandidate = true,
                    availability = QuotaWindowAvailability.Available,
                    displayKind = QuotaWindowDisplayKind.Balance,
                    balanceAmount = "9.49",
                ),
            ),
            latestAttempt = null,
            now = now,
            primaryWindowId = primaryWindowId,
        )

        assertEquals(true, state.primaryWindowCanAlert)
    }

    @Test
    fun `balance window with zero amount is not alertable`() {
        val primaryWindowId = QuotaWindowId("balance")
        val state = factory.create(
            account = account(),
            latestSnapshot = quotaSnapshot(
                fetchedAt = now.minus(Duration.ofMinutes(5)),
                primaryWindow = QuotaWindow(
                    windowId = primaryWindowId,
                    titleKey = "test",
                    usedPercent = null,
                    resetAt = null,
                    limitWindowSeconds = null,
                    isPrimaryCandidate = true,
                    availability = QuotaWindowAvailability.Available,
                    displayKind = QuotaWindowDisplayKind.Balance,
                    balanceAmount = "0.00",
                ),
            ),
            latestAttempt = null,
            now = now,
            primaryWindowId = primaryWindowId,
        )

        assertEquals(false, state.primaryWindowCanAlert)
    }

    @Test
    fun `balance window with null amount is not alertable`() {
        val primaryWindowId = QuotaWindowId("balance")
        val state = factory.create(
            account = account(),
            latestSnapshot = quotaSnapshot(
                fetchedAt = now.minus(Duration.ofMinutes(5)),
                primaryWindow = QuotaWindow(
                    windowId = primaryWindowId,
                    titleKey = "test",
                    usedPercent = null,
                    resetAt = null,
                    limitWindowSeconds = null,
                    isPrimaryCandidate = true,
                    availability = QuotaWindowAvailability.Available,
                    displayKind = QuotaWindowDisplayKind.Balance,
                    balanceAmount = null,
                ),
            ),
            latestAttempt = null,
            now = now,
            primaryWindowId = primaryWindowId,
        )

        assertEquals(false, state.primaryWindowCanAlert)
    }

    @Test
    fun `usage count window with positive used count is alertable`() {
        val primaryWindowId = QuotaWindowId("count")
        val state = factory.create(
            account = account(),
            latestSnapshot = quotaSnapshot(
                fetchedAt = now.minus(Duration.ofMinutes(5)),
                primaryWindow = QuotaWindow(
                    windowId = primaryWindowId,
                    titleKey = "test",
                    usedPercent = null,
                    resetAt = null,
                    limitWindowSeconds = null,
                    isPrimaryCandidate = true,
                    availability = QuotaWindowAvailability.Available,
                    displayKind = QuotaWindowDisplayKind.UsageCount,
                    usedCount = 50,
                    limitCount = 500,
                ),
            ),
            latestAttempt = null,
            now = now,
            primaryWindowId = primaryWindowId,
        )

        assertEquals(true, state.primaryWindowCanAlert)
    }

    @Test
    fun `usage count window with zero used count is not alertable`() {
        val primaryWindowId = QuotaWindowId("count")
        val state = factory.create(
            account = account(),
            latestSnapshot = quotaSnapshot(
                fetchedAt = now.minus(Duration.ofMinutes(5)),
                primaryWindow = QuotaWindow(
                    windowId = primaryWindowId,
                    titleKey = "test",
                    usedPercent = null,
                    resetAt = null,
                    limitWindowSeconds = null,
                    isPrimaryCandidate = true,
                    availability = QuotaWindowAvailability.Available,
                    displayKind = QuotaWindowDisplayKind.UsageCount,
                    usedCount = 0,
                    limitCount = 500,
                ),
            ),
            latestAttempt = null,
            now = now,
            primaryWindowId = primaryWindowId,
        )

        assertEquals(false, state.primaryWindowCanAlert)
    }

    @Test
    fun `usage count window with null used count is not alertable`() {
        val primaryWindowId = QuotaWindowId("count")
        val state = factory.create(
            account = account(),
            latestSnapshot = quotaSnapshot(
                fetchedAt = now.minus(Duration.ofMinutes(5)),
                primaryWindow = QuotaWindow(
                    windowId = primaryWindowId,
                    titleKey = "test",
                    usedPercent = null,
                    resetAt = null,
                    limitWindowSeconds = null,
                    isPrimaryCandidate = true,
                    availability = QuotaWindowAvailability.Available,
                    displayKind = QuotaWindowDisplayKind.UsageCount,
                    usedCount = null,
                    limitCount = 500,
                ),
            ),
            latestAttempt = null,
            now = now,
            primaryWindowId = primaryWindowId,
        )

        assertEquals(false, state.primaryWindowCanAlert)
    }

    @Test
    fun `usage count window with null limit count is not alertable`() {
        val primaryWindowId = QuotaWindowId("count")
        val state = factory.create(
            account = account(),
            latestSnapshot = quotaSnapshot(
                fetchedAt = now.minus(Duration.ofMinutes(5)),
                primaryWindow = QuotaWindow(
                    windowId = primaryWindowId,
                    titleKey = "test",
                    usedPercent = null,
                    resetAt = null,
                    limitWindowSeconds = null,
                    isPrimaryCandidate = true,
                    availability = QuotaWindowAvailability.Available,
                    displayKind = QuotaWindowDisplayKind.UsageCount,
                    usedCount = 50,
                    limitCount = null,
                ),
            ),
            latestAttempt = null,
            now = now,
            primaryWindowId = primaryWindowId,
        )

        assertEquals(false, state.primaryWindowCanAlert)
    }

    private fun account(): ProviderAccount =
        ProviderAccount.createNew(
            localAccountId = LocalAccountId("local-1"),
            providerId = ProviderId("codex"),
            providerAccountId = ProviderAccountId("acct-1"),
            displayName = "Codex Main",
            now = Instant.parse("2026-05-23T09:00:00Z"),
        )

    private fun quotaSnapshot(
        fetchedAt: Instant,
        primaryWindow: QuotaWindow = quotaWindow(
            windowId = QuotaWindowId("five_hour"),
            usedPercent = 62,
            resetAt = Instant.parse("2026-05-23T12:00:00Z"),
        ),
    ): QuotaSnapshot =
        QuotaSnapshot(
            snapshotId = SnapshotId("snapshot-1"),
            providerId = ProviderId("codex"),
            localAccountId = LocalAccountId("local-1"),
            providerAccountId = ProviderAccountId("acct-1"),
            fetchedAt = fetchedAt,
            source = QuotaSnapshotSource.ManualRefresh,
            planType = "plus",
            windows = listOf(
                primaryWindow,
                quotaWindow(
                    windowId = QuotaWindowId("weekly"),
                    usedPercent = 41,
                    resetAt = Instant.parse("2026-05-25T00:00:00Z"),
                ),
            ),
            credits = null,
            responseDigest = "safe-digest",
        )

    private fun quotaWindow(
        windowId: QuotaWindowId,
        usedPercent: Int?,
        resetAt: Instant?,
        availability: QuotaWindowAvailability = QuotaWindowAvailability.Available,
    ): QuotaWindow =
        QuotaWindow(
            windowId = windowId,
            titleKey = "quota_window_${windowId.value}",
            usedPercent = usedPercent,
            resetAt = resetAt,
            limitWindowSeconds = 18_000,
            isPrimaryCandidate = true,
            availability = availability,
        )

    private fun refreshAttempt(
        status: RefreshAttemptStatus,
        httpStatus: Int?,
        retryable: Boolean,
        userActionRequired: Boolean,
        errorCode: String? = null,
    ): RefreshAttempt =
        RefreshAttempt(
            attemptId = RefreshAttemptId("attempt-1"),
            providerId = ProviderId("codex"),
            localAccountId = LocalAccountId("local-1"),
            trigger = RefreshTrigger.Manual,
            startedAt = now.minus(Duration.ofSeconds(30)),
            finishedAt = now.minus(Duration.ofSeconds(20)),
            status = status,
            errorCode = errorCode,
            httpStatus = httpStatus,
            retryable = retryable,
            userActionRequired = userActionRequired,
            diagnosticsDigest = "safe-digest",
        )
}
