package com.kmnexus.codexmeter.notification

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * CM-10 R1/R2: single serialization boundary for the persistent-notification target.
 *
 * Every write that moves the effective notification target must execute inside [serialize]:
 * - default-account selection writes (AccountMutationRepository switch,
 *   AccountDeletionRepository fallback + rollback restore, RoomCodexSessionImportPersistence
 *   import re-selection + rollback, SessionLoginUseCase/ApiKeyLoginUseCase first-login selection)
 * - pin writes and deletion rebinds (RepublishingNotificationPreferenceStore,
 *   AccountDeletionRepository rebind — pin re-read + decide + write inside the lock)
 * - the Room account-row deletion that decides pin liveness (AccountDeletionRepository)
 *
 * Symmetrically, the status-notification posting path re-resolves the target and posts inside
 * the same mutex.
 *
 * Lock-order contract (deadlock freedom): the refresh/post path enters
 * `VersionedStateGuard.commitMutex` FIRST and this mutex SECOND. Writers acquire ONLY this
 * mutex around the target write itself; post-write refreshes and republishes run after the
 * lock is released — never nested inside it.
 */
class NotificationTargetBoundary {
    private val mutex = Mutex()

    suspend fun <T> serialize(block: suspend () -> T): T = mutex.withLock { block() }
}
