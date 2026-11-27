package com.kmnexus.codexmeter.debug

import androidx.room.Room
import com.kmnexus.codexmeter.app.CurrentQuotaStateRepository
import com.kmnexus.codexmeter.app.DebugFixtureBridge
import com.kmnexus.codexmeter.core.concurrency.VersionedStateGuard
import com.kmnexus.codexmeter.data.local.db.CodexMeterDatabase
import com.kmnexus.codexmeter.data.preferences.CurrentAccountSelection
import com.kmnexus.codexmeter.data.preferences.CurrentAccountStore
import com.kmnexus.codexmeter.data.repository.toEntity
import com.kmnexus.codexmeter.data.secure.FileSecureSessionStore
import com.kmnexus.codexmeter.domain.account.DeletedAccountStateCleaner
import com.kmnexus.codexmeter.domain.account.DeletedAccountStateCleanup
import com.kmnexus.codexmeter.domain.model.AccountStatus
import com.kmnexus.codexmeter.domain.model.LocalAccountId
import com.kmnexus.codexmeter.domain.model.ProviderAccount
import com.kmnexus.codexmeter.domain.model.ProviderAccountId
import com.kmnexus.codexmeter.domain.model.ProviderId
import com.kmnexus.codexmeter.domain.settings.NotificationAccountSelection
import com.kmnexus.codexmeter.domain.settings.NotificationPreferenceStore
import com.kmnexus.codexmeter.domain.settings.NotificationPreferences
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Ownership-based cleanup and state-guard regression tests for [FixtureSeeder].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FixtureOwnershipAuditTest {
    @get:Rule val temp = TemporaryFolder()

    private class FakeNotificationStore(
        initial: NotificationPreferences = NotificationPreferences(),
    ) : NotificationPreferenceStore {
        var current: NotificationPreferences = initial

        override suspend fun notificationPreferences(): NotificationPreferences = current

        override suspend fun updateNotificationPreferences(preferences: NotificationPreferences) {
            current = preferences
        }
    }

    private data class Harness(
        val db: CodexMeterDatabase,
        val seeder: FixtureSeeder,
        val sessions: FileSecureSessionStore,
        val prefs: CurrentAccountStore,
        val clock: Clock,
        val stateGuard: VersionedStateGuard,
        val cleanedWidgetAccounts: MutableList<Pair<ProviderId, LocalAccountId>>,
        val notificationStore: FakeNotificationStore,
        val republishCount: () -> Int,
    )

    private fun harness(): Harness {
        val db = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            CodexMeterDatabase::class.java,
        ).build()
        val sessions = FileSecureSessionStore(temp.newFolder())
        val prefs = object : CurrentAccountStore {
            private var value: CurrentAccountSelection? = null

            override suspend fun currentAccountSelection() = value

            override suspend fun updateCurrentAccountSelection(selection: CurrentAccountSelection?) {
                value = selection
            }
        }
        val clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC)
        val stateGuard = VersionedStateGuard()
        val cleanedWidgetAccounts = mutableListOf<Pair<ProviderId, LocalAccountId>>()
        val cleaner = object : DeletedAccountStateCleaner {
            override suspend fun clearDeletedAccountState(
                providerId: ProviderId,
                localAccountId: LocalAccountId,
            ): DeletedAccountStateCleanup {
                cleanedWidgetAccounts += providerId to localAccountId
                return DeletedAccountStateCleanup { }
            }
        }
        val notificationStore = FakeNotificationStore()
        var republishes = 0
        val bridge = DebugFixtureBridge(
            database = db,
            sessionStore = sessions,
            currentAccountStore = prefs,
            clock = clock,
            stateGuard = stateGuard,
            deletedAccountStateCleaner = cleaner,
            notificationPreferenceStore = notificationStore,
            republishCurrentState = { republishes++ },
        )
        return Harness(
            db = db,
            seeder = FixtureSeeder(bridge),
            sessions = sessions,
            prefs = prefs,
            clock = clock,
            stateGuard = stateGuard,
            cleanedWidgetAccounts = cleanedWidgetAccounts,
            notificationStore = notificationStore,
            republishCount = { republishes },
        )
    }

    @Test
    fun cleanMatchesOnlyFixtureIdentities() = runTest {
        val h = harness()
        val scenario = requireNotNull(FixtureScenarios.build("kimi_active", h.clock.instant()))
        val generated = scenario.accounts.single()
        h.seeder.seed(scenario)

        // An ordinary account that merely SHARES the fixture display-name prefix must survive.
        val normal = ProviderAccount.createNew(
            LocalAccountId("local-normal-123"),
            ProviderId("codex"),
            ProviderAccountId("audit-only"),
            "Fixture QA",
            h.clock.instant(),
        )
        h.db.providerAccountDao().upsert(normal.toEntity())
        h.sessions.save(
            generated.sessionEnvelope.copy(
                providerId = "codex",
                localAccountId = normal.localAccountId.value,
            ),
        )

        // Renaming the fixture must NOT make it immortal.
        assertEquals(
            1,
            h.db.providerAccountDao().updateDisplayName(
                generated.account.providerId.value,
                generated.account.localAccountId.value,
                "Renamed demo",
                "R",
                h.clock.millis(),
            ),
        )

        h.seeder.cleanFixtureAccounts()

        val ordinaryExists = h.db.providerAccountDao().getById(normal.localAccountId.value) != null
        val sessionExists = h.sessions.load("codex", normal.localAccountId.value) != null
        val fixtureExists = h.db.providerAccountDao().getById(generated.account.localAccountId.value) != null
        h.db.close()
        assertTrue(
            "ordinary row and session should survive; renamed fixture should be removed",
            ordinaryExists && sessionExists && !fixtureExists,
        )
    }

    @Test
    fun seedAndCleanAdvanceStateGuardAndInvalidateInFlightEpoch() = runTest {
        val h = harness()
        val scenario = requireNotNull(FixtureScenarios.build("kimi_active", h.clock.instant()))
        val targetId = scenario.selection.localAccountId

        val beforeSeedEpoch = h.stateGuard.captureReadToken(targetId)
        h.seeder.seed(scenario)
        assertEquals(0, h.stateGuard.inFlightEdits)
        assertFalse(h.stateGuard.isFresh(beforeSeedEpoch, targetId))
        val genAfterSeed = h.stateGuard.generation
        assertTrue(genAfterSeed > beforeSeedEpoch.generation)

        val beforeCleanEpoch = h.stateGuard.captureReadToken(targetId)
        h.seeder.cleanFixtureAccounts()
        assertEquals(0, h.stateGuard.inFlightEdits)
        assertFalse(h.stateGuard.isFresh(beforeCleanEpoch, targetId))
        assertTrue(h.stateGuard.generation > genAfterSeed)
        h.db.close()
    }

    @Test
    fun cleanSkipsDeletedAccountsWhenRepairingSelectionAndCleansWidgetAndNotificationBindings() = runTest {
        val h = harness()
        val scenario = requireNotNull(FixtureScenarios.build("kimi_active", h.clock.instant()))
        val fixtureAccount = scenario.selection

        // Deleted account inserted before active normal account; repair must skip it.
        val deletedOrdinary = ProviderAccount.createNew(
            LocalAccountId("local-deleted-1"),
            ProviderId("codex"),
            ProviderAccountId("deleted-pa"),
            "Deleted User",
            h.clock.instant().minusSeconds(10),
        ).copy(status = AccountStatus.Deleted)
        val activeOrdinary = ProviderAccount.createNew(
            LocalAccountId("local-active-2"),
            ProviderId("codex"),
            ProviderAccountId("active-pa"),
            "Active User",
            h.clock.instant().minusSeconds(5),
        )
        h.db.providerAccountDao().upsert(deletedOrdinary.toEntity())
        h.db.providerAccountDao().upsert(activeOrdinary.toEntity())

        h.seeder.seed(scenario)
        h.notificationStore.current = NotificationPreferences(
            persistentNotificationAccount = NotificationAccountSelection(
                providerId = fixtureAccount.providerId,
                localAccountId = fixtureAccount.localAccountId,
            ),
        )
        val republishesBeforeClean = h.republishCount()

        h.seeder.cleanFixtureAccounts()

        val repairedSelection = h.prefs.currentAccountSelection()
        assertEquals(activeOrdinary.localAccountId, repairedSelection?.localAccountId)
        assertEquals(
            listOf(fixtureAccount.providerId to fixtureAccount.localAccountId),
            h.cleanedWidgetAccounts,
        )
        assertEquals(
            activeOrdinary.localAccountId,
            h.notificationStore.current.persistentNotificationAccount?.localAccountId,
        )
        assertEquals(republishesBeforeClean + 1, h.republishCount())

        // If only deleted rows remain, cleaning a newly seeded fixture clears selection and binding.
        h.db.providerAccountDao().delete(activeOrdinary.toEntity())
        h.seeder.seed(scenario)
        h.notificationStore.current = NotificationPreferences(
            persistentNotificationAccount = NotificationAccountSelection(
                providerId = fixtureAccount.providerId,
                localAccountId = fixtureAccount.localAccountId,
            ),
        )
        h.seeder.cleanFixtureAccounts()

        assertNull(h.prefs.currentAccountSelection())
        assertNull(h.notificationStore.current.persistentNotificationAccount)
        h.db.close()
    }

    @Test
    fun productionHomeReadsAllSeededScenarios() = runTest {
        val h = harness()
        val home = CurrentQuotaStateRepository(
            h.prefs,
            h.db.providerAccountDao(),
            h.db.quotaSnapshotDao(),
            h.db.refreshAttemptDao(),
            clock = h.clock,
        )
        for (name in FixtureScenarios.ALL) {
            val scenario = requireNotNull(FixtureScenarios.build(name, h.clock.instant()))
            h.seeder.seed(scenario)
            val state = home.loadCurrentState()
            val windows = state.snapshot?.windows.orEmpty()
            println(
                "scenario=$name status=${state.status} windows=${windows.size} " +
                    "selected=${state.account?.localAccountId?.value}",
            )
            assertEquals(scenario.selection.localAccountId, state.account?.localAccountId)
            assertEquals(scenario.accounts[scenario.selectedAccountIndex].snapshots.single().windows, windows)
        }
        h.db.close()
    }
}
