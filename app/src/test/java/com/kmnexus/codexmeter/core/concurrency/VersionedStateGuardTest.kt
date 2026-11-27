package com.kmnexus.codexmeter.core.concurrency

import com.kmnexus.codexmeter.domain.model.LocalAccountId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VersionedStateGuardTest {

    private val accountA = LocalAccountId("account-a")
    private val accountB = LocalAccountId("account-b")

    @Test
    fun `fresh epoch commits the write`() = runTest {
        val guard = VersionedStateGuard()
        val epoch = guard.captureReadToken(accountA)
        var writes = 0

        val committed = guard.commitIfFresh(epoch = epoch, selectedAccountId = accountA) { writes += 1 }

        assertTrue(committed)
        assertEquals(1, writes)
        assertEquals(1, guard.committedWrites)
        assertEquals(0, guard.discardedWrites)
    }

    @Test
    fun `epoch captured before an edit is discarded`() = runTest {
        val guard = VersionedStateGuard()
        val epoch = guard.captureReadToken(accountA)
        guard.beginEdit()
        guard.endEdit()
        var writes = 0

        val committed = guard.commitIfFresh(epoch = epoch, selectedAccountId = accountA) { writes += 1 }

        assertFalse(committed)
        assertEquals(0, writes)
        assertEquals(1, guard.discardedWrites)
    }

    @Test
    fun `commit during an in-flight edit is discarded even if the edit started after capture`() = runTest {
        val guard = VersionedStateGuard()
        val epoch = guard.captureReadToken(accountA)
        guard.beginEdit()
        var writes = 0

        val committed = guard.commitIfFresh(epoch = epoch, selectedAccountId = accountA) { writes += 1 }
        guard.endEdit()

        assertFalse(committed)
        assertEquals(0, writes)
    }

    @Test
    fun `account switch discards the previous account epoch`() = runTest {
        val guard = VersionedStateGuard()
        val epoch = guard.captureReadToken(accountA)
        var writes = 0

        val committed = guard.commitIfFresh(epoch = epoch, selectedAccountId = accountB) { writes += 1 }

        assertFalse(committed)
        assertEquals(0, writes)
        assertEquals(1, guard.discardedWrites)
    }

    @Test
    fun `no account selected discards every write-back`() = runTest {
        val guard = VersionedStateGuard()
        val epoch = guard.captureReadToken(accountA)
        var writes = 0

        val committed = guard.commitIfFresh(epoch = epoch, selectedAccountId = null) { writes += 1 }

        assertFalse(committed)
        assertEquals(0, writes)
    }

    @Test
    fun `epoch captured after the edit still commits`() = runTest {
        val guard = VersionedStateGuard()
        guard.beginEdit()
        guard.endEdit()
        val epoch = guard.captureReadToken(accountA)
        var writes = 0

        val committed = guard.commitIfFresh(epoch = epoch, selectedAccountId = accountA) { writes += 1 }

        assertTrue(committed)
        assertEquals(1, writes)
    }

    @Test
    fun `overlapping edits keep epoch captured inside edit window stale even after endEdit`() = runTest {
        val guard = VersionedStateGuard()
        guard.beginEdit()
        guard.beginEdit()
        guard.endEdit()
        val epochDuringOverlap = guard.captureReadToken(accountA)

        // One edit is still in flight AND endEdit() advances generation, so the epoch captured
        // mid-edit stays permanently stale after the final edit finishes.
        assertFalse(guard.isFresh(epochDuringOverlap, accountA))
        guard.endEdit()
        assertFalse(guard.isFresh(epochDuringOverlap, accountA))

        val epochAfterAllEdits = guard.captureReadToken(accountA)
        assertTrue(guard.isFresh(epochAfterAllEdits, accountA))
    }

    @Test
    fun `unmatched endEdit throws instead of underflowing inFlightEdits`() {
        val guard = VersionedStateGuard()

        val error = runCatching { guard.endEdit() }.exceptionOrNull()

        assertTrue(error is IllegalStateException)
        assertEquals(0, guard.inFlightEdits)
        assertTrue(guard.isFresh(guard.captureReadToken(accountA), accountA))
    }

    @Test
    fun `commitIfFresh reads target account inside commitMutex after serialized switch`() = runTest {
        val guard = VersionedStateGuard()
        var currentSelected = accountA
        val epochA = guard.captureReadToken(accountA)
        val firstInsideMutex = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()

        val first = async {
            guard.commitSerialized {
                firstInsideMutex.complete(Unit)
                releaseFirst.await()
                currentSelected = accountB
            }
        }
        firstInsideMutex.await()

        val second = async {
            guard.commitIfFresh(epoch = epochA, selectedAccountIdProvider = { currentSelected }) {}
        }
        releaseFirst.complete(Unit)
        first.await()
        val secondCommitted = second.await()

        assertFalse(secondCommitted)
        assertEquals(1, guard.discardedWrites)
    }
}
