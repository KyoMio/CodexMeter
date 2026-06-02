package com.kmnexus.codexmeter.refresh

import com.kmnexus.codexmeter.CodexMeterApp
import com.kmnexus.codexmeter.domain.model.LocalAccountId
import com.kmnexus.codexmeter.domain.model.ProviderAccount
import com.kmnexus.codexmeter.domain.model.ProviderAccountId
import com.kmnexus.codexmeter.domain.model.ProviderId
import com.kmnexus.codexmeter.domain.model.RefreshAttemptId
import com.kmnexus.codexmeter.domain.refresh.RefreshTrigger
import java.time.Duration
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RefreshWorkSchedulerTest {
    @Test
    fun `periodic refresh uses architecture unique work name`() {
        val enqueuer = RecordingRefreshWorkEnqueuer()
        val scheduler = RefreshWorkScheduler(enqueuer)

        val plan = scheduler.schedulePeriodicRefresh()

        assertEquals("quota_periodic_refresh", plan.uniqueWorkName)
        assertEquals(listOf(plan), enqueuer.enqueuedPlans)
    }

    @Test
    fun `periodic refresh uses fifteen minute WorkManager interval class`() {
        val plan = RefreshWorkScheduler.periodicRefreshPlan()

        assertEquals(Duration.ofMinutes(15), plan.repeatInterval)
        assertTrue(plan.repeatInterval >= Duration.ofMinutes(15))
    }

    @Test
    fun `periodic refresh waits for connected network`() {
        val plan = RefreshWorkScheduler.periodicRefreshPlan()

        assertTrue(plan.requiresConnectedNetwork)
    }

    @Test
    fun `application bootstrap registers periodic refresh work`() {
        val enqueuer = RecordingRefreshWorkEnqueuer()
        val scheduler = RefreshWorkScheduler(enqueuer)

        CodexMeterApp.registerRefreshWork(scheduler)

        assertEquals(listOf(RefreshWorkScheduler.periodicRefreshPlan()), enqueuer.enqueuedPlans)
    }

    @Test
    fun `apply interval reschedules with chosen minutes and manual cancels`() {
        val enqueuer = RecordingRefreshWorkEnqueuer()
        val scheduler = RefreshWorkScheduler(enqueuer)

        scheduler.applyIntervalMinutes(30)
        assertEquals(Duration.ofMinutes(30), enqueuer.enqueuedPlans.single().repeatInterval)

        scheduler.applyIntervalMinutes(0)
        assertEquals(listOf(RefreshWorkScheduler.UNIQUE_PERIODIC_WORK_NAME), enqueuer.cancelledNames)
    }

    @Test
    fun `application exposes refresh dependencies for scheduled worker`() {
        assertTrue(QuotaRefreshDependenciesProvider::class.java.isAssignableFrom(CodexMeterApp::class.java))
    }

    @Test
    fun `missing worker runner fails instead of reporting fake success`() = runTest {
        val outcome = QuotaRefreshWorkerOutcome.fromDependencies(provider = null)

        assertEquals(QuotaRefreshWorkerOutcome.Failure, outcome)
    }

    @Test
    fun `worker dependency path refreshes current account through coordinator`() = runTest {
        val account = account()
        val refreshProvider = RecordingRefreshProvider()
        val dependencies = RecordingRefreshDependenciesProvider(
            account = account,
            refreshCoordinator = RefreshCoordinator(
                provider = refreshProvider,
                snapshotStore = EmptySnapshotStore,
                attemptStore = RecordingAttemptStore(),
                attemptIdProvider = AttemptIdProvider { RefreshAttemptId("attempt-1") },
                clock = Clock.fixed(Instant.parse("2026-05-23T12:00:00Z"), ZoneOffset.UTC),
            ),
        )

        val outcome = QuotaRefreshWorkerOutcome.fromDependencies(dependencies)

        assertEquals(QuotaRefreshWorkerOutcome.Failure, outcome)
        assertEquals(listOf(account to RefreshTrigger.Periodic), refreshProvider.requests)
    }

    @Test
    fun `retryable refresh failure asks WorkManager to retry`() = runTest {
        val dependencies = RecordingRefreshDependenciesProvider(
            account = account(),
            refreshCoordinator = RefreshCoordinator(
                provider = RecordingRefreshProvider(
                    com.kmnexus.codexmeter.domain.refresh.QuotaError.Network(
                        diagnosticsDigest = "safe",
                    ),
                ),
                snapshotStore = EmptySnapshotStore,
                attemptStore = RecordingAttemptStore(),
                attemptIdProvider = AttemptIdProvider { RefreshAttemptId("attempt-1") },
                clock = Clock.fixed(Instant.parse("2026-05-23T12:00:00Z"), ZoneOffset.UTC),
            ),
        )

        val outcome = QuotaRefreshWorkerOutcome.fromDependencies(dependencies)

        assertEquals(QuotaRefreshWorkerOutcome.Retry, outcome)
    }

    private class RecordingRefreshWorkEnqueuer : RefreshWorkEnqueuer {
        val enqueuedPlans = mutableListOf<RefreshWorkPlan>()
        val cancelledNames = mutableListOf<String>()

        override fun enqueue(plan: RefreshWorkPlan) {
            enqueuedPlans += plan
        }

        override fun cancel(uniqueWorkName: String) {
            cancelledNames += uniqueWorkName
        }
    }

    private class RecordingRefreshDependenciesProvider(
        private val account: ProviderAccount?,
        override val refreshCoordinator: RefreshCoordinator,
    ) : QuotaRefreshDependenciesProvider {
        override suspend fun activeQuotaRefreshAccounts(): List<ProviderAccount> =
            account?.let(::listOf).orEmpty()
    }

    private class RecordingRefreshProvider(
        private val error: com.kmnexus.codexmeter.domain.refresh.QuotaError =
            com.kmnexus.codexmeter.domain.refresh.QuotaError.AuthRequired(
                httpStatus = 401,
                diagnosticsDigest = "safe",
            ),
    ) : RefreshProvider {
        val requests = mutableListOf<Pair<ProviderAccount, RefreshTrigger>>()

        override suspend fun refresh(account: ProviderAccount, trigger: RefreshTrigger): ProviderRefreshResult {
            requests += account to trigger
            return ProviderRefreshResult.Failure(error)
        }
    }

    private object EmptySnapshotStore : SnapshotStore {
        override suspend fun save(snapshot: com.kmnexus.codexmeter.domain.quota.QuotaSnapshot) = Unit
        override suspend fun latestFor(account: ProviderAccount): com.kmnexus.codexmeter.domain.quota.QuotaSnapshot? = null
    }

    private class RecordingAttemptStore : RefreshAttemptStore {
        override suspend fun save(attempt: com.kmnexus.codexmeter.domain.refresh.RefreshAttempt) = Unit
    }

    private fun account(): ProviderAccount =
        ProviderAccount.createNew(
            localAccountId = LocalAccountId("local-1"),
            providerId = ProviderId("codex"),
            providerAccountId = ProviderAccountId("acct-1"),
            displayName = "Codex",
            now = Instant.parse("2026-05-23T12:00:00Z"),
        )
}
