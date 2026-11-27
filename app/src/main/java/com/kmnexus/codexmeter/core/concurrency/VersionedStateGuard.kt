package com.kmnexus.codexmeter.core.concurrency

import com.kmnexus.codexmeter.domain.model.LocalAccountId
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Version token tying an asynchronous write-back to the account and state generation it started
 * from. The token is a plain value captured cheaply before network or database I/O and re-validated
 * at commit time; it never holds a lock across suspension points.
 */
data class SnapshotEpoch(
    val accountId: LocalAccountId,
    val generation: Long,
)

/**
 * Compile-enforced defense against stale asynchronous write-backs (collab-rule A8: a late result
 * must never clobber newer state).
 *
 * The guard tracks `(generation, inFlightEdits)` atomically in one [AtomicReference]:
 * - [beginEdit] advances `generation` and increments `inFlightEdits`;
 * - [endEdit] checks `inFlightEdits > 0`, advances `generation` again, and decrements
 *   `inFlightEdits`. Advancing `generation` on both edges guarantees that an epoch captured inside
 *   an overlapping edit window never becomes fresh once the edit finishes;
 * - [commitIfFresh] reads the target account inside [commitMutex] and validates the epoch +
 *   write atomically.
 * - [commitSerialized] runs durable post-edit republishes under [commitMutex] so they cannot race
 *   with a guarded write-back.
 */
class VersionedStateGuard {

    private data class GuardState(
        val generation: Long = 0L,
        val inFlightEdits: Int = 0,
    )

    private val guardState = AtomicReference(GuardState())
    private val discardedWriteCounter = AtomicInteger(0)
    private val committedWriteCounter = AtomicInteger(0)
    private val commitMutex = Mutex()

    /** Total write-backs discarded because their epoch was stale. Diagnostics-safe counter only. */
    val discardedWrites: Int
        get() = discardedWriteCounter.get()

    /** Total write-backs committed through the guard. */
    val committedWrites: Int
        get() = committedWriteCounter.get()

    /** Number of local edits currently executing. Exposed for tests and diagnostics. */
    val inFlightEdits: Int
        get() = guardState.get().inFlightEdits

    /** Current generation. Exposed for tests and diagnostics. */
    val generation: Long
        get() = guardState.get().generation

    /** Captures the epoch a new asynchronous operation starts from. */
    fun captureReadToken(accountId: LocalAccountId): SnapshotEpoch =
        SnapshotEpoch(accountId = accountId, generation = guardState.get().generation)

    /**
     * Marks a local edit (rename, deletion, re-selection, import, preference change) as started:
     * atomically advances generation and increments the in-flight edit count.
     *
     * MUST be paired with [endEdit] in a finally block.
     */
    fun beginEdit() {
        while (true) {
            val current = guardState.get()
            val next = GuardState(
                generation = current.generation + 1L,
                inFlightEdits = current.inFlightEdits + 1,
            )
            if (guardState.compareAndSet(current, next)) return
        }
    }

    /**
     * Marks a local edit as finished: asserts [inFlightEdits] > 0 (never underflows), advances
     * generation so any epoch captured during the edit window stays stale, and decrements the
     * in-flight count.
     */
    fun endEdit() {
        while (true) {
            val current = guardState.get()
            check(current.inFlightEdits > 0) {
                "VersionedStateGuard.endEdit() called without a matching beginEdit()"
            }
            val next = GuardState(
                generation = current.generation + 1L,
                inFlightEdits = current.inFlightEdits - 1,
            )
            if (guardState.compareAndSet(current, next)) return
        }
    }

    /**
     * Returns true when [epoch] still describes the live state: same generation, no edit in flight,
     * and the freshly re-read [selectedAccountId] is still the epoch's account.
     */
    fun isFresh(epoch: SnapshotEpoch, selectedAccountId: LocalAccountId?): Boolean {
        val current = guardState.get()
        return current.generation == epoch.generation &&
            current.inFlightEdits == 0 &&
            epoch.accountId == selectedAccountId
    }

    /**
     * Reads the current target account inside [commitMutex], validates [epoch], and runs [write]
     * atomically if still fresh; otherwise discards and counts the write.
     */
    suspend fun commitIfFresh(
        epoch: SnapshotEpoch,
        selectedAccountIdProvider: suspend () -> LocalAccountId?,
        write: suspend () -> Unit,
    ): Boolean = commitMutex.withLock {
        val selectedAccountId = selectedAccountIdProvider()
        if (isFresh(epoch, selectedAccountId)) {
            write()
            committedWriteCounter.incrementAndGet()
            true
        } else {
            discardedWriteCounter.incrementAndGet()
            false
        }
    }

    /** Convenience overload when the target account id is already resolved for the test. */
    suspend fun commitIfFresh(
        epoch: SnapshotEpoch,
        selectedAccountId: LocalAccountId?,
        write: suspend () -> Unit,
    ): Boolean = commitIfFresh(
        epoch = epoch,
        selectedAccountIdProvider = { selectedAccountId },
        write = write,
    )

    /**
     * Serializes a post-mutation state republish under [commitMutex] so local edits cannot interleave
     * with guarded refresh write-backs.
     */
    suspend fun commitSerialized(write: suspend () -> Unit) {
        commitMutex.withLock {
            write()
            committedWriteCounter.incrementAndGet()
        }
    }
}
