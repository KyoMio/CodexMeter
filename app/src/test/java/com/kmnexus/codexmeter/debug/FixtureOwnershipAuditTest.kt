package com.kmnexus.codexmeter.debug

import androidx.room.Room
import com.kmnexus.codexmeter.app.CurrentQuotaStateRepository
import com.kmnexus.codexmeter.app.DebugFixtureBridge
import com.kmnexus.codexmeter.data.local.db.CodexMeterDatabase
import com.kmnexus.codexmeter.data.preferences.CurrentAccountSelection
import com.kmnexus.codexmeter.data.preferences.CurrentAccountStore
import com.kmnexus.codexmeter.data.repository.toEntity
import com.kmnexus.codexmeter.data.secure.FileSecureSessionStore
import com.kmnexus.codexmeter.domain.model.LocalAccountId
import com.kmnexus.codexmeter.domain.model.ProviderAccount
import com.kmnexus.codexmeter.domain.model.ProviderAccountId
import com.kmnexus.codexmeter.domain.model.ProviderId
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Ownership-based cleanup regression (CM-5 R1 item 2).
 *
 * The original cleaner keyed deletion on the display-name prefix: a renamed fixture turned
 * immortal, and an ordinary account merely NAMED "Fixture QA" got wiped along with its
 * session. The cleaner now keys on the immutable "fixture-" localAccountId prefix; both
 * halves of that contract are asserted here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FixtureOwnershipAuditTest {
    @get:Rule val temp = TemporaryFolder()

    private data class Harness(
        val db: CodexMeterDatabase,
        val seeder: FixtureSeeder,
        val sessions: FileSecureSessionStore,
        val prefs: CurrentAccountStore,
        val clock: Clock,
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
        return Harness(db, FixtureSeeder(DebugFixtureBridge(db, sessions, prefs, clock)), sessions, prefs, clock)
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
