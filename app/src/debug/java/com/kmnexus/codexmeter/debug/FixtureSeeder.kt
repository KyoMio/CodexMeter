package com.kmnexus.codexmeter.debug

import androidx.room.withTransaction
import com.kmnexus.codexmeter.app.DebugFixtureBridge
import com.kmnexus.codexmeter.data.local.entity.ProviderAccountEntity
import com.kmnexus.codexmeter.data.preferences.CurrentAccountSelection
import com.kmnexus.codexmeter.data.repository.toEntity
import com.kmnexus.codexmeter.domain.model.LocalAccountId
import com.kmnexus.codexmeter.domain.model.ProviderId
import com.kmnexus.codexmeter.domain.settings.NotificationAccountSelection
import kotlinx.coroutines.CancellationException

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
        bridge.stateGuard.beginEdit()
        try {
            cleanFixtureAccountsLocked()
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
        } finally {
            bridge.stateGuard.endEdit()
        }
        republishStateBestEffort()
    }

    /**
     * Removes every fixture-owned account along with its snapshots, refresh attempts, alert
     * state, widget binding, persistent-notification binding and session file. Ownership is keyed
     * on the immutable identity the fixture builders assign — a localAccountId with the "fixture-"
     * prefix — never on the display name.
     */
    suspend fun cleanFixtureAccounts() {
        val cleaned = run {
            bridge.stateGuard.beginEdit()
            try {
                cleanFixtureAccountsLocked()
            } finally {
                bridge.stateGuard.endEdit()
            }
        }
        if (cleaned) {
            republishStateBestEffort()
        }
    }

    private suspend fun cleanFixtureAccountsLocked(): Boolean {
        val fixtureAccounts = bridge.database.providerAccountDao().listAll()
            .filter { it.localAccountId.startsWith(FixtureScenarios.FIXTURE_ID_PREFIX) }
        if (fixtureAccounts.isEmpty()) return false

        deleteFixtureRows(fixtureAccounts)
        for (fixtureAccount in fixtureAccounts) {
            runBestEffort {
                bridge.deletedAccountStateCleaner?.clearDeletedAccountState(
                    providerId = ProviderId(fixtureAccount.providerId),
                    localAccountId = LocalAccountId(fixtureAccount.localAccountId),
                )
            }
            bridge.sessionStore.delete(
                providerId = fixtureAccount.providerId,
                localAccountId = fixtureAccount.localAccountId,
            )
        }
        val deletedIds = fixtureAccounts.map { it.localAccountId }.toSet()
        repairSelectionAfterClean(deletedIds)
        rebindNotificationAccountAfterClean(deletedIds)
        return true
    }

    private suspend fun deleteFixtureRows(fixtureAccounts: List<ProviderAccountEntity>) {
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
    }

    private suspend fun repairSelectionAfterClean(deletedLocalAccountIds: Set<String>) {
        val selection = bridge.currentAccountStore.currentAccountSelection() ?: return
        if (selection.localAccountId.value !in deletedLocalAccountIds) return
        val remaining = bridge.database.providerAccountDao().listAll()
            .filterNot { it.status == DELETED_ACCOUNT_STATUS }
            .firstOrNull()
        bridge.currentAccountStore.updateCurrentAccountSelection(
            remaining?.let {
                CurrentAccountSelection(
                    providerId = ProviderId(it.providerId),
                    localAccountId = LocalAccountId(it.localAccountId),
                )
            },
        )
    }

    private suspend fun rebindNotificationAccountAfterClean(deletedLocalAccountIds: Set<String>) {
        val store = bridge.notificationPreferenceStore ?: return
        runBestEffort {
            val preferences = store.notificationPreferences()
            val bound = preferences.persistentNotificationAccount ?: return@runBestEffort
            if (bound.localAccountId.value !in deletedLocalAccountIds) return@runBestEffort
            val fallback = bridge.currentAccountStore.currentAccountSelection()
            store.updateNotificationPreferences(
                preferences.copy(
                    persistentNotificationAccount = fallback?.let {
                        NotificationAccountSelection(
                            providerId = it.providerId,
                            localAccountId = it.localAccountId,
                        )
                    },
                ),
            )
        }
    }

    private suspend fun republishStateBestEffort() {
        val republish = bridge.republishCurrentState ?: return
        runBestEffort { republish() }
    }

    private suspend fun runBestEffort(block: suspend () -> Unit) {
        val result = runCatching { block() }
        val err = result.exceptionOrNull()
        if (err is CancellationException) throw err
    }

    private companion object {
        const val DELETED_ACCOUNT_STATUS = "deleted"
    }
}
