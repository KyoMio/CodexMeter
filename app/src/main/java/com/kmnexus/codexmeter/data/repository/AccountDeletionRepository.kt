package com.kmnexus.codexmeter.data.repository

import androidx.room.withTransaction
import com.kmnexus.codexmeter.core.concurrency.VersionedStateGuard
import com.kmnexus.codexmeter.data.local.db.CodexMeterDatabase
import com.kmnexus.codexmeter.data.preferences.CurrentAccountSelection
import com.kmnexus.codexmeter.data.preferences.CurrentAccountStore
import com.kmnexus.codexmeter.data.secure.ProviderSessionEnvelope
import com.kmnexus.codexmeter.data.secure.SecureSessionStore
import com.kmnexus.codexmeter.domain.account.DeletedAccountStateCleanup
import com.kmnexus.codexmeter.domain.account.DeletedAccountStateCleaner
import com.kmnexus.codexmeter.domain.model.LocalAccountId
import com.kmnexus.codexmeter.domain.model.ProviderId
import com.kmnexus.codexmeter.domain.settings.NotificationAccountSelection
import com.kmnexus.codexmeter.domain.settings.NotificationPreferenceStore
import com.kmnexus.codexmeter.notification.NotificationTargetBoundary

/**
 * CM-10 R4 ordering protocol — the delete side.
 *
 * The destructive delete core (fallback selection write → state cleanup capture → credential
 * file removal → Room row removal) runs as ONE critical section inside the shared
 * notification-target boundary. Login/import commits serialize their decision+writes through
 * the same boundary, so exactly one ordering exists for every race:
 *  - commit first: the delete removes everything the commit just wrote;
 *  - delete first: the commit's in-lock re-check sees the row gone and discards its late result.
 *
 * Compensation (delete failure) reverses only the writes THIS deletion still owns, each guarded
 * against supersession: a credential file is only restored when none exists (a later re-login
 * may have written a fresh one), and the selection is only restored when it still holds the
 * fallback this deletion wrote (a later write may have moved it). Compensation steps use
 * separate short boundary sections — never one long hold — because cleanup restore callbacks
 * may suspend on external coordination; the ownership guards keep each step correct without
 * atomicity.
 */
class AccountDeletionRepository(
    private val database: CodexMeterDatabase,
    private val secureSessionStore: SecureSessionStore,
    private val currentAccountStore: CurrentAccountStore,
    private val deletedAccountStateCleaner: DeletedAccountStateCleaner,
    private val localDataDeleter: AccountLocalDataDeleter = RoomAccountLocalDataDeleter(database),
    private val notificationPreferenceStore: NotificationPreferenceStore? = null,
    private val stateGuard: VersionedStateGuard,
    internal val notificationTargetBoundary: NotificationTargetBoundary = NotificationTargetBoundary(),
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

        var selectionFallbackWritten = false
        var previousSelection: CurrentAccountSelection? = null
        var nextSelection: CurrentAccountSelection? = null
        var sessionBeforeDelete: ProviderSessionEnvelope? = null

        // State-cleanup CAPTURE runs outside the boundary: it is not part of the file/row/selection
        // ordering (it only snapshots state for a possible compensation) and audit choreography
        // depends on it being reachable while the destructive section waits on the boundary.
        // It still runs before anything destructive, so compensation restores pre-delete state.
        val deletedAccountStateCleanup = runCatching {
            deletedAccountStateCleaner.clearDeletedAccountState(
                providerId = providerId,
                localAccountId = localAccountId,
            )
        }.getOrElse { throw it }

        try {
            // CM-10 R4: the destructive core — fallback decision, selection write, credential
            // removal, row removal — is ONE critical section, and the decision reads happen inside
            // it. A login/import commit suspended mid-validation either finished before this section
            // (and is removed here wholesale) or enters the boundary after it (and discards itself).
            notificationTargetBoundary.serialize {
                previousSelection = currentAccountStore.currentAccountSelection()
                nextSelection = nextCurrentSelectionAfterDeleting(
                    providerId = providerId,
                    deletedLocalAccountId = localAccountId,
                    currentSelection = previousSelection,
                )
                if (previousSelection?.providerId == providerId &&
                    previousSelection?.localAccountId == localAccountId
                ) {
                    currentAccountStore.updateCurrentAccountSelection(nextSelection)
                    selectionFallbackWritten = true
                }
                sessionBeforeDelete = secureSessionStore.load(
                    providerId = providerValue,
                    localAccountId = localValue,
                )
                secureSessionStore.delete(
                    providerId = providerValue,
                    localAccountId = localValue,
                )
                // CM-10 R2: the Room row deletion decides pin liveness — the resolver falls back
                // the moment the row is gone.
                localDataDeleter.deleteLocalData(providerId = providerId, localAccountId = localAccountId)
            }
        } catch (throwable: Throwable) {
            val selectionRollback = if (selectionFallbackWritten) {
                SelectionRollback(previousSelection = previousSelection, nextSelection = nextSelection)
            } else {
                null
            }
            compensateDeleteFailure(
                DeleteCompensationContext(
                    providerId = providerValue,
                    localAccountId = localValue,
                    deletedAccountStateCleanup = deletedAccountStateCleanup,
                    sessionBeforeDelete = sessionBeforeDelete,
                    selectionRollback = selectionRollback,
                ),
            )
            throw throwable
        }

        rebindNotificationAccountAfterDelete(
            providerId = providerId,
            localAccountId = localAccountId,
        )
    }

    private suspend fun compensateDeleteFailure(context: DeleteCompensationContext) {
        // Compensation, ownership-guarded per step. Order: state cleanup, credential file,
        // selection. Each step reverses only what this deletion wrote and nobody superseded.
        context.deletedAccountStateCleanup?.let { cleanup ->
            runCatching { cleanup.restore() }
        }
        context.sessionBeforeDelete?.let { envelopeBeforeDelete ->
            runCatching {
                notificationTargetBoundary.serialize {
                    val current = secureSessionStore.load(
                        providerId = context.providerId,
                        localAccountId = context.localAccountId,
                    )
                    if (current == null) {
                        // Only restore when no newer credential landed in the meantime —
                        // compensation must never overwrite a committed re-login.
                        secureSessionStore.save(envelopeBeforeDelete)
                    }
                }
            }
        }
        context.selectionRollback?.let { rollback ->
            runCatching {
                notificationTargetBoundary.serialize {
                    val current = currentAccountStore.currentAccountSelection()
                    if (current == rollback.nextSelection) {
                        // Only restore when the fallback we wrote is still in place — a later
                        // committed write owns the selection now.
                        currentAccountStore.updateCurrentAccountSelection(rollback.previousSelection)
                    }
                }
            }
        }
    }

    private class SelectionRollback(
        val previousSelection: CurrentAccountSelection?,
        val nextSelection: CurrentAccountSelection?,
    )

    private class DeleteCompensationContext(
        val providerId: String,
        val localAccountId: String,
        val deletedAccountStateCleanup: DeletedAccountStateCleanup?,
        val sessionBeforeDelete: ProviderSessionEnvelope?,
        val selectionRollback: SelectionRollback?,
    )

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
