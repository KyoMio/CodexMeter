package com.kmnexus.codexmeter.app

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.room.Room
import com.kmnexus.codexmeter.core.concurrency.VersionedStateGuard
import com.kmnexus.codexmeter.data.local.db.CodexMeterDatabase
import com.kmnexus.codexmeter.data.preferences.CurrentAccountPreferences
import com.kmnexus.codexmeter.data.preferences.CurrentAccountSelection
import com.kmnexus.codexmeter.data.preferences.CurrentAccountStore
import com.kmnexus.codexmeter.data.preferences.NotificationPreferencesDataStore
import com.kmnexus.codexmeter.data.repository.RoomCodexSessionImportPersistence
import com.kmnexus.codexmeter.data.repository.toDomain
import com.kmnexus.codexmeter.data.secure.FileSecureSessionStore
import com.kmnexus.codexmeter.data.secure.ProviderSessionEnvelope
import com.kmnexus.codexmeter.domain.model.LocalAccountId
import com.kmnexus.codexmeter.domain.model.ProviderId
import com.kmnexus.codexmeter.notification.AlertDedupeKey
import com.kmnexus.codexmeter.notification.AlertThresholds
import com.kmnexus.codexmeter.notification.AlertThresholdsReader
import com.kmnexus.codexmeter.notification.AndroidNotificationSink
import com.kmnexus.codexmeter.notification.CurrentQuotaNotificationPublisher
import com.kmnexus.codexmeter.notification.NotificationAccountTargetResolver
import com.kmnexus.codexmeter.notification.NotificationAlertStateStore
import com.kmnexus.codexmeter.notification.NotificationRequest
import com.kmnexus.codexmeter.notification.NotificationRequestOptions
import com.kmnexus.codexmeter.notification.NotificationRequestOptionsReader
import com.kmnexus.codexmeter.notification.NotificationSink
import com.kmnexus.codexmeter.notification.NotificationTargetBoundary
import com.kmnexus.codexmeter.notification.QuotaAlertEvent
import com.kmnexus.codexmeter.notification.StatusNotificationStateLoader
import java.io.File
import java.time.Clock
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Executors
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
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
class Cm10R4GapTest {
    @Test fun freshTarget() = exercise(false)
    @Test fun matchedTarget() = exercise(true)

    private fun exercise(existing: Boolean) = runBlocking {
        withTimeout(20_000) {
            val env = GapEnv(existing).apply { seed() }
            try {
                val writer = object : CurrentAccountStore by env.store {
                    override suspend fun updateCurrentAccountSelection(selection: CurrentAccountSelection?) {
                        env.store.updateCurrentAccountSelection(selection)
                        env.staged.complete(Unit)
                        env.release.await()
                    }
                }
                val imp = RoomCodexSessionImportPersistence(
                    env.db,
                    env.vault,
                    writer,
                    env.guard,
                    notificationTargetBoundary = env.boundary,
                )
                val op = async(Dispatchers.IO) {
                    imp.save(
                        env.incoming.toDomain(),
                        gapEnvelope(env.fresh, 9),
                        FixtureAccounts.snapshot(localAccountId = env.fresh, snapshotId = "new").toDomain(),
                        allowNewRow = true,
                        expectedLocalAccountId = null,
                    )
                }
                env.staged.await()
                val expected = if (existing) "A" else env.fresh
                check(env.resolver.resolveTargetSelection()?.localAccountId?.value == expected)
                val posting = async(env.io) { env.pub.publish(env.repo.loadCurrentState()) }
                env.loaded.await()
                withContext(env.io) { Unit }
                env.release.complete(Unit)
                posting.await()
                op.await()
                val gap = env.observed.toList()
                env.pub.publish(env.repo.loadCurrentState())
                println("existing=$existing gapPosts=$gap afterCommit=${env.observed.lastOrNull()}")
                check(if (existing) gap.single()?.contains("Alpha") == true else gap.isEmpty())
                check(env.observed.lastOrNull()?.contains("Alpha") == true)
            } finally {
                env.close()
            }
        }
        Unit
    }

    private class GapEnv(private val existing: Boolean) {
        private val ctx: Context = RuntimeEnvironment.getApplication()
        private val dir = File(ctx.filesDir, UUID.randomUUID().toString())
        val db = Room.inMemoryDatabaseBuilder(ctx, CodexMeterDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val store = CurrentAccountPreferences.create(File(dir, "prefs.preferences_pb"))
        val vault = FileSecureSessionStore(File(dir, "sessions"))
        val boundary = NotificationTargetBoundary()
        val guard = VersionedStateGuard()
        private val dao = db.providerAccountDao()
        private val p = ProviderId("codex")
        private val a = FixtureAccounts.account(localAccountId = "A", displayName = "Alpha")
        private val b = FixtureAccounts.account(localAccountId = "B", displayName = "Beta")
        val fresh = "codex-" + UUID.randomUUID()
        val incoming = a.copy(localAccountId = fresh)
        private val ioExecutor = Executors.newSingleThreadExecutor()
        val io = ioExecutor.asCoroutineDispatcher()
        val staged = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val loaded = CompletableDeferred<Unit>()
        val observed = mutableListOf<String?>()
        private val options = NotificationPreferencesDataStore(store.dataStore)
        val resolver = NotificationAccountTargetResolver(options, dao, store)
        val repo = CurrentQuotaStateRepository(
            store,
            dao,
            db.quotaSnapshotDao(),
            db.refreshAttemptDao(),
            clock = Clock.systemUTC(),
            notificationTargetResolver = resolver,
            notificationPreferenceReader = options,
        )
        val pub = buildPublisher()

        suspend fun seed() {
            dao.upsert(b)
            if (existing) {
                dao.upsert(a)
                db.quotaSnapshotDao().insert(FixtureAccounts.snapshot(localAccountId = "A", snapshotId = "first"))
            }
            store.updateCurrentAccountSelection(CurrentAccountSelection(p, LocalAccountId("B")))
            options.updateNotificationPreferences(
                options.notificationPreferences().copy(persistentNotificationAccount = null),
            )
        }

        private fun buildPublisher(): CurrentQuotaNotificationPublisher {
            val manager = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            fun title() = manager.activeNotifications.singleOrNull { it.id == 1001 }
                ?.notification?.extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString()
            val sink = AndroidNotificationSink(ctx)
            return CurrentQuotaNotificationPublisher(
                notificationSink = object : NotificationSink by sink {
                    override fun post(request: NotificationRequest) {
                        sink.post(request)
                        observed.add(title())
                    }
                },
                alertStateStore = object : NotificationAlertStateStore {
                    override suspend fun hasNotified(key: AlertDedupeKey) = false
                    override suspend fun markNotified(event: QuotaAlertEvent, notifiedAt: Instant) = Unit
                },
                optionsReader = NotificationRequestOptionsReader {
                    NotificationRequestOptions(true, true, false, false)
                },
                alertThresholdsReader = AlertThresholdsReader { AlertThresholds() },
                statusNotificationStateLoader = StatusNotificationStateLoader { frame ->
                    repo.loadStatusNotificationState(frame).also {
                        println("gap frame=" + it.account?.localAccountId?.value)
                        loaded.complete(Unit)
                    }
                },
                notificationTargetResolver = resolver,
                notificationTargetBoundary = boundary,
                clock = Clock.systemUTC(),
            )
        }

        fun close() {
            release.complete(Unit)
            ioExecutor.shutdownNow()
            db.close()
        }
    }
}

private fun gapEnvelope(id: String, value: Int) = ProviderSessionEnvelope(
    providerId = "codex",
    localAccountId = id,
    providerAccountId = "acct-A",
    schemaVersion = 1,
    payloadCiphertext = byteArrayOf(value.toByte()),
    payloadNonce = byteArrayOf(2),
    createdAt = "fixture",
    updatedAt = "fixture",
)
