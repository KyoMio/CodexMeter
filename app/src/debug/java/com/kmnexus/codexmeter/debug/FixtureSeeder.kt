package com.kmnexus.codexmeter.debug

import androidx.room.withTransaction
import com.kmnexus.codexmeter.app.DebugFixtureBridge
import com.kmnexus.codexmeter.data.preferences.CurrentAccountSelection
import com.kmnexus.codexmeter.data.repository.toEntity
import com.kmnexus.codexmeter.domain.model.LocalAccountId
import com.kmnexus.codexmeter.domain.model.ProviderId

/**
 * Executes fixture scenarios against the app's real storage (Room + session files + the
 * current-account DataStore) through the debug-only [DebugFixtureBridge] seam.
 *
 * Debug builds only; none of this ships in release. Seeding is idempotent: every seed first
 * wipes all pre-existing fixture rows, so repeated broadcasts never pile up duplicates.
 */
class FixtureSeeder(
    private val bridge: DebugFixtureBridge,
) {
    /**
     * Seeds [scenario] into the database and points the current-account selection at the
     * scenario's designated account. Any pre-existing fixture rows are removed first.
     */
    suspend fun seed(scenario: FixtureScenario) {
        cleanFixtureAccounts()
        for (fixtureAccount in scenario.accounts) {
            bridge.sessionStore.save(fixtureAccount.sessionEnvelope)
        }
        bridge.database.withTransaction {
            val accountDao = bridge.database.providerAccountDao()
            val snapshotDao = bridge.database.quotaSnapshotDao()
            for (fixtureAccount in scenario.accounts) {
                accountDao.upsert(fixtureAccount.account.toEntity())
                for (snapshot in fixtureAccount.snapshots) {
                    snapshotDao.insert(snapshot.toEntity())
                }
            }
        }
        bridge.currentAccountStore.updateCurrentAccountSelection(
            CurrentAccountSelection(
                providerId = scenario.selection.providerId,
                localAccountId = scenario.selection.localAccountId,
            ),
        )
    }

    /**
     * Removes every account whose display name carries the fixture marker prefix, along with
     * its snapshots, refresh attempts, alert state and session file. If the current selection
     * pointed at a deleted fixture account it is moved to the first remaining account (or
     * cleared when no accounts remain).
     */
    suspend fun cleanFixtureAccounts() {
        val fixtureAccounts = bridge.database.providerAccountDao().listAll()
            .filter { it.displayName.startsWith(FixtureScenarios.DISPLAY_NAME_PREFIX) }
        if (fixtureAccounts.isEmpty()) return

        bridge.database.withTransaction {
            val accountDao = bridge.database.providerAccountDao()
            val snapshotDao = bridge.database.quotaSnapshotDao()
            val attemptDao = bridge.database.refreshAttemptDao()
            val alertDao = bridge.database.alertStateDao()
            for (fixtureAccount in fixtureAccounts) {
                snapshotDao.deleteForAccount(
                    providerId = fixtureAccount.providerId,
                    localAccountId = fixtureAccount.localAccountId,
                )
                attemptDao.deleteForAccount(
                    providerId = fixtureAccount.providerId,
                    localAccountId = fixtureAccount.localAccountId,
                )
                alertDao.deleteForAccount(
                    providerId = fixtureAccount.providerId,
                    localAccountId = fixtureAccount.localAccountId,
                )
                accountDao.delete(fixtureAccount)
            }
        }
        for (fixtureAccount in fixtureAccounts) {
            bridge.sessionStore.delete(
                providerId = fixtureAccount.providerId,
                localAccountId = fixtureAccount.localAccountId,
            )
        }
        repairSelectionAfterClean(fixtureAccounts.map { it.localAccountId }.toSet())
    }

    private suspend fun repairSelectionAfterClean(deletedLocalAccountIds: Set<String>) {
        val selection = bridge.currentAccountStore.currentAccountSelection() ?: return
        if (selection.localAccountId.value !in deletedLocalAccountIds) return
        val remaining = bridge.database.providerAccountDao().listAll().firstOrNull()
        bridge.currentAccountStore.updateCurrentAccountSelection(
            remaining?.let {
                CurrentAccountSelection(
                    providerId = ProviderId(it.providerId),
                    localAccountId = LocalAccountId(it.localAccountId),
                )
            },
        )
    }
}
