package com.kmnexus.codexmeter.refresh

import androidx.room.Room
import com.kmnexus.codexmeter.core.concurrency.VersionedStateGuard
import com.kmnexus.codexmeter.data.local.db.CodexMeterDatabase
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
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Narrow regression tests for collab-rule A8 wired through VersionedStateGuard:
 * a late asynchronous write-back must never clobber newer local state.
 *
 * Both scenarios run the real RefreshCoordinator against the real
 * AccountMutationRepository + in-memory Room database, sharing one guard —
 * the same wiring AppContainer uses in production.
 */
@RunWith(RobolectricTestRunner::class)
// Robolectric SDK 36 currently requires Java 21, while this project is pinned to Java 17.
@Config(sdk = [35])
@OptIn(ExperimentalCoroutinesApi::class)
class StaleWriteBackRegressionTest {

    private val now = Instant.parse("2026-05-23T12:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)

    /**
     * Scenario 1: a refresh for the current account is in flight over the network when the user
     * renames that same account locally. The provider response lands afterwards, carrying the
     * pre-rename account object. The epoch captured before the network call is stale the moment the
     * rename starts, so the write-back is discarded instead of resurrecting the old display name.
     */
    @Test
    fun `late refresh result does not clobber a newer local rename`() = runTest {
        val db = inMemoryDatabase()
        try {
            val guard = VersionedStateGuard()
            val selectionStore = InMemorySelectionStore()
            seedAccount(db, "local-1", displayName = "Codex Main")
            selectionStore.updateCurrentAccountSelection(
                CurrentAccountSelection(ProviderId("codex"), LocalAccountId("local-1")),
            )

            val providerStarted = CompletableDeferred<Unit>()
            val providerMayFinish = CompletableDeferred<Unit>()
            val publisher = RecordingPublisher()
            val coordinator = coordinator(
                guard = guard,
                selectionStore = selectionStore,
                publisher = publisher,
                provider = RefreshProvider { _, _ ->
                    providerStarted.complete(Unit)
                    providerMayFinish.await()
                    ProviderRefreshResult.Success(quotaSnapshot("late-snapshot"))
                },
            )
            val mutationRepository = mutationRepository(db, selectionStore, guard)

            val refresh = async {
                coordinator.refresh(
                    account = domainAccount("local-1", displayName = "Codex Main"),
                    trigger = RefreshTrigger.Manual,
                )
            }
            providerStarted.await()

            // The local edit lands while the network call is still in flight.
            mutationRepository.renameAccount(
                providerId = ProviderId("codex"),
                localAccountId = LocalAccountId("local-1"),
                displayName = "Work Renamed",
            )

            providerMayFinish.complete(Unit)
            refresh.await()

            assertEquals(
                "the write-back captured before the rename must be discarded, not published",
                1,
                guard.discardedWrites,
            )
            assertTrue(
                "the stale pre-rename state must never reach widgets/notifications",
                publisher.publishedStates.isEmpty(),
            )
        } finally {
            db.close()
        }
    }

    /**
     * Scenario 2: a refresh for account A is in flight when the user switches to account B. The
     * selection write moves the durable current account; when A's late response arrives, the
     * commit-time re-read of the selection no longer matches the epoch's account, so A's state is
     * discarded instead of hijacking the surfaces that now belong to B.
     */
    @Test
    fun `late refresh result for the previous account does not hijack surfaces after a switch`() = runTest {
        val db = inMemoryDatabase()
        try {
            val guard = VersionedStateGuard()
            val selectionStore = InMemorySelectionStore()
            seedAccount(db, "account-a", displayName = "Account A")
            seedAccount(db, "account-b", displayName = "Account B")
            selectionStore.updateCurrentAccountSelection(
                CurrentAccountSelection(ProviderId("codex"), LocalAccountId("account-a")),
            )

            val providerStarted = CompletableDeferred<Unit>()
            val providerMayFinish = CompletableDeferred<Unit>()
            val publisher = RecordingPublisher()
            val coordinator = coordinator(
                guard = guard,
                selectionStore = selectionStore,
                publisher = publisher,
                provider = RefreshProvider { _, _ ->
                    providerStarted.complete(Unit)
                    providerMayFinish.await()
                    ProviderRefreshResult.Success(quotaSnapshot("stale-a-snapshot"))
                },
            )
            val mutationRepository = mutationRepository(db, selectionStore, guard)

            val refresh = async {
                coordinator.refresh(
                    account = domainAccount("account-a", displayName = "Account A"),
                    trigger = RefreshTrigger.Periodic,
                )
            }
            providerStarted.await()

            // The user switches to account B while A's network call is in flight.
            val switched = mutationRepository.persistDefaultAccountSelection(
                providerId = ProviderId("codex"),
                localAccountId = LocalAccountId("account-b"),
            )
            assertTrue(switched)

            providerMayFinish.complete(Unit)
            refresh.await()

            assertEquals(
                "the previous account's late write-back must be discarded after the switch",
                1,
                guard.discardedWrites,
            )
            assertTrue(
                "account A's state must never be published while account B is current",
                publisher.publishedStates.isEmpty(),
            )
        } finally {
            db.close()
        }
    }

    /**
     * Control: with no interleaving edit, the same pipeline publishes normally — proving the
     * scenarios above fail because of the guard and not because publishing is broken in general.
     */
    @Test
    fun `refresh without an interleaving edit still publishes`() = runTest {
        val db = inMemoryDatabase()
        try {
            val guard = VersionedStateGuard()
            val selectionStore = InMemorySelectionStore()
            seedAccount(db, "local-1", displayName = "Codex Main")
            selectionStore.updateCurrentAccountSelection(
                CurrentAccountSelection(ProviderId("codex"), LocalAccountId("local-1")),
            )

            val publisher = RecordingPublisher()
            val coordinator = coordinator(
                guard = guard,
                selectionStore = selectionStore,
                publisher = publisher,
                provider = RefreshProvider { _, _ ->
                    ProviderRefreshResult.Success(quotaSnapshot("fresh-snapshot"))
                },
            )

            coordinator.refresh(
                account = domainAccount("local-1", displayName = "Codex Main"),
                trigger = RefreshTrigger.Manual,
            )

            assertEquals(1, publisher.publishedStates.size)
            assertEquals(1, guard.committedWrites)
            assertEquals(0, guard.discardedWrites)
        } finally {
            db.close()
        }
    }

    // region harness

    private fun inMemoryDatabase(): CodexMeterDatabase =
        Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            CodexMeterDatabase::class.java,
        ).build()

    private suspend fun seedAccount(db: CodexMeterDatabase, localAccountId: String, displayName: String) {
        db.providerAccountDao().upsert(
            ProviderAccountEntity(
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
            ),
        )
    }

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

    private fun mutationRepository(
        db: CodexMeterDatabase,
        selectionStore: InMemorySelectionStore,
        guard: VersionedStateGuard,
    ): AccountMutationRepository =
        AccountMutationRepository(
            providerAccountDao = db.providerAccountDao(),
            currentAccountStore = selectionStore,
            clock = clock,
            stateGuard = guard,
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

    private class RecordingPublisher : CurrentQuotaStatePublisher {
        val publishedStates = mutableListOf<CurrentQuotaState>()

        override suspend fun publish(state: CurrentQuotaState) {
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

    // endregion
}
