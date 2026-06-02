package com.kmnexus.codexmeter.data.local.db

import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import com.kmnexus.codexmeter.data.local.entity.AlertStateEntity
import com.kmnexus.codexmeter.data.local.entity.ProviderAccountEntity
import com.kmnexus.codexmeter.data.local.entity.QuotaSnapshotEntity
import com.kmnexus.codexmeter.data.local.entity.RefreshAttemptEntity
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
// Robolectric SDK 36 currently requires Java 21, while this project is pinned to Java 17.
@Config(sdk = [35])
class CodexMeterDatabaseTest {
    @Test
    fun `account can be inserted and read`() = runTest {
        withDatabase { db ->
            db.providerAccountDao().upsert(
                providerAccount(
                    localAccountId = "local",
                    displayName = "Codex",
                ),
            )

            assertEquals("Codex", db.providerAccountDao().getById("local")!!.displayName)
        }
    }

    @Test
    fun `known provider account identity keeps original local account id when remote identity is re-saved`() = runTest {
        withDatabase { db ->
            db.providerAccountDao().upsert(
                providerAccount(
                    localAccountId = "local-1",
                    avatarColorKey = "local-1",
                    status = "active",
                    createdAt = 1_779_408_000_000L,
                    updatedAt = 1_779_408_000_000L,
                    lastSuccessfulRefreshAt = 1_779_408_600_000L,
                    displayName = "Old",
                    avatarInitial = "O",
                ),
            )

            db.providerAccountDao().upsert(
                providerAccount(
                    localAccountId = "local-2",
                    avatarColorKey = "local-2",
                    status = "needs_reauth",
                    createdAt = 1_779_500_000_000L,
                    updatedAt = 1_779_500_000_000L,
                    lastSuccessfulRefreshAt = null,
                    displayName = "New",
                ),
            )

            val reconciledAccount = db.providerAccountDao().getByProviderAccountId(
                providerId = "codex",
                providerAccountId = "acct",
            )!!

            assertEquals(
                1,
                countProviderAccountRows(
                    db = db.openHelper.readableDatabase,
                    providerId = "codex",
                    providerAccountId = "acct",
                ),
            )
            assertEquals("local-1", reconciledAccount.localAccountId)
            assertEquals("Old", reconciledAccount.displayName)
            assertEquals("O", reconciledAccount.avatarInitial)
            assertEquals("local-1", reconciledAccount.avatarColorKey)
            assertEquals("active", reconciledAccount.status)
            assertEquals(1_779_408_000_000L, reconciledAccount.createdAt)
            assertEquals(1_779_500_000_000L, reconciledAccount.updatedAt)
            assertEquals(1_779_408_600_000L, reconciledAccount.lastSuccessfulRefreshAt)
        }
    }

    @Test
    fun `known provider account identity conflict rejects duplicate placeholder local account`() = runTest {
        withDatabase { db ->
            db.providerAccountDao().upsert(
                providerAccount(
                    localAccountId = "local-1",
                    providerAccountId = "acct",
                    displayName = "Canonical",
                ),
            )
            db.providerAccountDao().upsert(
                providerAccount(
                    localAccountId = "local-2",
                    providerAccountId = null,
                    displayName = "Placeholder",
                ),
            )

            var error: IllegalStateException? = null
            try {
                db.providerAccountDao().upsert(
                    providerAccount(
                        localAccountId = "local-2",
                        providerAccountId = "acct",
                        displayName = "Incoming",
                    ),
                )
            } catch (thrown: IllegalStateException) {
                error = thrown
            }

            assertNotNull(error)
            assertEquals("Provider account local identity conflict", error!!.message)
            assertEquals(
                1,
                countProviderAccountRows(
                    db = db.openHelper.readableDatabase,
                    providerId = "codex",
                    providerAccountId = "acct",
                ),
            )
            assertEquals("Canonical", db.providerAccountDao().getById("local-1")!!.displayName)
            assertEquals("acct", db.providerAccountDao().getById("local-1")!!.providerAccountId)
            assertEquals("Placeholder", db.providerAccountDao().getById("local-2")!!.displayName)
            assertEquals(null, db.providerAccountDao().getById("local-2")!!.providerAccountId)
        }
    }

    @Test
    fun `accounts with null provider account id are not reconciled`() = runTest {
        withDatabase { db ->
            db.providerAccountDao().upsert(
                providerAccount(
                    localAccountId = "local-1",
                    providerAccountId = null,
                    displayName = "First",
                ),
            )

            db.providerAccountDao().upsert(
                providerAccount(
                    localAccountId = "local-2",
                    providerAccountId = null,
                    displayName = "Second",
                ),
            )

            assertEquals(2, countProviderAccountsWithoutRemoteId(db.openHelper.readableDatabase))
            assertEquals("First", db.providerAccountDao().getById("local-1")!!.displayName)
            assertEquals("Second", db.providerAccountDao().getById("local-2")!!.displayName)
        }
    }

    @Test
    fun `null provider account id update preserves existing remote identity for same local account`() = runTest {
        withDatabase { db ->
            db.providerAccountDao().upsert(
                providerAccount(
                    localAccountId = "local-1",
                    providerAccountId = "acct",
                    displayName = "Known",
                    updatedAt = 1_779_408_000_000L,
                ),
            )

            db.providerAccountDao().upsert(
                providerAccount(
                    localAccountId = "local-1",
                    providerAccountId = null,
                    displayName = "Renamed",
                    updatedAt = 1_779_411_600_000L,
                ),
            )

            val updatedAccount = db.providerAccountDao().getByProviderAccountId(
                providerId = "codex",
                providerAccountId = "acct",
            )

            assertEquals(
                1,
                countProviderAccountRows(
                    db = db.openHelper.readableDatabase,
                    providerId = "codex",
                    providerAccountId = "acct",
                ),
            )
            assertEquals("local-1", updatedAccount!!.localAccountId)
            assertEquals("acct", updatedAccount.providerAccountId)
            assertEquals("Renamed", updatedAccount.displayName)
            assertEquals(1_779_411_600_000L, updatedAccount.updatedAt)
            assertEquals(
                0,
                countNullProviderAccountRowsForLocalAccount(
                    db = db.openHelper.readableDatabase,
                    localAccountId = "local-1",
                ),
            )
        }
    }

    @Test
    fun `latest quota snapshot can be read by account`() = runTest {
        withDatabase { db ->
            db.providerAccountDao().upsert(providerAccount(localAccountId = "local"))
            db.quotaSnapshotDao().insert(
                quotaSnapshot(
                    snapshotId = "older",
                    localAccountId = "local",
                    fetchedAt = 1_779_408_000_000L,
                ),
            )
            db.quotaSnapshotDao().insert(
                quotaSnapshot(
                    snapshotId = "newer",
                    localAccountId = "local",
                    fetchedAt = 1_779_411_600_000L,
                ),
            )

            val latest = db.quotaSnapshotDao().getLatestForAccount(
                providerId = "codex",
                localAccountId = "local",
            )

            assertEquals("newer", latest!!.snapshotId)
        }
    }

    @Test
    fun `account list derives last successful refresh from latest quota snapshot`() = runTest {
        withDatabase { db ->
            db.providerAccountDao().upsert(
                providerAccount(
                    localAccountId = "local",
                    lastSuccessfulRefreshAt = null,
                ),
            )
            db.quotaSnapshotDao().insert(
                quotaSnapshot(
                    snapshotId = "older",
                    localAccountId = "local",
                    fetchedAt = 1_779_408_000_000L,
                ),
            )
            db.quotaSnapshotDao().insert(
                quotaSnapshot(
                    snapshotId = "newer",
                    localAccountId = "local",
                    fetchedAt = 1_779_411_600_000L,
                ),
            )

            val account = db.providerAccountDao().listByProvider("codex").single()

            assertEquals(1_779_411_600_000L, account.lastSuccessfulRefreshAt)
        }
    }

    @Test
    fun `latest refresh attempt can be read by account`() = runTest {
        withDatabase { db ->
            db.providerAccountDao().upsert(providerAccount(localAccountId = "local"))
            db.refreshAttemptDao().insert(
                refreshAttempt(
                    attemptId = "older",
                    localAccountId = "local",
                    startedAt = 1_779_408_000_000L,
                ),
            )
            db.refreshAttemptDao().insert(
                refreshAttempt(
                    attemptId = "newer",
                    localAccountId = "local",
                    startedAt = 1_779_411_600_000L,
                ),
            )

            val latest = db.refreshAttemptDao().getLatestForAccount(
                providerId = "codex",
                localAccountId = "local",
            )

            assertEquals("newer", latest!!.attemptId)
        }
    }

    @Test
    fun `alert state upsert replaces matching dedupe key`() = runTest {
        withDatabase { db ->
            db.providerAccountDao().upsert(providerAccount(localAccountId = "local"))
            db.alertStateDao().upsert(
                alertState(
                    alertStateId = "first",
                    localAccountId = "local",
                    lastNotifiedAt = "2026-05-22T01:00:00Z",
                ),
            )
            db.alertStateDao().upsert(
                alertState(
                    alertStateId = "replacement",
                    localAccountId = "local",
                    lastNotifiedAt = "2026-05-22T02:00:00Z",
                ),
            )

            val alertState = db.alertStateDao().getByDedupeKey(
                providerId = "codex",
                localAccountId = "local",
                windowId = "five_hour",
                resetAt = "2026-05-22T05:00:00Z",
                threshold = 90.0,
            )

            assertEquals("replacement", alertState!!.alertStateId)
            assertEquals("2026-05-22T02:00:00Z", alertState.lastNotifiedAt)
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

    private fun providerAccount(
        localAccountId: String,
        providerAccountId: String? = "acct",
        displayName: String = "Codex",
        avatarInitial: String = "C",
        avatarColorKey: String = localAccountId,
        status: String = "active",
        createdAt: Long = 1_779_408_000_000L,
        updatedAt: Long = createdAt,
        lastSuccessfulRefreshAt: Long? = null,
    ) = ProviderAccountEntity(
        localAccountId = localAccountId,
        providerId = "codex",
        providerAccountId = providerAccountId,
        displayName = displayName,
        avatarInitial = avatarInitial,
        avatarColorKey = avatarColorKey,
        status = status,
        createdAt = createdAt,
        updatedAt = updatedAt,
        lastSuccessfulRefreshAt = lastSuccessfulRefreshAt,
    )

    private fun quotaSnapshot(
        snapshotId: String,
        localAccountId: String,
        fetchedAt: Long,
    ) = QuotaSnapshotEntity(
        snapshotId = snapshotId,
        providerId = "codex",
        localAccountId = localAccountId,
        providerAccountId = "acct",
        fetchedAt = fetchedAt,
        source = "manualRefresh",
        planType = "team",
        windowsJson = "[]",
        creditsJson = null,
        responseDigest = "digest",
    )

    private fun refreshAttempt(
        attemptId: String,
        localAccountId: String,
        startedAt: Long,
    ) = RefreshAttemptEntity(
        attemptId = attemptId,
        providerId = "codex",
        localAccountId = localAccountId,
        trigger = "manualRefresh",
        startedAt = startedAt,
        finishedAt = startedAt + 1_000L,
        status = "success",
        errorCode = null,
        httpStatus = null,
        retryable = null,
        userActionRequired = null,
        diagnosticsDigest = "digest",
    )

    private fun alertState(
        alertStateId: String,
        localAccountId: String,
        lastNotifiedAt: String,
    ) = AlertStateEntity(
        alertStateId = alertStateId,
        providerId = "codex",
        localAccountId = localAccountId,
        windowId = "five_hour",
        threshold = 90.0,
        resetAt = "2026-05-22T05:00:00Z",
        lastNotifiedAt = lastNotifiedAt,
    )

    private fun countProviderAccountRows(
        db: SupportSQLiteDatabase,
        providerId: String,
        providerAccountId: String,
    ): Int = db.query(
        """
            SELECT COUNT(*)
            FROM provider_accounts
            WHERE provider_id = ? AND provider_account_id = ?
        """.trimIndent(),
        arrayOf(providerId, providerAccountId),
    ).use { cursor ->
        cursor.moveToFirst()
        cursor.getInt(0)
    }

    private fun countProviderAccountsWithoutRemoteId(db: SupportSQLiteDatabase): Int = db.query(
        """
            SELECT COUNT(*)
            FROM provider_accounts
            WHERE provider_account_id IS NULL
        """.trimIndent(),
    ).use { cursor ->
        cursor.moveToFirst()
        cursor.getInt(0)
    }

    private fun countNullProviderAccountRowsForLocalAccount(
        db: SupportSQLiteDatabase,
        localAccountId: String,
    ): Int = db.query(
        """
            SELECT COUNT(*)
            FROM provider_accounts
            WHERE local_account_id = ? AND provider_account_id IS NULL
        """.trimIndent(),
        arrayOf(localAccountId),
    ).use { cursor ->
        cursor.moveToFirst()
        cursor.getInt(0)
    }
}
