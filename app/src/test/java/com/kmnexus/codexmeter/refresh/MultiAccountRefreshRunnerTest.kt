package com.kmnexus.codexmeter.refresh

import com.kmnexus.codexmeter.domain.model.AccountStatus
import com.kmnexus.codexmeter.domain.model.LocalAccountId
import com.kmnexus.codexmeter.domain.model.ProviderAccount
import com.kmnexus.codexmeter.domain.model.ProviderAccountId
import com.kmnexus.codexmeter.domain.model.ProviderId
import com.kmnexus.codexmeter.domain.model.RefreshAttemptId
import com.kmnexus.codexmeter.domain.quota.QuotaSnapshot
import com.kmnexus.codexmeter.domain.refresh.QuotaError
import com.kmnexus.codexmeter.domain.refresh.RefreshAttempt
import com.kmnexus.codexmeter.domain.refresh.RefreshTrigger
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class MultiAccountRefreshRunnerTest {
    private val now = Instant.parse("2026-05-23T12:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)

    @Test
    fun `refresh covers needs-reauth accounts passed in for a manual retry`() = runTest {
        // A manual pull-to-refresh hands in Active + NeedsReauth accounts; the runner must refresh
        // every one it is given. Account-state filtering is the caller's job (activeAccounts for
        // background, manuallyRefreshableAccounts for manual), not the runner's.
        val provider = RecordingRefreshProvider()
        val runner = MultiAccountRefreshRunner(refreshCoordinator = coordinator(provider))

        runner.refresh(
            accounts = listOf(
                account("local-active", AccountStatus.Active),
                account("local-reauth", AccountStatus.NeedsReauth),
            ),
            trigger = RefreshTrigger.Manual,
        )

        assertEquals(
            setOf("local-active", "local-reauth"),
            provider.refreshedAccountIds.toSet(),
        )
    }

    private fun coordinator(provider: RefreshProvider): RefreshCoordinator =
        RefreshCoordinator(
            provider = provider,
            snapshotStore = NoopSnapshotStore,
            attemptStore = NoopAttemptStore,
            attemptIdProvider = SequentialAttemptIdProvider(),
            clock = clock,
        )

    private fun account(localAccountId: String, status: AccountStatus): ProviderAccount =
        ProviderAccount.createNew(
            localAccountId = LocalAccountId(localAccountId),
            providerId = ProviderId("codex"),
            providerAccountId = ProviderAccountId("acct-$localAccountId"),
            displayName = localAccountId,
            now = now,
        ).copy(status = status)

    private class RecordingRefreshProvider : RefreshProvider {
        val refreshedAccountIds = mutableListOf<String>()

        override suspend fun refresh(
            account: ProviderAccount,
            trigger: RefreshTrigger,
        ): ProviderRefreshResult {
            refreshedAccountIds += account.localAccountId.value
            return ProviderRefreshResult.Failure(QuotaError.Network(diagnosticsDigest = null))
        }
    }

    private object NoopSnapshotStore : SnapshotStore {
        override suspend fun save(snapshot: QuotaSnapshot) = Unit
        override suspend fun latestFor(account: ProviderAccount): QuotaSnapshot? = null
    }

    private object NoopAttemptStore : RefreshAttemptStore {
        override suspend fun save(attempt: RefreshAttempt) = Unit
    }

    private class SequentialAttemptIdProvider : AttemptIdProvider {
        private var next = 1
        override fun nextId(): RefreshAttemptId = RefreshAttemptId("attempt-${next++}")
    }
}
