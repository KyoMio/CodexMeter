package com.kmnexus.codexmeter.core.concurrency

import com.kmnexus.codexmeter.domain.model.LocalAccountId
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Version token tying an asynchronous write-back to the account and state generation it started
 * from. The token is a plain value captured cheaply before network or database I/O and re-validated
 * at commit time; it never holds a lock across suspension points.
 *
 * @property accountId the local account the write-back is about
 * @property generation the state generation at capture time; any local edit that starts afterwards
 *   increments the generation and makes this token stale
 */
data class SnapshotEpoch(
    val accountId: LocalAccountId,
    val generation: Long,
)

/**
 * Compile-enforced defense against stale asynchronous write-backs (collab-rule A8: a late result
 * must never clobber newer state).
 *
 * The guard tracks two things:
 * - a monotonically increasing generation, bumped whenever a local edit (rename, deletion,
 *   re-selection) starts,
 * - the number of local edits currently in flight.
 *
 * Account identity is deliberately NOT stored here: the durable selection lives in DataStore and is
 * mutated by several repositories, so a cached copy inside the guard could itself go stale. Instead
 * the caller re-reads the selected account at commit time and passes it to [commitIfFresh].
 *
 * Writers capture an epoch with [captureReadToken] BEFORE starting their asynchronous work and must
 * funnel the write-back through [commitIfFresh]. The commit runs only when all of these hold:
 * 1. the generation has not advanced since capture (no edit started in between),
 * 2. no local edit is in flight right now (the durable stores may be mid-transition),
 * 3. the freshly re-read selected account is still the epoch's account.
 *
 * Otherwise the write is deterministically discarded and counted in [discardedWrites]. Because
 * [commitIfFresh] demands a [SnapshotEpoch] in its signature, a bare unguarded overwrite cannot be
 * expressed — the type system refuses it.
 *
 * The guard is UI-agnostic and diagnostics-safe: it exposes counters only, never account content.
 */
class VersionedStateGuard {

    private val generationCounter = AtomicLong(0L)
    private val inFlightEditCounter = AtomicInteger(0)
    private val discardedWriteCounter = AtomicInteger(0)
    private val committedWriteCounter = AtomicInteger(0)

    /** Total write-backs discarded because their epoch was stale. Diagnostics-safe counter only. */
    val discardedWrites: Int
        get() = discardedWriteCounter.get()

    /** Total write-backs committed through the guard. */
    val committedWrites: Int
        get() = committedWriteCounter.get()

    /** Number of local edits currently executing. Exposed for tests and diagnostics. */
    val inFlightEdits: Int
        get() = inFlightEditCounter.get()

    /** Current generation. Exposed for tests and diagnostics. */
    val generation: Long
        get() = generationCounter.get()

    /** Captures the epoch a new asynchronous operation starts from. */
    fun captureReadToken(accountId: LocalAccountId): SnapshotEpoch =
        SnapshotEpoch(accountId = accountId, generation = generationCounter.get())

    /**
     * Marks a local edit (rename, deletion, re-selection) as started: the generation advances
     * immediately so every epoch captured before this moment is already stale, and the in-flight
     * count increments so commits during the edit window are dropped too.
     *
     * MUST be paired with [endEdit] in a finally block.
     */
    fun beginEdit() {
        generationCounter.incrementAndGet()
        inFlightEditCounter.incrementAndGet()
    }

    /** Marks a local edit as finished. See [beginEdit]. */
    fun endEdit() {
        inFlightEditCounter.decrementAndGet()
    }

    /**
     * Returns true when [epoch] still describes the live state: same generation, no edit in flight,
     * and the freshly re-read [selectedAccountId] is still the epoch's account.
     */
    fun isFresh(epoch: SnapshotEpoch, selectedAccountId: LocalAccountId?): Boolean {
        val generationUnchanged = epoch.generation == generationCounter.get()
        val noEditInFlight = inFlightEditCounter.get() == 0
        return generationUnchanged && noEditInFlight && epoch.accountId == selectedAccountId
    }

    /**
     * Applies [write] only when [epoch] is still fresh against the freshly re-read
     * [selectedAccountId]; otherwise the write is dropped and counted.
     *
     * @return true when the write was committed, false when it was discarded as stale.
     */
    suspend fun commitIfFresh(
        epoch: SnapshotEpoch,
        selectedAccountId: LocalAccountId?,
        write: suspend () -> Unit,
    ): Boolean {
        return if (isFresh(epoch, selectedAccountId)) {
            write()
            committedWriteCounter.incrementAndGet()
            true
        } else {
            discardedWriteCounter.incrementAndGet()
            false
        }
    }
}
