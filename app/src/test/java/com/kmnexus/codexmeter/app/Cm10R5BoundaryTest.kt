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
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class Cm10R5BoundaryTest {
    @Test fun cancelAfterDelete() = exercise(true)
    @Test fun finishAfterDelete() = exercise(false)

    private fun exercise(cancel: Boolean) = runBlocking {
        withTimeout(15_000) {
            val env = BoundaryEnv().apply { seed() }
            try {
                val store = RoomCodexSessionImportPersistence(
                    env.db,
                    env.wrapped,
                    env.prefs,
                    env.guard,
                    notificationTargetBoundary = env.boundary,
                )
                val job = async(env.workerIo) {
                    runCatching {
                        store.save(
                            env.a.copy(localAccountId = "fresh").toDomain(),
                            boundaryEnvelope(9),
                            FixtureAccounts.snapshot(localAccountId = "fresh", snapshotId = "audit").toDomain(),
                            allowNewRow = false,
                            expectedLocalAccountId = null,
                        )
                    }
                }
                env.aboutToSave.await()
                env.deleteA()
                env.boundary.serialize {
                    env.resumeIO.complete(Unit)
                    env.written.await()
                    // writer yielded waiting for the actual mutex
                    withContext(env.workerIo) { Unit }
                    if (cancel) job.cancelAndJoin()
                }
                if (!cancel) {
                    check(job.await().exceptionOrNull()?.message?.startsWith("Import discarded:") == true)
                }
                env.verifyDeleted(cancel)
            } finally {
                env.close()
            }
        }
        Unit
    }

    private class BoundaryEnv {
        private val ctx: Context = RuntimeEnvironment.getApplication()
        private val dir = File(ctx.filesDir, UUID.randomUUID().toString())
        val db = Room.inMemoryDatabaseBuilder(ctx, CodexMeterDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val prefs = CurrentAccountPreferences.create(File(dir, "prefs.preferences_pb"))
        private val vaultDir = File(dir, "sessions")
        private val vault = FileSecureSessionStore(vaultDir)
        val boundary = NotificationTargetBoundary()
        val guard = VersionedStateGuard()
        private val dao = db.providerAccountDao()
        val a = FixtureAccounts.account(localAccountId = "A", displayName = "alpha")
        private val b = FixtureAccounts.account(localAccountId = "B", displayName = "beta")
        private val p = ProviderId("codex")
        val aboutToSave = CompletableDeferred<Unit>()
        val resumeIO = CompletableDeferred<Unit>()
        val written = CompletableDeferred<Unit>()
        val wrapped = object : SecureSessionStore by vault {
            override suspend fun save(envelope: ProviderSessionEnvelope) {
                aboutToSave.complete(Unit)
                resumeIO.await()
                vault.save(envelope)
                written.complete(Unit)
            }
        }
        private val workerExecutor = Executors.newSingleThreadExecutor()
        val workerIo = workerExecutor.asCoroutineDispatcher()

        suspend fun seed() {
            dao.upsert(a)
            dao.upsert(b)
            prefs.updateCurrentAccountSelection(CurrentAccountSelection(p, LocalAccountId("A")))
            vault.save(boundaryEnvelope(3))
        }

        suspend fun deleteA() {
            AccountDeletionRepository(
                db,
                vault,
                prefs,
                NoopDeletedAccountStateCleaner,
                stateGuard = guard,
                notificationTargetBoundary = boundary,
            ).deleteAccount(p, LocalAccountId("A"))
            check(dao.getById("A") == null && vault.load("codex", "A") == null)
        }

        suspend fun verifyDeleted(cancel: Boolean) {
            val stored = FileSecureSessionStore(vaultDir).load("codex", "A")
            val selected = prefs.currentAccountSelection()?.localAccountId?.value
            val marker = stored?.payloadCiphertext?.first()
            println(
                "cancel=$cancel selected=$selected rowA=${dao.getById("A") != null} " +
                    "persistedSyntheticMarker=$marker",
            )
            check(selected == "B" && dao.getById("A") == null && stored == null) {
                "Deleted account still has a staged credential after cancellation"
            }
        }

        fun close() {
            resumeIO.complete(Unit)
            workerExecutor.shutdownNow()
            db.close()
        }
    }
}

private fun boundaryEnvelope(v: Int) = ProviderSessionEnvelope(
    providerId = "codex",
    localAccountId = "A",
    providerAccountId = "acct-A",
    schemaVersion = 1,
    payloadCiphertext = byteArrayOf(v.toByte()),
    payloadNonce = byteArrayOf(1),
    createdAt = "fixture",
    updatedAt = "fixture",
)
