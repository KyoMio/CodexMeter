package com.kmnexus.codexmeter.data.repository

import androidx.room.Room
import com.kmnexus.codexmeter.data.local.db.CodexMeterDatabase
import com.kmnexus.codexmeter.data.local.entity.AlertStateEntity
import com.kmnexus.codexmeter.data.local.entity.ProviderAccountEntity
import com.kmnexus.codexmeter.data.local.entity.QuotaSnapshotEntity
import com.kmnexus.codexmeter.data.local.entity.RefreshAttemptEntity
import com.kmnexus.codexmeter.data.preferences.CurrentAccountReader
import com.kmnexus.codexmeter.data.preferences.CurrentAccountSelection
import com.kmnexus.codexmeter.domain.model.LocalAccountId
import com.kmnexus.codexmeter.domain.model.ProviderId
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
// Robolectric SDK 36 currently requires Java 21, while this project is pinned to Java 17.
@Config(sdk = [35])
class QuotaHistoryClearRepositoryTest {
    @Test
    fun `clearing current account history keeps accounts and sessions boundary intact`() = runTest {
        withRepository { db, currentAccountReader, repository ->
            seedAccountGraph(db, localAccountId = "target")
            seedAccountGraph(db, localAccountId = "other")
            currentAccountReader.selection = CurrentAccountSelection(ProviderId("codex"), LocalAccountId("target"))

            repository.clearCurrentAccountHistory()

            assertEquals(1, countRows(db, "provider_accounts", "target"))
            assertEquals(1, countRows(db, "provider_accounts", "other"))
            assertEquals(0, countRows(db, "quota_snapshots", "target"))
            assertEquals(0, countRows(db, "refresh_attempts", "target"))
            assertEquals(0, countRows(db, "alert_states", "target"))
            assertEquals(1, countRows(db, "quota_snapshots", "other"))
            assertEquals(1, countRows(db, "refresh_attempts", "other"))
            assertEquals(1, countRows(db, "alert_states", "other"))
        }
    }

    @Test
    fun `clearing all history keeps all account records`() = runTest {
        withRepository { db, _, repository ->
            seedAccountGraph(db, localAccountId = "target")
            seedAccountGraph(db, localAccountId = "other")

            repository.clearAllHistory()

            assertEquals(1, countRows(db, "provider_accounts", "target"))
            assertEquals(1, countRows(db, "provider_accounts", "other"))
            assertEquals(0, countAllRows(db, "quota_snapshots"))
            assertEquals(0, countAllRows(db, "refresh_attempts"))
            assertEquals(0, countAllRows(db, "alert_states"))
        }
    }

    private suspend fun withRepository(
        block: suspend (CodexMeterDatabase, InMemoryCurrentAccountReader, QuotaHistoryClearRepository) -> Unit,
    ) {
        val db = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            CodexMeterDatabase::class.java,
        ).build()
        val currentAccountReader = InMemoryCurrentAccountReader()
        val repository = QuotaHistoryClearRepository(
            database = db,
            currentAccountReader = currentAccountReader,
        )
        try {
            block(db, currentAccountReader, repository)
        } finally {
            db.close()
        }
    }

    private class InMemoryCurrentAccountReader : CurrentAccountReader {
        var selection: CurrentAccountSelection? = null

        override suspend fun currentAccountSelection(): CurrentAccountSelection? = selection
    }

    private suspend fun seedAccountGraph(db: CodexMeterDatabase, localAccountId: String) {
        db.providerAccountDao().upsert(providerAccount(localAccountId))
        db.quotaSnapshotDao().insert(quotaSnapshot(localAccountId))
        db.refreshAttemptDao().insert(refreshAttempt(localAccountId))
        db.alertStateDao().upsert(alertState(localAccountId))
    }

    private fun countRows(db: CodexMeterDatabase, table: String, localAccountId: String): Int =
        db.openHelper.readableDatabase.query(
            "SELECT COUNT(*) FROM $table WHERE local_account_id = ?",
            arrayOf(localAccountId),
        ).use { cursor ->
            cursor.moveToFirst()
            cursor.getInt(0)
        }

    private fun countAllRows(db: CodexMeterDatabase, table: String): Int =
        db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM $table").use { cursor ->
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
}
