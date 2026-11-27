package com.kmnexus.codexmeter.refresh

import androidx.room.Room
import com.kmnexus.codexmeter.core.concurrency.VersionedStateGuard
import com.kmnexus.codexmeter.data.local.db.CodexMeterDatabase
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
import com.kmnexus.codexmeter.domain.refresh.QuotaError
import com.kmnexus.codexmeter.domain.refresh.RefreshAttempt
import com.kmnexus.codexmeter.domain.refresh.RefreshTrigger
import com.kmnexus.codexmeter.notification.AccountErrorEventReader
import com.kmnexus.codexmeter.notification.AccountErrorNotificationEvent
import com.kmnexus.codexmeter.notification.AccountErrorNotificationReason
import com.kmnexus.codexmeter.notification.AlertThresholds
import com.kmnexus.codexmeter.notification.CurrentQuotaNotificationPublisher
import com.kmnexus.codexmeter.notification.NotificationAlertStateStore
import com.kmnexus.codexmeter.notification.NotificationChannels
import com.kmnexus.codexmeter.notification.NotificationRequest
import com.kmnexus.codexmeter.notification.NotificationRequestOptions
import com.kmnexus.codexmeter.notification.NotificationSink
import com.kmnexus.codexmeter.notification.StaticAlertThresholdsReader
import com.kmnexus.codexmeter.notification.StaticNotificationRequestOptionsReader
import com.kmnexus.codexmeter.notification.AlertDedupeKey
import com.kmnexus.codexmeter.notification.QuotaAlertEvent
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


    /**
     * Regression for GH-PR12 review finding #1: a background refresh of the NON-selected account B
     * must still fire B's per-account effects (quota alerts, widget updates) — only the
     * current-account presentation write-back is epoch-guarded and dropped. Before the fix the
     * guard swallowed the whole publish, so B's alerts never fired while A stayed selected.
     */
    @Test
    fun `background refresh of a non-selected account still fires per-account effects`() = runTest {
        val db = inMemoryDatabase()
        try {
            val guard = VersionedStateGuard()
            val selectionStore = InMemorySelectionStore()
            seedAccount(db, "account-a", displayName = "Account A")
            seedAccount(db, "account-b", displayName = "Account B")
            // A stays selected for the whole test — B is refreshed in the background.
            selectionStore.updateCurrentAccountSelection(
                CurrentAccountSelection(ProviderId("codex"), LocalAccountId("account-a")),
            )

            val publisher = RecordingPublisher()
            val effects = RecordingPerAccountEffects()
            val coordinator = coordinator(
                guard = guard,
                selectionStore = selectionStore,
                publisher = publisher,
                provider = RefreshProvider { _, _ ->
                    ProviderRefreshResult.Success(quotaSnapshot("background-b-snapshot"))
                },
                perAccountEffectsPublisher = effects,
            )

            coordinator.refresh(
                account = domainAccount("account-b", displayName = "Account B"),
                trigger = RefreshTrigger.Periodic,
            )

            assertEquals(
                "per-account effects must fire for the refreshed account even when it is not selected",
                1,
                effects.effectStates.size,
            )
            assertEquals(
                "the current-account presentation channel must stay silent for the background account",
                0,
                publisher.publishedStates.size,
            )
            assertEquals(1, guard.discardedWrites)
        } finally {
            db.close()
        }
    }

    /**
     * Regression for GH-PR12 review finding #2: a refresh write that passed the freshness check and
     * then suspends mid-publish must not end up ordered AFTER a local rename's republish. Before
     * the fix, validation and the write were not serialized, so the parking write resumed after
     * "After" had already been published and clobbered it with "Before". With the commit mutex the
     * republish queues behind the in-progress write, restoring causality: Before then After.
     */
    // region harness    // region harness

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
        perAccountEffectsPublisher: PerAccountQuotaEffectPublisher = PerAccountQuotaEffectPublisher { },
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
            perAccountEffectsPublisher = perAccountEffectsPublisher,
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

    private class RecordingPublisher(
        val beforeRecord: suspend () -> Unit = {},
    ) : CurrentQuotaStatePublisher {
        val publishedStates = mutableListOf<CurrentQuotaState>()

        override suspend fun publish(state: CurrentQuotaState) {
            beforeRecord()
            publishedStates += state
        }
    }

    private class RecordingPerAccountEffects : PerAccountQuotaEffectPublisher {
        val effectStates = mutableListOf<CurrentQuotaState>()

        override suspend fun publishAccountEffects(state: CurrentQuotaState) {
            effectStates += state
        }
    }

    private class InMemorySelectionStore : CurrentAccountStore {
        private var selection: CurrentAccountSelection? = null

        override suspend fun currentAccountSelection(): CurrentAccountSelection? = selection

        override suspend fun updateCurrentAccountSelection(selection: CurrentAccountSelection?) {
            this.selection = selection
        }
    }



    @Test
    fun `pinned non-default status notification account still updates when refreshed`() = runTest {
        val guard = VersionedStateGuard()
        val accountA = domainAccount("local-a", "Account A")
        val accountB = domainAccount("local-b", "Account B")
        val homeSelected = LocalAccountId("local-a")
        val pinnedNotificationAccount = LocalAccountId("local-b")
        val posted = mutableListOf<NotificationRequest>()
        val notificationPublisher = CurrentQuotaNotificationPublisher(
            notificationSink = NotificationSink { posted += it },
            alertStateStore = object : NotificationAlertStateStore {
                override suspend fun hasNotified(key: AlertDedupeKey) = false
                override suspend fun markNotified(event: QuotaAlertEvent, notifiedAt: Instant) = Unit
            },
            optionsReader = StaticNotificationRequestOptionsReader(
                NotificationRequestOptions(
                    notificationPermissionAvailable = true,
                    statusNotificationEnabled = true,
                    quotaAlertsEnabled = false,
                    accountErrorsEnabled = true,
                ),
            ),
            alertThresholdsReader = StaticAlertThresholdsReader(AlertThresholds()),
            accountErrorEventReader = AccountErrorEventReader { null },
            clock = clock,
        )
        val notificationOnly = CompositeCurrentQuotaStatePublisher(listOf(notificationPublisher))
        val refreshCoordinator = RefreshCoordinator(
            provider = RefreshProvider { acc, _ ->
                ProviderRefreshResult.Success(quotaSnapshot("snap-${acc.localAccountId.value}"))
            },
            snapshotStore = object : SnapshotStore {
                override suspend fun save(snapshot: QuotaSnapshot) = Unit
                override suspend fun latestFor(account: ProviderAccount): QuotaSnapshot? = null
            },
            attemptStore = object : RefreshAttemptStore {
                override suspend fun save(attempt: RefreshAttempt) = Unit
            },
            currentQuotaStatePublisher = notificationOnly,
            perAccountEffectsPublisher = notificationOnly,
            attemptIdProvider = AttemptIdProvider { RefreshAttemptId("attempt-b") },
            clock = clock,
            stateGuard = guard,
            // Production AppContainer resolves pinned persistentNotificationAccount first, falling back to Home.
            selectedAccountReader = SelectedAccountReader {
                assertEquals(LocalAccountId("local-a"), homeSelected)
                pinnedNotificationAccount
            },
        )

        refreshCoordinator.refresh(accountB, RefreshTrigger.Periodic)

        val statusRequests = posted.filter { it.channelId == NotificationChannels.STATUS_CHANNEL_ID }
        assertEquals(1, statusRequests.size)
        assertEquals(listOf("Codex", "Account B"), statusRequests.single().title.formatArgs)
        assertEquals(0, guard.discardedWrites)
    }

    @Test
    fun `non-selected account failure still emits account error notification via per-account channel`() = runTest {
        val guard = VersionedStateGuard()
        val accountA = LocalAccountId("local-a")
        val accountB = domainAccount("local-b", "Account B")
        val posted = mutableListOf<NotificationRequest>()
        val notificationPublisher = CurrentQuotaNotificationPublisher(
            notificationSink = NotificationSink { posted += it },
            alertStateStore = object : NotificationAlertStateStore {
                override suspend fun hasNotified(key: AlertDedupeKey) = false
                override suspend fun markNotified(event: QuotaAlertEvent, notifiedAt: Instant) = Unit
            },
            optionsReader = StaticNotificationRequestOptionsReader(
                NotificationRequestOptions(
                    notificationPermissionAvailable = true,
                    statusNotificationEnabled = true,
                    quotaAlertsEnabled = false,
                    accountErrorsEnabled = true,
                ),
            ),
            alertThresholdsReader = StaticAlertThresholdsReader(AlertThresholds()),
            accountErrorEventReader = AccountErrorEventReader { state ->
                if (state.account?.localAccountId == accountB.localAccountId) {
                    AccountErrorNotificationEvent(AccountErrorNotificationReason.AuthRequired)
                } else {
                    null
                }
            },
            clock = clock,
        )
        val notificationOnly = CompositeCurrentQuotaStatePublisher(listOf(notificationPublisher))
        val refreshCoordinator = RefreshCoordinator(
            provider = RefreshProvider { _, _ ->
                ProviderRefreshResult.Failure(QuotaError.AuthRequired(httpStatus = 401, diagnosticsDigest = "401"))
            },
            snapshotStore = object : SnapshotStore {
                override suspend fun save(snapshot: QuotaSnapshot) = Unit
                override suspend fun latestFor(account: ProviderAccount): QuotaSnapshot? = null
            },
            attemptStore = object : RefreshAttemptStore {
                override suspend fun save(attempt: RefreshAttempt) = Unit
            },
            currentQuotaStatePublisher = notificationOnly,
            perAccountEffectsPublisher = notificationOnly,
            attemptIdProvider = AttemptIdProvider { RefreshAttemptId("attempt-err") },
            clock = clock,
            stateGuard = guard,
            selectedAccountReader = SelectedAccountReader { accountA },
        )

        refreshCoordinator.refresh(accountB, RefreshTrigger.Periodic)

        val errorRequests = posted.filter { it.channelId == NotificationChannels.ACCOUNT_ERRORS_CHANNEL_ID }
        assertEquals(1, errorRequests.size)
        assertTrue(posted.none { it.channelId == NotificationChannels.STATUS_CHANNEL_ID })
    }

    @Test
    fun `single refresh of selected account updates widget once when channels are split`() = runTest {
        val guard = VersionedStateGuard()
        val accountA = domainAccount("local-a", "Account A")
        var widgetUpdates = 0
        var notificationStatusUpdates = 0
        var notificationEffectUpdates = 0

        val widgetMember = object : CurrentQuotaStatePublisher, PerAccountQuotaEffectPublisher {
            override suspend fun publish(state: CurrentQuotaState) {
                widgetUpdates++
            }

            override suspend fun publishAccountEffects(state: CurrentQuotaState) {
                widgetUpdates++
            }
        }
        val notificationMember = object : CurrentQuotaStatePublisher, PerAccountQuotaEffectPublisher {
            override suspend fun publish(state: CurrentQuotaState) {
                notificationStatusUpdates++
            }

            override suspend fun publishAccountEffects(state: CurrentQuotaState) {
                notificationEffectUpdates++
            }
        }

        val fullComposite = CompositeCurrentQuotaStatePublisher(listOf(widgetMember, notificationMember))
        val notificationOnly = CompositeCurrentQuotaStatePublisher(
            fullComposite.members.filterNot { it === widgetMember },
        )
        val refreshCoordinator = RefreshCoordinator(
            provider = RefreshProvider { _, _ -> ProviderRefreshResult.Success(quotaSnapshot("snap-a")) },
            snapshotStore = object : SnapshotStore {
                override suspend fun save(snapshot: QuotaSnapshot) = Unit
                override suspend fun latestFor(account: ProviderAccount): QuotaSnapshot? = null
            },
            attemptStore = object : RefreshAttemptStore {
                override suspend fun save(attempt: RefreshAttempt) = Unit
            },
            currentQuotaStatePublisher = notificationOnly,
            perAccountEffectsPublisher = fullComposite,
            attemptIdProvider = AttemptIdProvider { RefreshAttemptId("attempt-a") },
            clock = clock,
            stateGuard = guard,
            selectedAccountReader = SelectedAccountReader { accountA.localAccountId },
        )

        refreshCoordinator.refresh(accountA, RefreshTrigger.Manual)

        assertEquals(1, widgetUpdates)
        assertEquals(1, notificationStatusUpdates)
        assertEquals(1, notificationEffectUpdates)
    }

    // endregion
}
