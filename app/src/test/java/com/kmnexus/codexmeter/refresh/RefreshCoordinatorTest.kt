package com.kmnexus.codexmeter.refresh

import com.kmnexus.codexmeter.domain.model.LocalAccountId
import com.kmnexus.codexmeter.domain.model.ProviderAccount
import com.kmnexus.codexmeter.domain.model.ProviderAccountId
import com.kmnexus.codexmeter.domain.model.ProviderId
import com.kmnexus.codexmeter.domain.model.QuotaWindowId
import com.kmnexus.codexmeter.domain.model.RefreshAttemptId
import com.kmnexus.codexmeter.domain.model.SnapshotId
import com.kmnexus.codexmeter.domain.quota.CurrentQuotaState
import com.kmnexus.codexmeter.domain.quota.CurrentQuotaStatus
import com.kmnexus.codexmeter.domain.quota.QuotaSnapshot
import com.kmnexus.codexmeter.domain.quota.QuotaSnapshotSource
import com.kmnexus.codexmeter.domain.quota.QuotaWindow
import com.kmnexus.codexmeter.domain.quota.QuotaWindowAvailability
import com.kmnexus.codexmeter.domain.refresh.QuotaError
import com.kmnexus.codexmeter.domain.refresh.RefreshAttempt
import com.kmnexus.codexmeter.domain.refresh.RefreshAttemptStatus
import com.kmnexus.codexmeter.domain.refresh.RefreshTrigger
import com.kmnexus.codexmeter.domain.settings.PrimaryQuotaWindowPreferenceReader
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RefreshCoordinatorTest {
    private val now = Instant.parse("2026-05-23T12:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val account = account()

    @Test
    fun `concurrent refresh for same account calls provider once`() = runTest {
        val providerStarted = CompletableDeferred<Unit>()
        val providerMayFinish = CompletableDeferred<Unit>()
        val snapshot = quotaSnapshot("snapshot-1")
        val provider = RecordingRefreshProvider {
            providerStarted.complete(Unit)
            providerMayFinish.await()
            ProviderRefreshResult.Success(snapshot)
        }
        val snapshotStore = InMemorySnapshotStore()
        val attemptStore = RecordingAttemptStore()
        val coordinator = coordinator(
            provider = provider,
            snapshotStore = snapshotStore,
            attemptStore = attemptStore,
        )

        val first = async { coordinator.refresh(account, RefreshTrigger.Manual) }
        providerStarted.await()
        val second = async { coordinator.refresh(account, RefreshTrigger.Manual) }
        providerMayFinish.complete(Unit)
        val results = awaitAll(first, second)

        assertEquals(1, provider.callCount)
        assertEquals(1, snapshotStore.savedSnapshots.size)
        assertEquals(1, attemptStore.savedAttempts.size)
        assertSame(snapshot, (results[0] as RefreshResult.Success).snapshot)
        assertSame(snapshot, (results[1] as RefreshResult.Success).snapshot)
    }

    @Test
    fun `same account follow-up refresh after shared success starts new provider call`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val providerStarted = CompletableDeferred<Unit>()
        val providerMayFinish = CompletableDeferred<Unit>()
        val firstSnapshot = quotaSnapshot("snapshot-first")
        val followUpSnapshot = quotaSnapshot("snapshot-follow-up")
        val snapshots = ArrayDeque(listOf(firstSnapshot, followUpSnapshot))
        val provider = RecordingRefreshProvider {
            providerStarted.complete(Unit)
            providerMayFinish.await()
            ProviderRefreshResult.Success(snapshots.removeFirst())
        }
        val snapshotStore = InMemorySnapshotStore()
        val attemptStore = RecordingAttemptStore()
        val coordinator = coordinator(
            provider = provider,
            snapshotStore = snapshotStore,
            attemptStore = attemptStore,
        )

        val first = async { coordinator.refresh(account, RefreshTrigger.Manual) }
        providerStarted.await()
        val secondAndFollowUp = async(dispatcher) {
            val sharedResult = coordinator.refresh(account, RefreshTrigger.Manual)
            val followUpResult = coordinator.refresh(account, RefreshTrigger.Manual)
            sharedResult to followUpResult
        }
        providerMayFinish.complete(Unit)
        val firstResult = first.await()
        val (secondResult, followUpResult) = secondAndFollowUp.await()

        assertEquals(2, provider.callCount)
        assertEquals(listOf(firstSnapshot, followUpSnapshot), snapshotStore.savedSnapshots)
        assertEquals(2, attemptStore.savedAttempts.size)
        assertSame(firstSnapshot, (firstResult as RefreshResult.Success).snapshot)
        assertSame(firstSnapshot, (secondResult as RefreshResult.Success).snapshot)
        assertSame(followUpSnapshot, (followUpResult as RefreshResult.Success).snapshot)
    }

    @Test
    fun `same account follow-up refresh after provider throws starts new provider call`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val providerStarted = CompletableDeferred<Unit>()
        val providerMayThrow = CompletableDeferred<Unit>()
        val recoveredSnapshot = quotaSnapshot("snapshot-recovered")
        var providerCalls = 0
        val provider = RecordingRefreshProvider {
            providerCalls += 1
            if (providerCalls == 1) {
                providerStarted.complete(Unit)
                providerMayThrow.await()
                throw TestRefreshException()
            }
            ProviderRefreshResult.Success(recoveredSnapshot)
        }
        val snapshotStore = InMemorySnapshotStore()
        val coordinator = coordinator(
            provider = provider,
            snapshotStore = snapshotStore,
        )

        val first = async { runCatching { coordinator.refresh(account, RefreshTrigger.Manual) } }
        providerStarted.await()
        val secondAndFollowUp = async(dispatcher) {
            val sharedFailure = runCatching { coordinator.refresh(account, RefreshTrigger.Manual) }.exceptionOrNull()
            val followUpResult = runCatching { coordinator.refresh(account, RefreshTrigger.Manual) }
            sharedFailure to followUpResult
        }
        providerMayThrow.complete(Unit)
        val firstFailure = first.await().exceptionOrNull()
        val (sharedFailure, followUpResult) = secondAndFollowUp.await()

        assertTrue(firstFailure is TestRefreshException)
        assertTrue(sharedFailure is TestRefreshException)
        assertEquals(null, followUpResult.exceptionOrNull())
        assertEquals(2, provider.callCount)
        assertEquals(listOf(recoveredSnapshot), snapshotStore.savedSnapshots)
        assertSame(recoveredSnapshot, (followUpResult.getOrThrow() as RefreshResult.Success).snapshot)
    }

    @Test
    fun `owner cancellation completes shared waiters and clears same account flight`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val providerStarted = CompletableDeferred<Unit>()
        val providerMayFinish = CompletableDeferred<Unit>()
        val cleanupEntered = CompletableDeferred<Unit>()
        val cleanupMayContinue = CompletableDeferred<Unit>()
        val recoveredSnapshot = quotaSnapshot("snapshot-recovered")
        var providerCalls = 0
        val provider = RecordingRefreshProvider {
            providerCalls += 1
            if (providerCalls == 1) {
                providerStarted.complete(Unit)
                providerMayFinish.await()
                ProviderRefreshResult.Success(quotaSnapshot("snapshot-unreachable"))
            } else {
                ProviderRefreshResult.Success(recoveredSnapshot)
            }
        }
        val snapshotStore = InMemorySnapshotStore()
        val coordinator = coordinator(
            provider = provider,
            snapshotStore = snapshotStore,
            beforeFlightRemoval = {
                cleanupEntered.complete(Unit)
                cleanupMayContinue.await()
            },
        )

        val owner = async { coordinator.refresh(account, RefreshTrigger.Manual) }
        providerStarted.await()
        val waitingFollower = async(dispatcher) {
            runCatching {
                withTimeout(1_000) {
                    coordinator.refresh(account, RefreshTrigger.Manual)
                }
            }
        }
        owner.cancel()
        cleanupEntered.await()
        cleanupMayContinue.complete(Unit)
        owner.join()

        val followerFailure = waitingFollower.await().exceptionOrNull()
        assertTrue(followerFailure is CancellationException)
        assertTrue(followerFailure !is TimeoutCancellationException)

        val laterResult = withTimeout(1_000) {
            coordinator.refresh(account, RefreshTrigger.Manual)
        }

        assertEquals(2, provider.callCount)
        assertEquals(listOf(recoveredSnapshot), snapshotStore.savedSnapshots)
        assertSame(recoveredSnapshot, (laterResult as RefreshResult.Success).snapshot)
    }

    @Test
    fun `successful refresh stores snapshot and attempt`() = runTest {
        val snapshot = quotaSnapshot("snapshot-1")
        val snapshotStore = InMemorySnapshotStore()
        val attemptStore = RecordingAttemptStore()
        val coordinator = coordinator(
            provider = RecordingRefreshProvider { ProviderRefreshResult.Success(snapshot) },
            snapshotStore = snapshotStore,
            attemptStore = attemptStore,
        )

        val result = coordinator.refresh(account, RefreshTrigger.Manual)

        assertEquals(RefreshResult.Success(snapshot), result)
        assertEquals(listOf(snapshot), snapshotStore.savedSnapshots)
        assertEquals(snapshot, snapshotStore.latestFor(account))
        assertEquals(1, attemptStore.savedAttempts.size)
        val attempt = attemptStore.savedAttempts.single()
        assertEquals(RefreshAttemptId("attempt-1"), attempt.attemptId)
        assertEquals(ProviderId("codex"), attempt.providerId)
        assertEquals(LocalAccountId("local-1"), attempt.localAccountId)
        assertEquals(RefreshTrigger.Manual, attempt.trigger)
        assertEquals(now, attempt.startedAt)
        assertEquals(now, attempt.finishedAt)
        assertEquals(RefreshAttemptStatus.Success, attempt.status)
        assertEquals(null, attempt.errorCode)
        assertEquals(null, attempt.httpStatus)
        assertEquals(null, attempt.retryable)
        assertEquals(null, attempt.userActionRequired)
        assertEquals(null, attempt.diagnosticsDigest)
    }

    @Test
    fun `successful refresh publishes current quota state for widget projection`() = runTest {
        val snapshot = quotaSnapshot("snapshot-1")
        val publisher = RecordingCurrentQuotaStatePublisher()
        val coordinator = coordinator(
            provider = RecordingRefreshProvider { ProviderRefreshResult.Success(snapshot) },
            currentQuotaStatePublisher = publisher,
        )

        coordinator.refresh(account, RefreshTrigger.Manual)

        assertEquals(1, publisher.publishedStates.size)
        val state = publisher.publishedStates.single()
        assertEquals(CurrentQuotaStatus.Fresh, state.status)
        assertEquals(snapshot, state.snapshot)
        assertEquals(snapshot.windows.single(), state.primaryWindow)
    }

    @Test
    fun `successful refresh publishes selected primary quota window`() = runTest {
        val snapshot = quotaSnapshot(
            "snapshot-1",
            windows = listOf(
                quotaWindow(windowId = QuotaWindowId("five_hour"), usedPercent = 62),
                quotaWindow(windowId = QuotaWindowId("weekly"), usedPercent = 41),
            ),
        )
        val publisher = RecordingCurrentQuotaStatePublisher()
        val coordinator = coordinator(
            provider = RecordingRefreshProvider { ProviderRefreshResult.Success(snapshot) },
            currentQuotaStatePublisher = publisher,
            primaryWindowId = QuotaWindowId("weekly"),
        )

        coordinator.refresh(account, RefreshTrigger.Manual)

        val state = publisher.publishedStates.single()
        assertEquals(QuotaWindowId("weekly"), state.primaryWindow?.windowId)
        assertEquals(41, state.primaryWindow?.usedPercent)
    }

    @Test
    fun `failed refresh stores failed attempt and leaves latest snapshot unchanged`() = runTest {
        val previousSnapshot = quotaSnapshot("snapshot-existing", fetchedAt = now.minus(Duration.ofMinutes(20)))
        val snapshotStore = InMemorySnapshotStore(initialSnapshots = listOf(previousSnapshot))
        val attemptStore = RecordingAttemptStore()
        val error = QuotaError.AuthRequired(
            httpStatus = 401,
            diagnosticsDigest = "safe-auth-digest",
        )
        val coordinator = coordinator(
            provider = RecordingRefreshProvider { ProviderRefreshResult.Failure(error) },
            snapshotStore = snapshotStore,
            attemptStore = attemptStore,
        )

        val result = coordinator.refresh(account, RefreshTrigger.Periodic)

        assertEquals(RefreshResult.Failure(error = error, lastKnownGood = previousSnapshot), result)
        assertEquals(emptyList<QuotaSnapshot>(), snapshotStore.savedSnapshots)
        assertEquals(previousSnapshot, snapshotStore.latestFor(account))
        assertEquals(1, attemptStore.savedAttempts.size)
        val attempt = attemptStore.savedAttempts.single()
        assertEquals(RefreshAttemptId("attempt-1"), attempt.attemptId)
        assertEquals(ProviderId("codex"), attempt.providerId)
        assertEquals(LocalAccountId("local-1"), attempt.localAccountId)
        assertEquals(RefreshTrigger.Periodic, attempt.trigger)
        assertEquals(now, attempt.startedAt)
        assertEquals(now, attempt.finishedAt)
        assertEquals(RefreshAttemptStatus.Failed, attempt.status)
        assertEquals("error_auth_required", attempt.errorCode)
        assertEquals(401, attempt.httpStatus)
        assertEquals(false, attempt.retryable)
        assertEquals(true, attempt.userActionRequired)
        assertEquals("safe-auth-digest", attempt.diagnosticsDigest)
    }

    @Test
    fun `failed refresh publishes last known good state for widget projection`() = runTest {
        val previousSnapshot = quotaSnapshot("snapshot-existing", fetchedAt = now.minus(Duration.ofMinutes(20)))
        val publisher = RecordingCurrentQuotaStatePublisher()
        val error = QuotaError.Network(diagnosticsDigest = "safe-network-digest")
        val coordinator = coordinator(
            provider = RecordingRefreshProvider { ProviderRefreshResult.Failure(error) },
            snapshotStore = InMemorySnapshotStore(initialSnapshots = listOf(previousSnapshot)),
            currentQuotaStatePublisher = publisher,
        )

        coordinator.refresh(account, RefreshTrigger.Periodic)

        assertEquals(1, publisher.publishedStates.size)
        val state = publisher.publishedStates.single()
        assertEquals(CurrentQuotaStatus.ErrorWithLastKnownGood, state.status)
        assertEquals(previousSnapshot, state.snapshot)
        assertEquals("error_network", state.latestAttempt?.errorCode)
        assertEquals("safe-network-digest", state.latestAttempt?.diagnosticsDigest)
    }

    @Test
    fun `auth required refresh marks account as needing reauth`() = runTest {
        val accountStatusStore = RecordingRefreshAccountStatusStore()
        val error = QuotaError.AuthRequired(
            httpStatus = 401,
            diagnosticsDigest = "safe-auth-digest",
        )
        val coordinator = coordinator(
            provider = RecordingRefreshProvider { ProviderRefreshResult.Failure(error) },
            accountStatusStore = accountStatusStore,
        )

        coordinator.refresh(account, RefreshTrigger.Periodic)

        assertEquals(listOf(account to now), accountStatusStore.needsReauthAccounts)
    }

    @Test
    fun `successful refresh clears a stale needs-reauth flag and publishes active state`() = runTest {
        val accountStatusStore = RecordingRefreshAccountStatusStore()
        val publisher = RecordingCurrentQuotaStatePublisher()
        val needsReauthAccount = account.copy(status = com.kmnexus.codexmeter.domain.model.AccountStatus.NeedsReauth)
        val coordinator = coordinator(
            provider = RecordingRefreshProvider { ProviderRefreshResult.Success(quotaSnapshot("snapshot-1")) },
            accountStatusStore = accountStatusStore,
            currentQuotaStatePublisher = publisher,
        )

        coordinator.refresh(needsReauthAccount, RefreshTrigger.Periodic)

        assertEquals(listOf(needsReauthAccount to now), accountStatusStore.activeAccounts)
        assertEquals(CurrentQuotaStatus.Fresh, publisher.publishedStates.single().status)
    }

    private fun coordinator(
        provider: RefreshProvider,
        snapshotStore: SnapshotStore = InMemorySnapshotStore(),
        attemptStore: RefreshAttemptStore = RecordingAttemptStore(),
        accountStatusStore: RefreshAccountStatusStore = RecordingRefreshAccountStatusStore(),
        currentQuotaStatePublisher: CurrentQuotaStatePublisher = RecordingCurrentQuotaStatePublisher(),
        primaryWindowId: QuotaWindowId = QuotaWindowId("five_hour"),
        beforeFlightRemoval: suspend () -> Unit = {},
    ): RefreshCoordinator =
        RefreshCoordinator(
            provider = provider,
            snapshotStore = snapshotStore,
            attemptStore = attemptStore,
            accountStatusStore = accountStatusStore,
            currentQuotaStatePublisher = currentQuotaStatePublisher,
            primaryQuotaWindowPreferenceReader = StaticPrimaryQuotaWindowPreferenceReader(primaryWindowId),
            attemptIdProvider = SequentialAttemptIdProvider(),
            clock = clock,
        ).also {
            it.beforeFlightRemoval = beforeFlightRemoval
        }

    private class RecordingRefreshProvider(
        private val result: suspend () -> ProviderRefreshResult,
    ) : RefreshProvider {
        var callCount = 0
            private set

        override suspend fun refresh(
            account: ProviderAccount,
            trigger: RefreshTrigger,
        ): ProviderRefreshResult {
            callCount += 1
            return result()
        }
    }

    private class InMemorySnapshotStore(
        initialSnapshots: List<QuotaSnapshot> = emptyList(),
    ) : SnapshotStore {
        private val latestSnapshots = initialSnapshots.associateBy { it.providerId to it.localAccountId }.toMutableMap()
        val savedSnapshots = mutableListOf<QuotaSnapshot>()

        override suspend fun save(snapshot: QuotaSnapshot) {
            savedSnapshots += snapshot
            latestSnapshots[snapshot.providerId to snapshot.localAccountId] = snapshot
        }

        override suspend fun latestFor(account: ProviderAccount): QuotaSnapshot? =
            latestSnapshots[account.providerId to account.localAccountId]
    }

    private class RecordingAttemptStore : RefreshAttemptStore {
        val savedAttempts = mutableListOf<RefreshAttempt>()

        override suspend fun save(attempt: RefreshAttempt) {
            savedAttempts += attempt
        }
    }

    private class RecordingRefreshAccountStatusStore : RefreshAccountStatusStore {
        val needsReauthAccounts = mutableListOf<Pair<ProviderAccount, Instant>>()
        val activeAccounts = mutableListOf<Pair<ProviderAccount, Instant>>()

        override suspend fun markNeedsReauth(account: ProviderAccount, updatedAt: Instant) {
            needsReauthAccounts += account to updatedAt
        }

        override suspend fun markActive(account: ProviderAccount, updatedAt: Instant) {
            activeAccounts += account to updatedAt
        }
    }

    private class RecordingCurrentQuotaStatePublisher : CurrentQuotaStatePublisher {
        val publishedStates = mutableListOf<CurrentQuotaState>()

        override suspend fun publish(state: CurrentQuotaState) {
            publishedStates += state
        }
    }

    private class SequentialAttemptIdProvider : AttemptIdProvider {
        private var next = 1

        override fun nextId(): RefreshAttemptId =
            RefreshAttemptId("attempt-${next++}")
    }

    private class TestRefreshException : RuntimeException("synthetic refresh failure")

    private class StaticPrimaryQuotaWindowPreferenceReader(
        private val windowId: QuotaWindowId,
    ) : PrimaryQuotaWindowPreferenceReader {
        override suspend fun primaryQuotaWindowId(): QuotaWindowId = windowId
    }

    private fun account(): ProviderAccount =
        ProviderAccount.createNew(
            localAccountId = LocalAccountId("local-1"),
            providerId = ProviderId("codex"),
            providerAccountId = ProviderAccountId("acct-1"),
            displayName = "Codex Main",
            now = Instant.parse("2026-05-23T11:00:00Z"),
        )

    private fun quotaSnapshot(
        snapshotId: String,
        fetchedAt: Instant = now,
        windows: List<QuotaWindow> = listOf(quotaWindow(windowId = QuotaWindowId("five_hour"), usedPercent = 62)),
    ): QuotaSnapshot =
        QuotaSnapshot(
            snapshotId = SnapshotId(snapshotId),
            providerId = ProviderId("codex"),
            localAccountId = LocalAccountId("local-1"),
            providerAccountId = ProviderAccountId("acct-1"),
            fetchedAt = fetchedAt,
            source = QuotaSnapshotSource.ManualRefresh,
            planType = "plus",
            windows = windows,
            credits = null,
            responseDigest = "safe-digest",
        )

    private fun quotaWindow(
        windowId: QuotaWindowId,
        usedPercent: Int?,
    ): QuotaWindow =
        QuotaWindow(
            windowId = windowId,
            titleKey = "quota_window_${windowId.value}",
            usedPercent = usedPercent,
            resetAt = Instant.parse("2026-05-23T17:00:00Z"),
            limitWindowSeconds = 18_000,
            isPrimaryCandidate = true,
            availability = QuotaWindowAvailability.Available,
        )
}
