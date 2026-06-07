package com.kmnexus.codexmeter.refresh

import com.kmnexus.codexmeter.domain.model.LocalAccountId
import com.kmnexus.codexmeter.domain.model.ProviderAccount
import com.kmnexus.codexmeter.domain.model.ProviderAccountId
import com.kmnexus.codexmeter.domain.model.ProviderId
import com.kmnexus.codexmeter.domain.model.QuotaWindowId
import com.kmnexus.codexmeter.domain.model.RefreshAttemptId
import com.kmnexus.codexmeter.domain.model.SnapshotId
import com.kmnexus.codexmeter.domain.quota.QuotaSnapshot
import com.kmnexus.codexmeter.domain.quota.QuotaSnapshotSource
import com.kmnexus.codexmeter.domain.quota.QuotaWindow
import com.kmnexus.codexmeter.domain.quota.QuotaWindowAvailability
import com.kmnexus.codexmeter.domain.refresh.QuotaError
import com.kmnexus.codexmeter.domain.refresh.RefreshAttempt
import com.kmnexus.codexmeter.domain.refresh.RefreshTrigger
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QuotaRefreshWorkerTest {
    @Test
    fun `worker refreshes every active account`() = runTest {
        val accounts = listOf(account("local-1"), account("local-2"))
        val refreshProvider = RecordingRefreshProvider()
        val dependencies = RecordingRefreshDependenciesProvider(
            accounts = accounts,
            refreshCoordinator = coordinator(refreshProvider),
        )

        val outcome = QuotaRefreshWorkerOutcome.fromDependencies(dependencies)

        assertEquals(QuotaRefreshWorkerOutcome.Success, outcome)
        assertEquals(listOf("local-1", "local-2"), refreshProvider.requests.map { it.account.localAccountId.value })
        assertTrue(refreshProvider.requests.all { it.trigger == RefreshTrigger.Periodic })
    }

    // Account-status filtering is the caller's job (activeQuotaRefreshAccounts -> activeAccounts for
    // background, manuallyRefreshableAccounts for manual), covered by CurrentQuotaRefreshAccountStoreTest.
    // The worker/runner refreshes exactly the accounts it is handed; see MultiAccountRefreshRunnerTest.

    @Test
    fun `retryable failure in any account asks WorkManager to retry`() = runTest {
        val refreshProvider = RecordingRefreshProvider(
            failures = mapOf(
                LocalAccountId("local-1") to QuotaError.Network(diagnosticsDigest = "safe"),
            ),
        )
        val dependencies = RecordingRefreshDependenciesProvider(
            accounts = listOf(account("local-1"), account("local-2")),
            refreshCoordinator = coordinator(refreshProvider),
        )

        val outcome = QuotaRefreshWorkerOutcome.fromDependencies(dependencies)

        assertEquals(QuotaRefreshWorkerOutcome.Retry, outcome)
    }

    @Test
    fun `terminal auth failure fails only when no retryable work remains`() = runTest {
        val authRequired = QuotaError.AuthRequired(httpStatus = 401, diagnosticsDigest = "safe")
        val retryable = QuotaError.Network(diagnosticsDigest = "safe")
        val authOnlyProvider = RecordingRefreshProvider(
            failures = mapOf(LocalAccountId("local-1") to authRequired),
        )
        val mixedProvider = RecordingRefreshProvider(
            failures = mapOf(
                LocalAccountId("local-1") to authRequired,
                LocalAccountId("local-2") to retryable,
            ),
        )

        val authOnlyOutcome = QuotaRefreshWorkerOutcome.fromDependencies(
            RecordingRefreshDependenciesProvider(
                accounts = listOf(account("local-1"), account("local-2")),
                refreshCoordinator = coordinator(authOnlyProvider),
            ),
        )
        val mixedOutcome = QuotaRefreshWorkerOutcome.fromDependencies(
            RecordingRefreshDependenciesProvider(
                accounts = listOf(account("local-1"), account("local-2")),
                refreshCoordinator = coordinator(mixedProvider),
            ),
        )

        assertEquals(QuotaRefreshWorkerOutcome.Failure, authOnlyOutcome)
        assertEquals(QuotaRefreshWorkerOutcome.Retry, mixedOutcome)
    }

    @Test
    fun `worker refreshes accounts with bounded parallelism of four`() = runTest {
        val releaseRefresh = CompletableDeferred<Unit>()
        val fourStarted = CompletableDeferred<Unit>()
        val refreshProvider = RecordingRefreshProvider(
            onStart = { startedCount ->
                if (startedCount == 4) {
                    fourStarted.complete(Unit)
                }
                releaseRefresh.await()
            },
        )
        val dependencies = RecordingRefreshDependenciesProvider(
            accounts = listOf(
                account("local-1"),
                account("local-2"),
                account("local-3"),
                account("local-4"),
                account("local-5"),
            ),
            refreshCoordinator = coordinator(refreshProvider),
        )

        val outcome = async { QuotaRefreshWorkerOutcome.fromDependencies(dependencies) }

        withTimeout(1_000) {
            fourStarted.await()
        }
        assertEquals(4, refreshProvider.startedAccountIds.size)
        assertEquals(4, refreshProvider.maxConcurrentRefreshes)

        releaseRefresh.complete(Unit)

        assertEquals(QuotaRefreshWorkerOutcome.Success, outcome.await())
        assertEquals(
            listOf("local-1", "local-2", "local-3", "local-4", "local-5"),
            refreshProvider.startedAccountIds,
        )
        assertTrue(refreshProvider.maxConcurrentRefreshes <= 4)
    }

    private class RecordingRefreshDependenciesProvider(
        private val accounts: List<ProviderAccount>,
        override val refreshCoordinator: RefreshCoordinator,
    ) : QuotaRefreshDependenciesProvider {
        override suspend fun activeQuotaRefreshAccounts(): List<ProviderAccount> = accounts
    }

    private inner class RecordingRefreshProvider(
        private val failures: Map<LocalAccountId, QuotaError> = emptyMap(),
        private val onStart: suspend (startedCount: Int) -> Unit = {},
    ) : RefreshProvider {
        val requests = mutableListOf<RefreshRequest>()
        val startedAccountIds = mutableListOf<String>()
        var maxConcurrentRefreshes = 0
            private set
        private var concurrentRefreshes = 0

        override suspend fun refresh(account: ProviderAccount, trigger: RefreshTrigger): ProviderRefreshResult {
            requests += RefreshRequest(account = account, trigger = trigger)
            concurrentRefreshes += 1
            maxConcurrentRefreshes = maxOf(maxConcurrentRefreshes, concurrentRefreshes)
            startedAccountIds += account.localAccountId.value
            return try {
                onStart(startedAccountIds.size)
                failures[account.localAccountId]?.let { ProviderRefreshResult.Failure(it) }
                    ?: ProviderRefreshResult.Success(snapshot(account))
            } finally {
                concurrentRefreshes -= 1
            }
        }
    }

    private data class RefreshRequest(
        val account: ProviderAccount,
        val trigger: RefreshTrigger,
    )

    private object RecordingSnapshotStore : SnapshotStore {
        override suspend fun save(snapshot: QuotaSnapshot) = Unit
        override suspend fun latestFor(account: ProviderAccount): QuotaSnapshot? = null
    }

    private class RecordingAttemptStore : RefreshAttemptStore {
        override suspend fun save(attempt: RefreshAttempt) = Unit
    }

    private fun coordinator(refreshProvider: RefreshProvider): RefreshCoordinator =
        RefreshCoordinator(
            provider = refreshProvider,
            snapshotStore = RecordingSnapshotStore,
            attemptStore = RecordingAttemptStore(),
            attemptIdProvider = AttemptIdProvider { RefreshAttemptId("attempt-${System.nanoTime()}") },
            clock = Clock.fixed(Instant.parse("2026-05-23T12:00:00Z"), ZoneOffset.UTC),
        )

    private fun account(localAccountId: String): ProviderAccount =
        ProviderAccount.createNew(
            localAccountId = LocalAccountId(localAccountId),
            providerId = ProviderId("codex"),
            providerAccountId = ProviderAccountId("acct-$localAccountId"),
            displayName = localAccountId,
            now = Instant.parse("2026-05-23T12:00:00Z"),
        )

    private fun snapshot(account: ProviderAccount): QuotaSnapshot =
        QuotaSnapshot(
            snapshotId = SnapshotId("snapshot-${account.localAccountId.value}"),
            providerId = account.providerId,
            localAccountId = account.localAccountId,
            providerAccountId = account.providerAccountId,
            fetchedAt = Instant.parse("2026-05-23T12:00:00Z"),
            source = QuotaSnapshotSource.BackgroundRefresh,
            planType = "plus",
            windows = listOf(
                QuotaWindow(
                    windowId = QuotaWindowId("five_hour"),
                    titleKey = "quota_window_five_hour",
                    usedPercent = 62,
                    resetAt = Instant.parse("2026-05-23T17:00:00Z"),
                    limitWindowSeconds = 18_000,
                    isPrimaryCandidate = true,
                    availability = QuotaWindowAvailability.Available,
                ),
            ),
            credits = null,
            responseDigest = "safe-digest",
        )
}
