package com.kmnexus.codexmeter.data.repository

import androidx.room.Room
import com.kmnexus.codexmeter.data.local.db.CodexMeterDatabase
import com.kmnexus.codexmeter.data.local.entity.AlertStateEntity
import com.kmnexus.codexmeter.data.local.entity.ProviderAccountEntity
import com.kmnexus.codexmeter.data.local.entity.QuotaSnapshotEntity
import com.kmnexus.codexmeter.data.local.entity.RefreshAttemptEntity
import com.kmnexus.codexmeter.data.preferences.CurrentAccountSelection
import com.kmnexus.codexmeter.data.preferences.CurrentAccountStore
import com.kmnexus.codexmeter.data.secure.FakeSecureSessionStore
import com.kmnexus.codexmeter.data.secure.ProviderSessionEnvelope
import com.kmnexus.codexmeter.data.secure.SecureSessionStore
import com.kmnexus.codexmeter.domain.account.DeletedAccountStateCleanup
import com.kmnexus.codexmeter.domain.account.DeletedAccountStateCleaner
import com.kmnexus.codexmeter.domain.account.NoopDeletedAccountStateCleaner
import com.kmnexus.codexmeter.domain.model.LocalAccountId
import com.kmnexus.codexmeter.domain.model.ProviderId
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
// Robolectric SDK 36 currently requires Java 21, while this project is pinned to Java 17.
@Config(sdk = [35])
class AccountDeletionRepositoryTest {
    @Test
    fun `deleting one account removes its local data and preserves another account`() = runTest {
        withRepository { db, _, repository ->
            seedAccountGraph(db, localAccountId = "target")
            seedAccountGraph(db, localAccountId = "other")

            repository.deleteAccount(providerId = ProviderId("codex"), localAccountId = LocalAccountId("target"))

            assertAccountGraphDeleted(db, localAccountId = "target")
            assertAccountGraphPresent(db, localAccountId = "other")
        }
    }

    @Test
    fun `deleting account deletes its encrypted session only`() = runTest {
        withRepository { _, sessionStore, repository ->
            sessionStore.save(sessionEnvelope(localAccountId = "target"))
            sessionStore.save(sessionEnvelope(localAccountId = "other"))

            repository.deleteAccount(providerId = ProviderId("codex"), localAccountId = LocalAccountId("target"))

            assertNull(sessionStore.load(providerId = "codex", localAccountId = "target"))
            assertNotNull(sessionStore.load(providerId = "codex", localAccountId = "other"))
        }
    }

    @Test
    fun `deleting current account moves selection to next account`() = runTest {
        withRepository { db, _, repository ->
            seedAccountGraph(db, localAccountId = "target")
            seedAccountGraph(db, localAccountId = "other")
            val currentAccountStore = repository.currentAccountStore
            currentAccountStore.updateCurrentAccountSelection(selection(localAccountId = "target"))

            repository.deleteAccount(providerId = ProviderId("codex"), localAccountId = LocalAccountId("target"))

            assertEquals(selection(localAccountId = "other"), currentAccountStore.currentAccountSelection())
        }
    }

    @Test
    fun `deleting only current account clears selection`() = runTest {
        withRepository { db, _, repository ->
            seedAccountGraph(db, localAccountId = "target")
            val currentAccountStore = repository.currentAccountStore
            currentAccountStore.updateCurrentAccountSelection(selection(localAccountId = "target"))

            repository.deleteAccount(providerId = ProviderId("codex"), localAccountId = LocalAccountId("target"))

            assertNull(currentAccountStore.currentAccountSelection())
        }
    }

    @Test
    fun `secure session delete failure leaves account data and current selection intact`() = runTest {
        val widgetStateCleaner = RecordingDeletedAccountStateCleaner()
        withRepository(
            sessionStore = ThrowingSecureSessionStore(IllegalStateException("delete failed")),
            deletedAccountStateCleaner = widgetStateCleaner,
        ) { db, _, repository ->
            seedAccountGraph(db, localAccountId = "target")
            repository.currentAccountStore.updateCurrentAccountSelection(selection(localAccountId = "target"))

            val result = runCatching {
                repository.deleteAccount(providerId = ProviderId("codex"), localAccountId = LocalAccountId("target"))
            }

            assertEquals("delete failed", result.exceptionOrNull()?.message)
            assertAccountGraphPresent(db, localAccountId = "target")
            assertEquals(selection(localAccountId = "target"), repository.currentAccountStore.currentAccountSelection())
            assertEquals(listOf(selection(localAccountId = "target")), widgetStateCleaner.clearedAccounts)
            assertEquals(listOf(selection(localAccountId = "target")), widgetStateCleaner.restoredAccounts)
        }
    }

    @Test
    fun `current selection update failure leaves account data intact`() = runTest {
        val currentAccountStore = ThrowingCurrentAccountStore(
            initialSelection = selection(localAccountId = "target"),
            exception = IllegalStateException("selection failed"),
        )
        withRepository(currentAccountStore = currentAccountStore) { db, _, repository ->
            seedAccountGraph(db, localAccountId = "target")
            seedAccountGraph(db, localAccountId = "other")

            val result = runCatching {
                repository.deleteAccount(providerId = ProviderId("codex"), localAccountId = LocalAccountId("target"))
            }

            assertEquals("selection failed", result.exceptionOrNull()?.message)
            assertAccountGraphPresent(db, localAccountId = "target")
            assertAccountGraphPresent(db, localAccountId = "other")
        }
    }

    @Test
    fun `deleting account clears widget state for deleted account`() = runTest {
        val widgetStateCleaner = RecordingDeletedAccountStateCleaner()
        withRepository(deletedAccountStateCleaner = widgetStateCleaner) { db, _, repository ->
            seedAccountGraph(db, localAccountId = "target")

            repository.deleteAccount(providerId = ProviderId("codex"), localAccountId = LocalAccountId("target"))

            assertEquals(listOf(selection(localAccountId = "target")), widgetStateCleaner.clearedAccounts)
        }
    }

    @Test
    fun `widget state cleaner failure leaves account data and current selection intact`() = runTest {
        val widgetStateCleaner = ThrowingDeletedAccountStateCleaner(IllegalStateException("widget failed"))
        withRepository(deletedAccountStateCleaner = widgetStateCleaner) { db, _, repository ->
            seedAccountGraph(db, localAccountId = "target")
            repository.currentAccountStore.updateCurrentAccountSelection(selection(localAccountId = "target"))

            val result = runCatching {
                repository.deleteAccount(providerId = ProviderId("codex"), localAccountId = LocalAccountId("target"))
            }

            assertEquals("widget failed", result.exceptionOrNull()?.message)
            assertAccountGraphPresent(db, localAccountId = "target")
            assertEquals(selection(localAccountId = "target"), repository.currentAccountStore.currentAccountSelection())
        }
    }

    @Test
    fun `local data delete failure after session delete restores session current selection and widget state`() = runTest {
        val widgetStateCleaner = RecordingDeletedAccountStateCleaner()
        val sessionStore = FakeSecureSessionStore()
        sessionStore.save(sessionEnvelope(localAccountId = "target"))
        withRepository(
            sessionStore = sessionStore,
            deletedAccountStateCleaner = widgetStateCleaner,
            localDataDeleter = ThrowingAccountLocalDataDeleter(IllegalStateException("local delete failed")),
        ) { db, _, repository ->
            seedAccountGraph(db, localAccountId = "target")
            repository.currentAccountStore.updateCurrentAccountSelection(selection(localAccountId = "target"))

            val result = runCatching {
                repository.deleteAccount(providerId = ProviderId("codex"), localAccountId = LocalAccountId("target"))
            }

            val restoredSession = sessionStore.load(providerId = "codex", localAccountId = "target")
            assertEquals("local delete failed", result.exceptionOrNull()?.message)
            assertNotNull(restoredSession)
            assertEquals("provider-target", restoredSession?.providerAccountId)
            assertArrayEquals(byteArrayOf(1), restoredSession?.payloadCiphertext)
            assertArrayEquals(byteArrayOf(2), restoredSession?.payloadNonce)
            assertAccountGraphPresent(db, localAccountId = "target")
            assertEquals(selection(localAccountId = "target"), repository.currentAccountStore.currentAccountSelection())
            assertEquals(listOf(selection(localAccountId = "target")), widgetStateCleaner.clearedAccounts)
            assertEquals(listOf(selection(localAccountId = "target")), widgetStateCleaner.restoredAccounts)
        }
    }

    private suspend fun withRepository(
        sessionStore: SecureSessionStore = FakeSecureSessionStore(),
        currentAccountStore: CurrentAccountStore = InMemoryCurrentAccountStore(),
        deletedAccountStateCleaner: DeletedAccountStateCleaner = NoopDeletedAccountStateCleaner,
        localDataDeleter: AccountLocalDataDeleter? = null,
        block: suspend (CodexMeterDatabase, SecureSessionStore, RepositoryHarness) -> Unit,
    ) {
        val db = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            CodexMeterDatabase::class.java,
        ).build()
        val repository = AccountDeletionRepository(
            database = db,
            secureSessionStore = sessionStore,
            currentAccountStore = currentAccountStore,
            deletedAccountStateCleaner = deletedAccountStateCleaner,
            localDataDeleter = localDataDeleter ?: RoomAccountLocalDataDeleter(db),
        )

        try {
            block(db, sessionStore, RepositoryHarness(repository, currentAccountStore))
        } finally {
            db.close()
        }
    }

    private suspend fun seedAccountGraph(db: CodexMeterDatabase, localAccountId: String) {
        db.providerAccountDao().upsert(providerAccount(localAccountId))
        db.quotaSnapshotDao().insert(quotaSnapshot(localAccountId))
        db.refreshAttemptDao().insert(refreshAttempt(localAccountId))
        db.alertStateDao().upsert(alertState(localAccountId))
    }

    private fun assertAccountGraphDeleted(db: CodexMeterDatabase, localAccountId: String) {
        assertEquals(0, countRows(db, "provider_accounts", localAccountId))
        assertEquals(0, countRows(db, "quota_snapshots", localAccountId))
        assertEquals(0, countRows(db, "refresh_attempts", localAccountId))
        assertEquals(0, countRows(db, "alert_states", localAccountId))
    }

    private fun assertAccountGraphPresent(db: CodexMeterDatabase, localAccountId: String) {
        assertEquals(1, countRows(db, "provider_accounts", localAccountId))
        assertEquals(1, countRows(db, "quota_snapshots", localAccountId))
        assertEquals(1, countRows(db, "refresh_attempts", localAccountId))
        assertEquals(1, countRows(db, "alert_states", localAccountId))
    }

    private fun countRows(db: CodexMeterDatabase, table: String, localAccountId: String): Int =
        db.openHelper.readableDatabase.query(
            "SELECT COUNT(*) FROM $table WHERE local_account_id = ?",
            arrayOf(localAccountId),
        ).use { cursor ->
            cursor.moveToFirst()
            cursor.getInt(0)
        }

    private fun providerAccount(localAccountId: String) = ProviderAccountEntity(
        localAccountId = localAccountId,
        providerId = "codex",
        providerAccountId = "provider-$localAccountId",
        displayName = localAccountId,
        avatarInitial = localAccountId.first().uppercase(),
        avatarColorKey = localAccountId,
        status = "active",
        createdAt = 1_779_408_000_000L,
        updatedAt = 1_779_408_000_000L,
        lastSuccessfulRefreshAt = null,
    )

    private fun quotaSnapshot(localAccountId: String) = QuotaSnapshotEntity(
        snapshotId = "snapshot-$localAccountId",
        providerId = "codex",
        localAccountId = localAccountId,
        providerAccountId = "provider-$localAccountId",
        fetchedAt = 1_779_408_000_000L,
        source = "manualRefresh",
        planType = "team",
        windowsJson = "[]",
        creditsJson = null,
        responseDigest = "digest",
    )

    private fun refreshAttempt(localAccountId: String) = RefreshAttemptEntity(
        attemptId = "attempt-$localAccountId",
        providerId = "codex",
        localAccountId = localAccountId,
        trigger = "manualRefresh",
        startedAt = 1_779_408_000_000L,
        finishedAt = 1_779_408_001_000L,
        status = "success",
        errorCode = null,
        httpStatus = null,
        retryable = null,
        userActionRequired = null,
        diagnosticsDigest = "digest",
    )

    private fun alertState(localAccountId: String) = AlertStateEntity(
        alertStateId = "alert-$localAccountId",
        providerId = "codex",
        localAccountId = localAccountId,
        windowId = "five_hour",
        threshold = 90.0,
        resetAt = "2026-05-22T05:00:00Z",
        lastNotifiedAt = "2026-05-22T02:00:00Z",
    )

    private fun sessionEnvelope(localAccountId: String): ProviderSessionEnvelope =
        ProviderSessionEnvelope(
            providerId = "codex",
            localAccountId = localAccountId,
            providerAccountId = "provider-$localAccountId",
            schemaVersion = 1,
            payloadCiphertext = byteArrayOf(1),
            payloadNonce = byteArrayOf(2),
            createdAt = "2026-05-22T00:00:00Z",
            updatedAt = "2026-05-22T00:00:00Z",
        )

    private fun selection(localAccountId: String): CurrentAccountSelection =
        CurrentAccountSelection(
            providerId = ProviderId("codex"),
            localAccountId = LocalAccountId(localAccountId),
        )

    private class RepositoryHarness(
        private val repository: AccountDeletionRepository,
        val currentAccountStore: CurrentAccountStore,
    ) {
        suspend fun deleteAccount(providerId: ProviderId, localAccountId: LocalAccountId) {
            repository.deleteAccount(providerId = providerId, localAccountId = localAccountId)
        }
    }

    private class InMemoryCurrentAccountStore : CurrentAccountStore {
        private var selection: CurrentAccountSelection? = null

        override suspend fun currentAccountSelection(): CurrentAccountSelection? = selection

        override suspend fun updateCurrentAccountSelection(selection: CurrentAccountSelection?) {
            this.selection = selection
        }
    }

    private class ThrowingSecureSessionStore(
        private val exception: Throwable,
    ) : SecureSessionStore {
        override suspend fun save(envelope: ProviderSessionEnvelope) = Unit

        override suspend fun load(providerId: String, localAccountId: String): ProviderSessionEnvelope? = null

        override suspend fun delete(providerId: String, localAccountId: String) {
            throw exception
        }
    }

    private class ThrowingCurrentAccountStore(
        initialSelection: CurrentAccountSelection?,
        private val exception: Throwable,
    ) : CurrentAccountStore {
        private var selection = initialSelection

        override suspend fun currentAccountSelection(): CurrentAccountSelection? = selection

        override suspend fun updateCurrentAccountSelection(selection: CurrentAccountSelection?) {
            throw exception
        }
    }

    private class RecordingDeletedAccountStateCleaner : DeletedAccountStateCleaner {
        val clearedAccounts = mutableListOf<CurrentAccountSelection>()
        val restoredAccounts = mutableListOf<CurrentAccountSelection>()

        override suspend fun clearDeletedAccountState(
            providerId: ProviderId,
            localAccountId: LocalAccountId,
        ): DeletedAccountStateCleanup {
            val selection = CurrentAccountSelection(
                providerId = providerId,
                localAccountId = localAccountId,
            )
            clearedAccounts += selection
            return DeletedAccountStateCleanup {
                restoredAccounts += selection
            }
        }
    }

    private class ThrowingDeletedAccountStateCleaner(
        private val exception: Throwable,
    ) : DeletedAccountStateCleaner {
        override suspend fun clearDeletedAccountState(
            providerId: ProviderId,
            localAccountId: LocalAccountId,
        ): DeletedAccountStateCleanup {
            throw exception
        }
    }

    private class ThrowingAccountLocalDataDeleter(
        private val exception: Throwable,
    ) : AccountLocalDataDeleter {
        override suspend fun deleteLocalData(
            providerId: ProviderId,
            localAccountId: LocalAccountId,
        ) {
            throw exception
        }
    }
}
