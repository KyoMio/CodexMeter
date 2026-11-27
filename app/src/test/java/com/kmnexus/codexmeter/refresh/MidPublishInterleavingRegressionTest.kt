package com.kmnexus.codexmeter.refresh

import com.kmnexus.codexmeter.core.concurrency.VersionedStateGuard
import com.kmnexus.codexmeter.data.local.dao.ProviderAccountDao
import com.kmnexus.codexmeter.data.local.dao.QuotaSnapshotDao
import com.kmnexus.codexmeter.data.local.dao.RefreshAttemptDao
import com.kmnexus.codexmeter.data.local.entity.ProviderAccountEntity
import com.kmnexus.codexmeter.data.preferences.CurrentAccountSelection
import com.kmnexus.codexmeter.data.preferences.CurrentAccountStore
import com.kmnexus.codexmeter.data.repository.AccountMutationRepository
import com.kmnexus.codexmeter.domain.model.LocalAccountId
import com.kmnexus.codexmeter.domain.model.ProviderAccount
import com.kmnexus.codexmeter.domain.model.ProviderAccountId
import com.kmnexus.codexmeter.domain.model.ProviderId
import com.kmnexus.codexmeter.domain.model.QuotaWindowId
import com.kmnexus.codexmeter.domain.model.RefreshAttemptId
import com.kmnexus.codexmeter.domain.model.SnapshotId
import com.kmnexus.codexmeter.domain.quota.CurrentQuotaState
import com.kmnexus.codexmeter.domain.quota.QuotaSnapshot
import com.kmnexus.codexmeter.domain.quota.QuotaSnapshotSource
import com.kmnexus.codexmeter.domain.quota.QuotaWindow
import com.kmnexus.codexmeter.domain.quota.QuotaWindowAvailability
import com.kmnexus.codexmeter.domain.refresh.RefreshAttempt
import com.kmnexus.codexmeter.domain.refresh.RefreshTrigger
import com.kmnexus.codexmeter.domain.settings.DefaultPrimaryQuotaWindowPreferenceReader
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression for GH-PR12 review finding #2, isolated in its own class because the interleaving
 * harness is deliberately elaborate: a refresh write parked inside the guard's critical section
 * versus the real rename -> republish pipeline.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MidPublishInterleavingRegressionTest {

    private val now = Instant.parse("2026-05-23T12:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)

    @Test
    fun `mid-publish suspension during a rename cannot end with the stale state on top`() = runTest {
        // In-memory DAO fakes: every suspending step stays on the test scheduler, so
        // advanceUntilIdle() is deterministic in BOTH worlds — unlike Room, whose queries escape
        // virtual time onto a real SQLite executor. That escape is why the Room-based version of
        // this test could not distinguish the pre-fix from the fixed implementation.
        val accounts = InMemoryProviderAccountDao()
        val selectionStore = InMemorySelectionStore()
        val guard = VersionedStateGuard()
        accounts.upsert(accountEntity("local-1", displayName = "Before"))
        selectionStore.updateCurrentAccountSelection(
            CurrentAccountSelection(ProviderId("codex"), LocalAccountId("local-1")),
        )

        val writeMayResume = CompletableDeferred<Unit>()
        val writeParked = CompletableDeferred<Unit>()
        val publisher = parkingOncePublisher(writeParked, writeMayResume)
        val mutationRepo = interleavingMutationRepository(
            accounts = accounts,
            selectionStore = selectionStore,
            guard = guard,
            publisher = publisher,
        )
        val coordinator = coordinator(
            guard = guard,
            selectionStore = selectionStore,
            publisher = publisher,
            provider = RefreshProvider { _, _ ->
                ProviderRefreshResult.Success(quotaSnapshot("in-flight-snapshot"))
            },
        )

        val refresh = async {
            coordinator.refresh(
                account = domainAccount("local-1", displayName = "Before"),
                trigger = RefreshTrigger.Manual,
            )
        }
        writeParked.await()
        // The refresh write is now parked INSIDE the guard's critical section, after passing the
        // freshness check — exactly the window the review finding exploited.

        val rename = async {
            mutationRepo.renameAccount(
                providerId = ProviderId("codex"),
                localAccountId = LocalAccountId("local-1"),
                displayName = "After",
            )
        }
        // Drain the scheduler while the write is still parked:
        // - without the commit mutex (pre-fix), the rename's republish runs to completion NOW, so
        //   resuming the parked write lands "Before" strictly AFTER "After" — the clobber.
        // - with the mutex, the republish stalls on the lock the parked write holds, and the
        //   scheduler goes idle with the republish queued behind it.
        testScheduler.advanceUntilIdle()
        writeMayResume.complete(Unit)
        refresh.await()
        rename.await()

        val names = publisher.publishedStates.map { it.account?.displayName }
        assertEquals(
            "ordering must be causal: the stale pre-rename write may land first, " +
                "but the rename's republish must be serialized after it — never the reverse; got $names",
            "After",
            names.last(),
        )
        assertTrue(
            "both writes should have landed (Before then After): $names",
            names.size >= 2 && names.first() == "Before",
        )
    }


    /**
     * A publisher whose FIRST write parks inside the guard's critical section (signalling
     * [writeParked], awaiting [writeMayResume]); subsequent writes flow through. This reproduces
     * the exact window from the review finding: validation passed, write suspended mid-publish.
     */
    private fun parkingOncePublisher(
        writeParked: CompletableDeferred<Unit>,
        writeMayResume: CompletableDeferred<Unit>,
    ): RecordingPublisher {
        var parkOnce = true
        return RecordingPublisher(
            beforeRecord = {
                if (parkOnce) {
                    parkOnce = false
                    writeParked.complete(Unit)
                    writeMayResume.await()
                }
            },
        )
    }

    /** Production wiring for the interleaving test: mutation repo -> real republisher -> guard. */
    private fun interleavingMutationRepository(
        accounts: InMemoryProviderAccountDao,
        selectionStore: InMemorySelectionStore,
        guard: VersionedStateGuard,
        publisher: RecordingPublisher,
    ): AccountMutationRepository {
        val realRepublisher = com.kmnexus.codexmeter.app.CurrentAccountQuotaStateRepublisher(
            currentAccountReader = selectionStore,
            providerAccountDao = accounts,
            quotaSnapshotDao = InMemoryQuotaSnapshotDao(),
            refreshAttemptDao = InMemoryRefreshAttemptDao(),
            currentQuotaStatePublisher = publisher,
            clock = clock,
            stateGuard = guard,
        )
        return AccountMutationRepository(
            providerAccountDao = accounts,
            currentAccountStore = selectionStore,
            currentAccountStateRepublisher = realRepublisher,
            clock = clock,
            stateGuard = guard,
        )
    }

    private class InMemoryProviderAccountDao : ProviderAccountDao() {
        private val rows = mutableMapOf<String, ProviderAccountEntity>()

        override suspend fun upsertByLocalAccountId(account: ProviderAccountEntity) {
            rows[account.localAccountId] = account
        }

        override suspend fun getByProviderAccountId(
            providerId: String,
            providerAccountId: String,
        ): ProviderAccountEntity? = rows.values.firstOrNull {
            it.providerId == providerId && it.providerAccountId == providerAccountId
        }

        override suspend fun getById(localAccountId: String): ProviderAccountEntity? = rows[localAccountId]

        override suspend fun listByProvider(providerId: String): List<ProviderAccountEntity> =
            rows.values.filter { it.providerId == providerId }.sortedBy { it.createdAt }

        override suspend fun listAll(): List<ProviderAccountEntity> =
            rows.values.sortedBy { it.createdAt }

        override suspend fun delete(account: ProviderAccountEntity) {
            rows.remove(account.localAccountId)
        }

        override suspend fun updateStatus(
            providerId: String,
            localAccountId: String,
            status: String,
            updatedAt: Long,
        ) {
            rows[localAccountId]?.let { rows[localAccountId] = it.copy(status = status, updatedAt = updatedAt) }
        }

        override suspend fun updateDisplayName(
            providerId: String,
            localAccountId: String,
            displayName: String,
            avatarInitial: String,
            updatedAt: Long,
        ): Int {
            val row = rows[localAccountId] ?: return 0
            rows[localAccountId] = row.copy(
                displayName = displayName,
                avatarInitial = avatarInitial,
                updatedAt = updatedAt,
            )
            return 1
        }
    }

    private class InMemoryQuotaSnapshotDao : QuotaSnapshotDao {
        private val rows = mutableListOf<com.kmnexus.codexmeter.data.local.entity.QuotaSnapshotEntity>()

        override suspend fun insert(snapshot: com.kmnexus.codexmeter.data.local.entity.QuotaSnapshotEntity) {
            rows.add(snapshot)
        }

        override suspend fun getLatestForAccount(
            providerId: String,
            localAccountId: String,
        ) = rows.filter { it.providerId == providerId && it.localAccountId == localAccountId }
            .maxByOrNull { it.fetchedAt }

        override suspend fun deleteForAccount(providerId: String, localAccountId: String): Int = 0

        override suspend fun deleteAll(): Int = 0

        override suspend fun deleteFetchedBefore(cutoffMillis: Long): Int = 0

        override suspend fun listForAccountSince(
            providerId: String,
            localAccountId: String,
            sinceMillis: Long,
        ) = rows.filter {
            it.providerId == providerId && it.localAccountId == localAccountId && it.fetchedAt >= sinceMillis
        }.sortedBy { it.fetchedAt }
    }

    private class InMemoryRefreshAttemptDao : RefreshAttemptDao {
        override suspend fun insert(attempt: com.kmnexus.codexmeter.data.local.entity.RefreshAttemptEntity) = Unit

        override suspend fun getLatestForAccount(
            providerId: String,
            localAccountId: String,
        ) = null

        override suspend fun getLatestForAccountByTrigger(
            providerId: String,
            localAccountId: String,
            trigger: String,
        ) = null

        override suspend fun countConsecutiveFailuresSinceLatestSuccess(
            providerId: String,
            localAccountId: String,
        ): Int = 0

        override suspend fun deleteForAccount(providerId: String, localAccountId: String): Int = 0

        override suspend fun deleteAll(): Int = 0

        override suspend fun deleteStartedBefore(cutoffMillis: Long): Int = 0
    }

    private fun accountEntity(localAccountId: String, displayName: String) = ProviderAccountEntity(
        localAccountId = localAccountId,
        providerId = "codex",
        providerAccountId = "provider-$localAccountId",
        displayName = displayName,
        avatarInitial = displayName.first().uppercase(),
        avatarColorKey = localAccountId,
        status = "active",
        createdAt = now.toEpochMilli(),
        updatedAt = now.toEpochMilli(),
        lastSuccessfulRefreshAt = null,
    )


    private fun coordinator(
        guard: VersionedStateGuard,
        selectionStore: InMemorySelectionStore,
        publisher: RecordingPublisher,
        provider: RefreshProvider,
    ): RefreshCoordinator =
        RefreshCoordinator(
            provider = provider,
            snapshotStore = object : SnapshotStore {
                override suspend fun save(snapshot: QuotaSnapshot) = Unit
                override suspend fun latestFor(account: ProviderAccount): QuotaSnapshot? = null
            },
            attemptStore = object : RefreshAttemptStore {
                override suspend fun save(attempt: RefreshAttempt) = Unit
            },
            attemptIdProvider = AttemptIdProvider { RefreshAttemptId("attempt-1") },
            currentQuotaStatePublisher = publisher,
            primaryQuotaWindowPreferenceReader = DefaultPrimaryQuotaWindowPreferenceReader,
            clock = clock,
            stateGuard = guard,
            selectedAccountReader = SelectedAccountReader {
                selectionStore.currentAccountSelection()?.localAccountId
            },
        )

    private fun domainAccount(localAccountId: String, displayName: String) = ProviderAccount.createNew(
        localAccountId = LocalAccountId(localAccountId),
        providerId = ProviderId("codex"),
        providerAccountId = ProviderAccountId("provider-$localAccountId"),
        displayName = displayName,
        now = now,
    )

    private fun quotaSnapshot(snapshotId: String) = QuotaSnapshot(
        snapshotId = SnapshotId(snapshotId),
        providerId = ProviderId("codex"),
        localAccountId = LocalAccountId("local-1"),
        providerAccountId = ProviderAccountId("acct-1"),
        fetchedAt = now,
        source = QuotaSnapshotSource.ManualRefresh,
        planType = "plus",
        windows = listOf(
            QuotaWindow(
                windowId = QuotaWindowId("five_hour"),
                titleKey = "quota_window_five_hour",
                usedPercent = 42,
                resetAt = Instant.parse("2026-05-23T17:00:00Z"),
                limitWindowSeconds = 18_000,
                isPrimaryCandidate = true,
                availability = QuotaWindowAvailability.Available,
            ),
        ),
        credits = null,
        responseDigest = "safe-digest",
    )

    private class RecordingPublisher(
        val beforeRecord: suspend () -> Unit = {},
    ) : CurrentQuotaStatePublisher {
        val publishedStates = mutableListOf<CurrentQuotaState>()

        override suspend fun publish(state: CurrentQuotaState) {
            beforeRecord()
            publishedStates += state
        }
    }

    private class InMemorySelectionStore : CurrentAccountStore {
        private var selection: CurrentAccountSelection? = null

        override suspend fun currentAccountSelection(): CurrentAccountSelection? = selection

        override suspend fun updateCurrentAccountSelection(selection: CurrentAccountSelection?) {
            this.selection = selection
        }
    }

}
