package com.kmnexus.codexmeter.app

import android.content.Context
import androidx.room.Room
import com.kmnexus.codexmeter.core.concurrency.VersionedStateGuard
import com.kmnexus.codexmeter.data.local.db.CodexMeterDatabase
import com.kmnexus.codexmeter.data.preferences.CurrentAccountPreferences
import com.kmnexus.codexmeter.data.preferences.CurrentAccountSelection
import com.kmnexus.codexmeter.data.repository.AccountDeletionRepository
import com.kmnexus.codexmeter.data.repository.RoomCodexSessionImportPersistence
import com.kmnexus.codexmeter.data.repository.toDomain
import com.kmnexus.codexmeter.data.secure.FileSecureSessionStore
import com.kmnexus.codexmeter.data.secure.ProviderSessionEnvelope
import com.kmnexus.codexmeter.data.secure.SecureSessionStore
import com.kmnexus.codexmeter.domain.account.NoopDeletedAccountStateCleaner
import com.kmnexus.codexmeter.domain.model.LocalAccountId
import com.kmnexus.codexmeter.domain.model.ProviderId
import com.kmnexus.codexmeter.notification.NotificationTargetBoundary
import com.kmnexus.codexmeter.providers.codex.auth.CodexSessionImporter
import java.io.File
import java.time.Clock
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class Cm10R4ImportDeleteTest {
    @Test fun freshUuid() = exercise(existing = false, delete = false)
    @Test fun matchedLive() = exercise(existing = true, delete = false)
    @Test fun deleteDuringMatchedImport() = exercise(existing = true, delete = true)

    private fun exercise(existing: Boolean, delete: Boolean) = runBlocking {
        withTimeout(20_000) {
            val env = ImportDeleteEnv(existing, delete).apply { seed() }
            try {
                val job = async(Dispatchers.IO) {
                    runCatching {
                        env.imp.save(
                            env.incoming.toDomain(),
                            importDeleteEnvelope(env.fresh, 9),
                            FixtureAccounts.snapshot(localAccountId = env.fresh, snapshotId = "new-import").toDomain(),
                            allowNewRow = true,
                            expectedLocalAccountId = null,
                        )
                    }
                }
                if (delete) env.deleteDuringSave()
                verifyOutcome(env, existing, delete, job.await())
            } finally {
                env.release.complete(Unit)
                env.db.close()
            }
        }
        Unit
    }

    private suspend fun verifyOutcome(
        env: ImportDeleteEnv,
        existing: Boolean,
        delete: Boolean,
        result: Result<CodexSessionImporter.CommittedImport>,
    ) {
        val selected = env.store.currentAccountSelection()?.localAccountId?.value
        val loaded = env.repo.loadCurrentState().account?.localAccountId?.value
        val envelope = env.vault.load("codex", "A")
        val restored = envelope?.payloadCiphertext?.contentEquals(byteArrayOf(7)) == true
        println(
            "existing=$existing delete=$delete result=" + (result.exceptionOrNull()?.message ?: "ok") +
                " selected=$selected home=$loaded live=" + env.dao.listAll().map { it.localAccountId } +
                " oldEnvelopeRestored=$restored",
        )
        if (delete) {
            check(result.exceptionOrNull()?.message?.startsWith("Import discarded:") == true)
            check(env.dao.getById("A") == null && env.db.quotaSnapshotDao().getLatestForAccount("codex", "A") == null)
            check(loaded == "B" && selected == "B" && envelope == null) {
                "Discard overwrote completed deletion: selected=$selected " +
                    "home=$loaded oldEnvelopePresent=${envelope != null}"
            }
        } else {
            val expected = if (existing) "A" else env.fresh
            check(result.getOrThrow().account.localAccountId.value == expected)
            check(loaded == expected && selected == expected)
            check(env.db.quotaSnapshotDao().getLatestForAccount("codex", expected) != null)
        }
    }

    private class ImportDeleteEnv(
        private val existing: Boolean,
        private val delete: Boolean,
    ) {
        private val ctx: Context = RuntimeEnvironment.getApplication()
        private val dir = File(ctx.filesDir, UUID.randomUUID().toString())
        val db = Room.inMemoryDatabaseBuilder(ctx, CodexMeterDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val store = CurrentAccountPreferences.create(File(dir, "prefs.preferences_pb"))
        val vault = FileSecureSessionStore(File(dir, "sessions"))
        val boundary = NotificationTargetBoundary()
        val guard = VersionedStateGuard()
        val dao = db.providerAccountDao()
        private val p = ProviderId("codex")
        private val a = FixtureAccounts.account(localAccountId = "A", displayName = "Alpha")
        private val b = FixtureAccounts.account(localAccountId = "B", displayName = "Beta")
        val fresh = "codex-" + UUID.randomUUID()
        val incoming = a.copy(localAccountId = fresh)
        val saved = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        private val paused = object : SecureSessionStore by vault {
            override suspend fun save(envelope: ProviderSessionEnvelope) {
                vault.save(envelope)
                if (delete && !saved.isCompleted) {
                    saved.complete(Unit)
                    release.await()
                }
            }
        }
        val imp = RoomCodexSessionImportPersistence(db, paused, store, guard, notificationTargetBoundary = boundary)
        val repo = CurrentQuotaStateRepository(
            store,
            dao,
            db.quotaSnapshotDao(),
            db.refreshAttemptDao(),
            clock = Clock.systemUTC(),
        )

        suspend fun seed() {
            val selection = if (existing) "A" else "B"
            dao.upsert(b)
            if (existing) {
                dao.upsert(a)
                vault.save(importDeleteEnvelope("A", 7))
            }
            store.updateCurrentAccountSelection(CurrentAccountSelection(p, LocalAccountId(selection)))
        }

        suspend fun deleteDuringSave() {
            saved.await()
            AccountDeletionRepository(
                db,
                vault,
                store,
                NoopDeletedAccountStateCleaner,
                stateGuard = guard,
                notificationTargetBoundary = boundary,
            ).deleteAccount(p, LocalAccountId("A"))
            check(store.currentAccountSelection()?.localAccountId?.value == "B")
            check(repo.loadCurrentState().account?.localAccountId?.value == "B")
            check(vault.load("codex", "A") == null && dao.getById("A") == null)
            println("delete returned: Home=B, rowA=false, envelopeA=false")
            release.complete(Unit)
        }
    }
}

private fun importDeleteEnvelope(id: String, value: Int) = ProviderSessionEnvelope(
    providerId = "codex",
    localAccountId = id,
    providerAccountId = "acct-A",
    schemaVersion = 1,
    payloadCiphertext = byteArrayOf(value.toByte()),
    payloadNonce = byteArrayOf(2),
    createdAt = "fixture",
    updatedAt = "fixture",
)
