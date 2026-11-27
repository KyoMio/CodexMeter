package com.kmnexus.codexmeter.core.concurrency

import com.kmnexus.codexmeter.domain.model.LocalAccountId
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

        // No generation bump at all: the account mismatch alone must discard the write.
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
    fun `overlapping edits keep blocking commits until the last one finishes`() = runTest {
        val guard = VersionedStateGuard()
        guard.beginEdit()
        guard.beginEdit()
        guard.endEdit()
        val epochDuringOverlap = guard.captureReadToken(accountA)

        // One edit is still in flight: even an epoch captured right now must not commit.
        assertFalse(guard.isFresh(epochDuringOverlap, accountA))
        guard.endEdit()
        assertTrue(guard.isFresh(epochDuringOverlap, accountA))
    }
}
