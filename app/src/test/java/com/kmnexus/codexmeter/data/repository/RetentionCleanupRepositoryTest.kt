package com.kmnexus.codexmeter.data.repository

import androidx.room.Room
import com.kmnexus.codexmeter.data.local.db.CodexMeterDatabase
import com.kmnexus.codexmeter.data.local.entity.AlertStateEntity
import com.kmnexus.codexmeter.data.local.entity.ProviderAccountEntity
import com.kmnexus.codexmeter.data.local.entity.QuotaSnapshotEntity
import com.kmnexus.codexmeter.data.local.entity.RefreshAttemptEntity
import com.kmnexus.codexmeter.domain.settings.RetentionPreference
import java.time.Instant
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
class RetentionCleanupRepositoryTest {
    @Test
    fun `cleanup removes snapshots and attempts before cutoff without deleting account state`() = runTest {
        withDatabase { db ->
            seedAccountState(db)
            seedHistory(db, id = "old", timestamp = Instant.parse("2026-05-17T11:59:59Z"))
            seedHistory(db, id = "boundary", timestamp = Instant.parse("2026-05-17T12:00:00Z"))
            seedHistory(db, id = "fresh", timestamp = Instant.parse("2026-05-24T12:00:00Z"))
            seedHistory(db, id = "failed", timestamp = Instant.parse("2026-05-17T11:00:00Z"), attemptStatus = "failed")

            RetentionCleanupRepository(db).cleanup(
                preference = RetentionPreference.SevenDays,
                now = NOW,
            )

            assertEquals(0, countRows(db, "quota_snapshots", "snapshot-old"))
            assertEquals(0, countRows(db, "refresh_attempts", "attempt-old"))
            assertEquals(1, countRows(db, "quota_snapshots", "snapshot-boundary"))
            assertEquals(1, countRows(db, "refresh_attempts", "attempt-boundary"))
            assertEquals(1, countRows(db, "quota_snapshots", "snapshot-fresh"))
            assertEquals(1, countRows(db, "refresh_attempts", "attempt-fresh"))
            assertEquals(0, countRows(db, "quota_snapshots", "snapshot-failed"))
            assertEquals(1, countRows(db, "refresh_attempts", "attempt-failed"))
            assertEquals(1, countRows(db, "provider_accounts", "account"))
            assertEquals(1, countRows(db, "alert_states", "alert"))
        }
    }

    @Test
    fun `forever retention leaves persisted history intact`() = runTest {
        withDatabase { db ->
            seedAccountState(db)
            seedHistory(db, id = "old", timestamp = Instant.parse("2026-01-01T00:00:00Z"))

            RetentionCleanupRepository(db).cleanup(
                preference = RetentionPreference.Forever,
                now = NOW,
            )

            assertEquals(1, countRows(db, "quota_snapshots", "snapshot-old"))
            assertEquals(1, countRows(db, "refresh_attempts", "attempt-old"))
            assertEquals(1, countRows(db, "provider_accounts", "account"))
            assertEquals(1, countRows(db, "alert_states", "alert"))
        }
    }

    private suspend fun withDatabase(block: suspend (CodexMeterDatabase) -> Unit) {
        val db = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            CodexMeterDatabase::class.java,
        ).build()
        try {
            block(db)
        } finally {
            db.close()
        }
    }

    private suspend fun seedAccountState(db: CodexMeterDatabase) {
        db.providerAccountDao().upsert(
            ProviderAccountEntity(
                localAccountId = "account",
                providerId = "codex",
                providerAccountId = "provider-account",
                displayName = "Account",
                avatarInitial = "A",
                avatarColorKey = "account",
                status = "active",
                createdAt = NOW.toEpochMilli(),
                updatedAt = NOW.toEpochMilli(),
                lastSuccessfulRefreshAt = null,
            ),
        )
        db.alertStateDao().upsert(
            AlertStateEntity(
                alertStateId = "alert",
                providerId = "codex",
                localAccountId = "account",
                windowId = "five_hour",
                threshold = 90.0,
                resetAt = "2026-05-24T17:00:00Z",
                lastNotifiedAt = "2026-05-24T12:00:00Z",
            ),
        )
    }

    private suspend fun seedHistory(
        db: CodexMeterDatabase,
        id: String,
        timestamp: Instant,
        attemptStatus: String = "success",
    ) {
        db.quotaSnapshotDao().insert(
            QuotaSnapshotEntity(
                snapshotId = "snapshot-$id",
                providerId = "codex",
                localAccountId = "account",
                providerAccountId = "provider-account",
                fetchedAt = timestamp.toEpochMilli(),
                source = "manualRefresh",
                planType = "team",
                windowsJson = "[]",
                creditsJson = null,
                responseDigest = "digest",
            ),
        )
        db.refreshAttemptDao().insert(
            RefreshAttemptEntity(
                attemptId = "attempt-$id",
                providerId = "codex",
                localAccountId = "account",
                trigger = "manualRefresh",
                startedAt = timestamp.toEpochMilli(),
                finishedAt = timestamp.toEpochMilli(),
                status = attemptStatus,
                errorCode = null,
                httpStatus = null,
                retryable = null,
                userActionRequired = null,
                diagnosticsDigest = "digest",
            ),
        )
    }

    private fun countRows(db: CodexMeterDatabase, table: String, id: String): Int =
        db.openHelper.readableDatabase.query(
            "SELECT COUNT(*) FROM $table WHERE ${idColumn(table)} = ?",
            arrayOf(id),
        ).use { cursor ->
            cursor.moveToFirst()
            cursor.getInt(0)
        }

    private fun idColumn(table: String): String =
        when (table) {
            "quota_snapshots" -> "snapshot_id"
            "refresh_attempts" -> "attempt_id"
            "provider_accounts" -> "local_account_id"
            "alert_states" -> "alert_state_id"
            else -> error("Unknown table: $table")
        }

    private companion object {
        val NOW: Instant = Instant.parse("2026-05-24T12:00:00Z")
    }
}
