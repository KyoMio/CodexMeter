package com.kmnexus.codexmeter.notification

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * CM-10 R1: single serialization boundary for the persistent-notification target.
 *
 * Every write that changes the resolved notification target — default-account selection writes
 * (AccountMutationRepository), pin writes (SettingsViewModel preference persistence), and the
 * fallback-target writes in account deletion — must execute inside [serialize]. Symmetrically,
 * the status-notification posting path resolves the target and posts inside the same mutex.
 *
 * Lock-order contract (deadlock freedom): the refresh/post path enters
 * `VersionedStateGuard.commitMutex` FIRST and this mutex SECOND. No writer is allowed to acquire
 * `commitMutex` while holding this mutex. Writers acquire ONLY this mutex around the target
 * write itself; post-write refreshes and republishes run after the lock is released
 * ("写入完成后释放锁再刷新" — no nested waits inside the lock).
 *
 * Why this exists: CM-10 (R0) dropped notifications whose loaded account no longer matched the
 * freshly re-resolved target — but the re-check and the system post were still two steps, and
 * the selection write never took any lock the posting side held. An adversarial interleaving
 * (probe: pause inside the sink, commit B selection, resume) could still post the Alpha
 * notification after B was durably selected. With both sides behind one mutex, either the post
 * runs before the write (valid for the then-current target) or after it (re-check sees B and
 * drops the stale frame); the interleaving in between is no longer schedulable.
 */
class NotificationTargetBoundary {
    private val mutex = Mutex()

    suspend fun <T> serialize(block: suspend () -> T): T = mutex.withLock { block() }
}
