package com.kmnexus.codexmeter.data.repository

import androidx.room.withTransaction
import com.kmnexus.codexmeter.core.concurrency.VersionedStateGuard
import com.kmnexus.codexmeter.data.local.db.CodexMeterDatabase
import com.kmnexus.codexmeter.data.preferences.CurrentAccountSelection
import com.kmnexus.codexmeter.data.preferences.CurrentAccountStore
import com.kmnexus.codexmeter.data.secure.SecureSessionStore
import com.kmnexus.codexmeter.domain.account.DeletedAccountStateCleanup
import com.kmnexus.codexmeter.domain.account.DeletedAccountStateCleaner
import com.kmnexus.codexmeter.domain.model.LocalAccountId
import com.kmnexus.codexmeter.domain.model.ProviderId
import com.kmnexus.codexmeter.domain.settings.NotificationAccountSelection
import com.kmnexus.codexmeter.domain.settings.NotificationPreferenceStore

class AccountDeletionRepository(
    private val database: CodexMeterDatabase,
    private val secureSessionStore: SecureSessionStore,
    private val currentAccountStore: CurrentAccountStore,
    private val deletedAccountStateCleaner: DeletedAccountStateCleaner,
    private val localDataDeleter: AccountLocalDataDeleter = RoomAccountLocalDataDeleter(database),
    private val notificationPreferenceStore: NotificationPreferenceStore? = null,
    private val stateGuard: VersionedStateGuard,
    private val notificationTargetBoundary: com.kmnexus.codexmeter.notification.NotificationTargetBoundary =
        com.kmnexus.codexmeter.notification.NotificationTargetBoundary(),
) {
    suspend fun deleteAccount(
        providerId: ProviderId,
        localAccountId: LocalAccountId,
    ) {
        // Deletion is the strongest local edit: any in-flight refresh for this account (or any
        // other, since the selection may move) must not write back over the post-delete state.
        stateGuard.beginEdit()
        try {
            deleteAccountGuarded(providerId = providerId, localAccountId = localAccountId)
        } finally {
            stateGuard.endEdit()
        }
    }

    private suspend fun deleteAccountGuarded(
        providerId: ProviderId,
        localAccountId: LocalAccountId,
    ) {
        val providerValue = providerId.value
        val localValue = localAccountId.value
        val previousSelection = currentAccountStore.currentAccountSelection()
        val nextSelection = nextCurrentSelectionAfterDeleting(
            providerId = providerId,
            deletedLocalAccountId = localAccountId,
            currentSelection = previousSelection,
        )
        val shouldUpdateCurrentSelection = previousSelection?.providerId == providerId &&
            previousSelection.localAccountId == localAccountId
        val sessionBeforeDelete = secureSessionStore.load(
            providerId = providerValue,
            localAccountId = localValue,
        )

        var deletedAccountStateCleanup: DeletedAccountStateCleanup? = null

        try {
            if (shouldUpdateCurrentSelection) {
                // CM-10 R1: deletion moves the notification target — serialize against posts.
                notificationTargetBoundary.serialize {
                    currentAccountStore.updateCurrentAccountSelection(nextSelection)
                }
            }
            deletedAccountStateCleanup = deletedAccountStateCleaner.clearDeletedAccountState(
                providerId = providerId,
                localAccountId = localAccountId,
            )
            secureSessionStore.delete(
                providerId = providerValue,
                localAccountId = localValue,
            )
            // CM-10 R2: the Room row deletion decides pin liveness — the resolver falls back the
            // moment the row is gone. Serialize against the posting boundary: either the post
            // completes first (correct for the then-live pin) or the row is already gone when
            // the post re-checks (stale pinned frame dropped).
            notificationTargetBoundary.serialize {
                localDataDeleter.deleteLocalData(providerId = providerId, localAccountId = localAccountId)
            }
        } catch (throwable: Throwable) {
            deletedAccountStateCleanup?.let { cleanup ->
                runCatching { cleanup.restore() }
            }
            if (sessionBeforeDelete != null) {
                runCatching { secureSessionStore.save(sessionBeforeDelete) }
            }
            if (shouldUpdateCurrentSelection) {
                // CM-10 R2: rollback restores the target — serialize against posts, or a frame
                // built for the abandoned fallback lands after the restore.
                runCatching {
                    notificationTargetBoundary.serialize {
                        currentAccountStore.updateCurrentAccountSelection(previousSelection)
                    }
                }
            }
            throw throwable
        }

        rebindNotificationAccountAfterDelete(
            providerId = providerId,
            localAccountId = localAccountId,
        )
    }

    /**
     * Notification binding is a concrete account. When that account is deleted, the runtime falls
     * back to the default account while Settings would still show the dead id — lock the binding to
     * whatever the runtime would now use (the post-delete default), or clear it when nothing is
     * left. Runs after the transactional delete; a failure here must not roll back the deletion.
     */
    private suspend fun rebindNotificationAccountAfterDelete(
        providerId: ProviderId,
        localAccountId: LocalAccountId,
    ) {
        val store = notificationPreferenceStore ?: return
        runCatching {
            // CM-10 R2: re-read the pin INSIDE the lock and decide there. Reading outside and
            // writing inside still lets a concurrent pin write slip between the two — the
            // decision would rebind a pin the user just changed.
            notificationTargetBoundary.serialize {
                val preferences = store.notificationPreferences()
                val bound = preferences.persistentNotificationAccount
                if (bound?.providerId != providerId || bound.localAccountId != localAccountId) {
                    return@serialize
                }
                val fallback = currentAccountStore.currentAccountSelection()
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
    }

    private suspend fun nextCurrentSelectionAfterDeleting(
        providerId: ProviderId,
        deletedLocalAccountId: LocalAccountId,
        currentSelection: CurrentAccountSelection?,
    ): CurrentAccountSelection? {
        if (currentSelection?.providerId != providerId ||
            currentSelection.localAccountId != deletedLocalAccountId
        ) {
            return currentSelection
        }

        val providerAccountDao = database.providerAccountDao()
        // Prefer another account on the same provider…
        providerAccountDao
            .listByProvider(providerId.value)
            .filterNot { it.localAccountId == deletedLocalAccountId.value }
            .firstOrNull()
            ?.let { account ->
                return CurrentAccountSelection(
                    providerId = providerId,
                    localAccountId = LocalAccountId(account.localAccountId),
                )
            }
        // …otherwise fall back to the next usable account on ANY provider, so Home is never
        // stranded on an empty default while accounts still exist.
        return providerAccountDao.listAll()
            .filterNot { it.localAccountId == deletedLocalAccountId.value }
            .filterNot { it.status == "deleted" }
            .firstOrNull()
            ?.let { account ->
                CurrentAccountSelection(
                    providerId = ProviderId(account.providerId),
                    localAccountId = LocalAccountId(account.localAccountId),
                )
            }
    }
}

interface AccountLocalDataDeleter {
    suspend fun deleteLocalData(
        providerId: ProviderId,
        localAccountId: LocalAccountId,
    )
}

class RoomAccountLocalDataDeleter(
    private val database: CodexMeterDatabase,
) : AccountLocalDataDeleter {
    override suspend fun deleteLocalData(
        providerId: ProviderId,
        localAccountId: LocalAccountId,
    ) {
        val providerValue = providerId.value
        val localValue = localAccountId.value
        database.withTransaction {
            val account = database.providerAccountDao().getById(localValue)
                ?.takeIf { it.providerId == providerValue }

            database.quotaSnapshotDao().deleteForAccount(
                providerId = providerValue,
                localAccountId = localValue,
            )
            database.refreshAttemptDao().deleteForAccount(
                providerId = providerValue,
                localAccountId = localValue,
            )
            database.alertStateDao().deleteForAccount(
                providerId = providerValue,
                localAccountId = localValue,
            )
            if (account != null) {
                database.providerAccountDao().delete(account)
            }
        }
    }
}
