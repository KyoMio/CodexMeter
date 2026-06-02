package com.kmnexus.codexmeter.data.repository

import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.room.Room
import com.kmnexus.codexmeter.data.local.db.CodexMeterDatabase
import com.kmnexus.codexmeter.data.local.entity.ProviderAccountEntity
import com.kmnexus.codexmeter.data.preferences.CurrentAccountPreferences
import com.kmnexus.codexmeter.data.preferences.CurrentAccountSelection
import com.kmnexus.codexmeter.data.preferences.CurrentAccountStore
import com.kmnexus.codexmeter.data.secure.FakeSecureSessionStore
import com.kmnexus.codexmeter.data.secure.ProviderSessionEnvelope
import com.kmnexus.codexmeter.data.secure.SecureSessionStore
import com.kmnexus.codexmeter.domain.model.AccountStatus
import com.kmnexus.codexmeter.domain.model.LocalAccountId
import com.kmnexus.codexmeter.domain.model.ProviderAccount
import com.kmnexus.codexmeter.domain.model.ProviderAccountId
import com.kmnexus.codexmeter.domain.model.ProviderId
import com.kmnexus.codexmeter.domain.model.QuotaWindowId
import com.kmnexus.codexmeter.domain.model.SnapshotId
import com.kmnexus.codexmeter.domain.quota.QuotaSnapshot
import com.kmnexus.codexmeter.domain.quota.QuotaSnapshotSource
import com.kmnexus.codexmeter.domain.quota.QuotaWindow
import com.kmnexus.codexmeter.domain.quota.QuotaWindowAvailability
import java.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
// Robolectric SDK 36 currently requires Java 21, while this project is pinned to Java 17.
@Config(sdk = [35])
class RoomCodexSessionImportPersistenceTest {
    @Test
    fun `successful import persists current account selection`() = runTest {
        withPersistence { database, sessionStore, preferences, persistence ->
            val account = account(localAccountId = "local-1")
            val envelope = envelope(localAccountId = "local-1")
            val snapshot = snapshot(localAccountId = "local-1")

            val committed = persistence.save(
                account = account,
                sessionEnvelope = envelope,
                snapshot = snapshot,
            )

            assertEquals(account.localAccountId, committed.account.localAccountId)
            assertEquals(account, database.providerAccountDao().getById("local-1")!!.toDomainAccount())
            assertNotNull(sessionStore.load(providerId = "codex", localAccountId = "local-1"))
            assertNotNull(
                database.quotaSnapshotDao().getLatestForAccount(
                    providerId = "codex",
                    localAccountId = "local-1",
                ),
            )
            assertEquals(
                CurrentAccountSelection(
                    providerId = ProviderId("codex"),
                    localAccountId = LocalAccountId("local-1"),
                ),
                preferences.currentAccountSelection(),
            )
        }
    }

    @Test
    fun `reimporting existing provider account preserves user alias`() = runTest {
        withPersistence { database, _, _, persistence ->
            database.providerAccountDao().upsert(
                account(localAccountId = "local-1").renamedTo(
                    displayName = "Work",
                    updatedAt = Instant.parse("2026-05-24T08:00:00Z"),
                ).toEntity(),
            )

            val committed = persistence.save(
                account = account(localAccountId = "local-new"),
                sessionEnvelope = envelope(localAccountId = "local-new"),
                snapshot = snapshot(localAccountId = "local-new"),
            )

            assertEquals(LocalAccountId("local-1"), committed.account.localAccountId)
            assertEquals("Work", committed.account.displayName)
            assertEquals("W", committed.account.avatarInitial)
            assertEquals("Work", database.providerAccountDao().getById("local-1")!!.displayName)
            assertNull(database.providerAccountDao().getById("local-new"))
        }
    }

    @Test
    fun `reimporting existing provider account restores active status while preserving alias`() = runTest {
        withPersistence { database, _, _, persistence ->
            database.providerAccountDao().upsert(
                account(localAccountId = "local-1").renamedTo(
                    displayName = "Work",
                    updatedAt = Instant.parse("2026-05-24T08:00:00Z"),
                ).copy(status = AccountStatus.NeedsReauth).toEntity(),
            )

            val committed = persistence.save(
                account = account(localAccountId = "local-new"),
                sessionEnvelope = envelope(localAccountId = "local-new"),
                snapshot = snapshot(localAccountId = "local-new"),
            )

            val persisted = database.providerAccountDao().getById("local-1")!!.toDomainAccount()
            assertEquals("Work", committed.account.displayName)
            assertEquals(AccountStatus.Active, committed.account.status)
            assertEquals(AccountStatus.Active, persisted.status)
            assertNull(database.providerAccountDao().getById("local-new"))
        }
    }

    @Test
    fun `session save failure leaves no account or snapshot committed`() = runTest {
        withPersistence(sessionStore = ThrowingSecureSessionStore()) { database, _, preferences, persistence ->
            try {
                persistence.save(
                    account = account(localAccountId = "local-1"),
                    sessionEnvelope = envelope(localAccountId = "local-1"),
                    snapshot = snapshot(localAccountId = "local-1"),
                )
                fail("Expected session save failure")
            } catch (_: IllegalStateException) {
                assertNull(database.providerAccountDao().getById("local-1"))
                assertNull(
                    database.quotaSnapshotDao().getLatestForAccount(
                        providerId = "codex",
                        localAccountId = "local-1",
                    ),
                )
                assertNull(preferences.currentAccountSelection())
            }
        }
    }

    @Test
    fun `current account save failure leaves no account or snapshot committed`() = runTest {
        val currentAccountStore = ThrowingCurrentAccountStore()
        withPersistence(currentAccountStore = currentAccountStore) { database, sessionStore, _, persistence ->
            try {
                persistence.save(
                    account = account(localAccountId = "local-1"),
                    sessionEnvelope = envelope(localAccountId = "local-1"),
                    snapshot = snapshot(localAccountId = "local-1"),
                )
                fail("Expected current account save failure")
            } catch (_: IllegalStateException) {
                assertNull(database.providerAccountDao().getById("local-1"))
                assertNull(
                    database.quotaSnapshotDao().getLatestForAccount(
                        providerId = "codex",
                        localAccountId = "local-1",
                    ),
                )
                assertEquals(null, currentAccountStore.selection)
                assertNull(sessionStore.load(providerId = "codex", localAccountId = "local-1"))
            }
        }
    }

    @Test
    fun `room commit failure removes saved session and leaves no account snapshot or selection`() = runTest {
        withPersistence(
            beforeRoomCommit = { throw IllegalStateException("room unavailable") },
        ) { database, sessionStore, preferences, persistence ->
            try {
                persistence.save(
                    account = account(localAccountId = "local-1"),
                    sessionEnvelope = envelope(localAccountId = "local-1"),
                    snapshot = snapshot(localAccountId = "local-1"),
                )
                fail("Expected room commit failure")
            } catch (_: IllegalStateException) {
                assertNull(sessionStore.load(providerId = "codex", localAccountId = "local-1"))
                assertNull(database.providerAccountDao().getById("local-1"))
                assertNull(
                    database.quotaSnapshotDao().getLatestForAccount(
                        providerId = "codex",
                        localAccountId = "local-1",
                    ),
                )
                assertNull(preferences.currentAccountSelection())
            }
        }
    }

    @Test
    fun `room commit failure restores previous session for existing account`() = runTest {
        val sessionStore = FakeSecureSessionStore()
        val previousEnvelope = envelope(localAccountId = "local-1").copy(
            payloadCiphertext = byteArrayOf(9, 9, 9),
            payloadNonce = byteArrayOf(8, 8, 8),
        )
        sessionStore.save(previousEnvelope)

        withPersistence(
            sessionStore = sessionStore,
            beforeRoomCommit = { throw IllegalStateException("room unavailable") },
        ) { _, savedSessionStore, _, persistence ->
            try {
                persistence.save(
                    account = account(localAccountId = "local-1"),
                    sessionEnvelope = envelope(localAccountId = "local-1"),
                    snapshot = snapshot(localAccountId = "local-1"),
                )
                fail("Expected room commit failure")
            } catch (_: IllegalStateException) {
                val restored = savedSessionStore.load(providerId = "codex", localAccountId = "local-1")
                assertNotNull(restored)
                assertEquals(previousEnvelope.providerId, restored!!.providerId)
                assertEquals(previousEnvelope.localAccountId, restored.localAccountId)
                assertEquals(previousEnvelope.providerAccountId, restored.providerAccountId)
                assertArrayEquals(previousEnvelope.payloadCiphertext, restored.payloadCiphertext)
                assertArrayEquals(previousEnvelope.payloadNonce, restored.payloadNonce)
            }
        }
    }

    private suspend fun withPersistence(
        sessionStore: SecureSessionStore = FakeSecureSessionStore(),
        currentAccountStore: CurrentAccountStore? = null,
        beforeRoomCommit: suspend () -> Unit = {},
        block: suspend (
            CodexMeterDatabase,
            SecureSessionStore,
            CurrentAccountPreferences,
            RoomCodexSessionImportPersistence,
        ) -> Unit,
    ) {
        val database = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            CodexMeterDatabase::class.java,
        ).build()
        val preferences = CurrentAccountPreferences.create(
            RuntimeEnvironment.getApplication()
                .preferencesDataStoreFile("room-import-persistence-test.preferences_pb"),
        )
        val persistence = RoomCodexSessionImportPersistence(
            database = database,
            sessionStore = sessionStore,
            currentAccountStore = currentAccountStore ?: preferences,
            beforeRoomCommit = beforeRoomCommit,
        )
        try {
            block(database, sessionStore, preferences, persistence)
        } finally {
            database.close()
        }
    }

    private fun account(localAccountId: String): ProviderAccount =
        ProviderAccount.createNew(
            localAccountId = LocalAccountId(localAccountId),
            providerId = ProviderId("codex"),
            providerAccountId = ProviderAccountId("acct-1"),
            displayName = "Codex",
            now = Instant.parse("2026-05-23T12:00:00Z"),
        )

    private fun envelope(localAccountId: String): ProviderSessionEnvelope =
        ProviderSessionEnvelope(
            providerId = "codex",
            localAccountId = localAccountId,
            providerAccountId = "acct-1",
            schemaVersion = 1,
            payloadCiphertext = byteArrayOf(1, 2, 3),
            payloadNonce = byteArrayOf(4, 5, 6),
            createdAt = "2026-05-23T12:00:00Z",
            updatedAt = "2026-05-23T12:00:00Z",
        )

    private fun snapshot(localAccountId: String): QuotaSnapshot =
        QuotaSnapshot(
            snapshotId = SnapshotId("codex:$localAccountId:1779537600000:DeviceCodeLogin"),
            providerId = ProviderId("codex"),
            localAccountId = LocalAccountId(localAccountId),
            providerAccountId = ProviderAccountId("acct-1"),
            fetchedAt = Instant.parse("2026-05-23T12:00:00Z"),
            source = QuotaSnapshotSource.DeviceCodeLogin,
            planType = "plus",
            windows = listOf(
                QuotaWindow(
                    windowId = QuotaWindowId("five_hour"),
                    titleKey = "quota_window_five_hour",
                    usedPercent = 62,
                    resetAt = Instant.parse("2026-05-23T17:00:00Z"),
                    limitWindowSeconds = 18_000,
                    isPrimaryCandidate = true,
                    availability = QuotaWindowAvailability.Available,
                ),
            ),
            credits = null,
            responseDigest = "safe-digest",
        )

    private fun ProviderAccountEntity.toDomainAccount(): ProviderAccount =
        ProviderAccount(
            localAccountId = LocalAccountId(localAccountId),
            providerId = ProviderId(providerId),
            providerAccountId = providerAccountId?.let(::ProviderAccountId),
            displayName = displayName,
            avatarInitial = avatarInitial,
            avatarColorKey = avatarColorKey,
            status = com.kmnexus.codexmeter.domain.model.AccountStatus.Active,
            createdAt = Instant.ofEpochMilli(createdAt),
            updatedAt = Instant.ofEpochMilli(updatedAt),
            lastSuccessfulRefreshAt = lastSuccessfulRefreshAt?.let(Instant::ofEpochMilli),
        )

    private class ThrowingSecureSessionStore : SecureSessionStore {
        override suspend fun save(envelope: ProviderSessionEnvelope) {
            throw IllegalStateException("session store unavailable")
        }

        override suspend fun load(providerId: String, localAccountId: String): ProviderSessionEnvelope? = null

        override suspend fun delete(providerId: String, localAccountId: String) = Unit
    }

    private class ThrowingCurrentAccountStore : CurrentAccountStore {
        var selection: CurrentAccountSelection? = null

        override suspend fun currentAccountSelection(): CurrentAccountSelection? = selection

        override suspend fun updateCurrentAccountSelection(selection: CurrentAccountSelection?) {
            throw IllegalStateException("current account store unavailable")
        }
    }
}
