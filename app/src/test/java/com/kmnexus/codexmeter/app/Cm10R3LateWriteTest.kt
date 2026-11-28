package com.kmnexus.codexmeter.app

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.room.Room
import com.kmnexus.codexmeter.core.concurrency.VersionedStateGuard
import com.kmnexus.codexmeter.data.local.db.CodexMeterDatabase
import com.kmnexus.codexmeter.data.preferences.CurrentAccountPreferences
import com.kmnexus.codexmeter.data.preferences.CurrentAccountSelection
import com.kmnexus.codexmeter.data.preferences.NotificationPreferencesDataStore
import com.kmnexus.codexmeter.data.repository.AccountDeletionRepository
import com.kmnexus.codexmeter.data.repository.AccountLocalDataDeleter
import com.kmnexus.codexmeter.data.repository.RoomAccountLocalDataDeleter
import com.kmnexus.codexmeter.data.repository.RoomCodexSessionImportPersistence
import com.kmnexus.codexmeter.data.repository.toDomain
import com.kmnexus.codexmeter.data.secure.FakeSecureSessionStore
import com.kmnexus.codexmeter.data.secure.ProviderSessionEnvelope
import com.kmnexus.codexmeter.data.secure.SecureSessionStore
import com.kmnexus.codexmeter.domain.account.DeletedAccountStateCleaner
import com.kmnexus.codexmeter.domain.account.DeletedAccountStateCleanup
import com.kmnexus.codexmeter.domain.auth.SessionLoginUseCase
import com.kmnexus.codexmeter.domain.model.LocalAccountId
import com.kmnexus.codexmeter.domain.model.ProviderAccount
import com.kmnexus.codexmeter.domain.model.ProviderId
import com.kmnexus.codexmeter.domain.settings.NotificationAccountSelection
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
import com.kmnexus.codexmeter.providers.SessionImportRouter
import com.kmnexus.codexmeter.providers.SessionImporter
import com.kmnexus.codexmeter.providers.ValidatedSessionImport
import java.io.File
import java.time.Clock
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class Cm10R3LateWriteTest {
    enum class Case { IMPORT, DELETE, ROLLBACK, REL0GIN }

    @Test fun importVsPost() = race(Case.IMPORT)
    @Test fun deletePinnedVsPost() = race(Case.DELETE)
    @Test fun restoreVsPost() = race(Case.ROLLBACK)
    @Test fun lateReloginVsPost() = race(Case.REL0GIN)

    private fun race(case: Case) = runBlocking {
        withTimeout(25_000) {
            val h = LateWriteHarness(case).apply { initialize() }
            try {
                runLateWriteScenario(h)
            } finally {
                h.close()
            }
            Unit
        }
    }

    private suspend fun runLateWriteScenario(h: LateWriteHarness) = coroutineScope {
        val sessions = FakeSecureSessionStore()
        val atWriter = CompletableDeferred<Unit>()
        val rowGone = CompletableDeferred<Unit>()
        val deletion = createDeletionRepo(h, sessions, atWriter, rowGone)
        val relogin = if (h.case == Case.REL0GIN) {
            async(Dispatchers.IO) {
                // R3 改编：账号在验证期间被删除时重登结果按设计被丢弃（Result.failure），
                // getOrThrow 会抛——writer 的「完成」语义以协程结束为准而非结果成功。
                runCatching { h.login.reloginApiKey(LocalAccountId("A"), "fixture").getOrThrow() }
            }
        } else {
            null
        }
        if (relogin != null) h.atProvider.await()
        suspend fun deleteA() = deletion.deleteAccount(ProviderId(h.aProvider), LocalAccountId("A"))
        val earlyDelete = when (h.case) {
            Case.ROLLBACK -> async(Dispatchers.IO) {
                check(runCatching { deleteA() }.exceptionOrNull()?.message == "controlled storage failure")
            }
            Case.REL0GIN -> async(Dispatchers.IO) { deleteA() }
            else -> null
        }
        if (earlyDelete != null) {
            rowGone.await()
            check(h.target() == "B")
        }
        val post = async(h.io) { h.guard.commitSerialized { h.publisher.publish(h.repo.loadCurrentState()) } }
        if (h.case == Case.REL0GIN) {
            h.loaded.await()
            // Earlier publisher turn reached the mutex before the deletion releases it.
            withContext(h.io) { Unit }
            h.resumeDeletion.complete(Unit)
        }
        h.atSink.await()
        val handles = LateWriteHandles(sessions, atWriter, ::deleteA, earlyDelete, relogin)
        val writer = async(Dispatchers.IO) { runWriterTask(h, handles) }
        if (h.case == Case.IMPORT || h.case == Case.DELETE) atWriter.await()
        val completed = withTimeoutOrNull(400) { writer.await(); true } == true
        val before = h.target()
        check(h.title() == null)
        h.openSink.countDown()
        post.await()
        writer.await()
        earlyDelete?.await()
        assertOutcome(h, before, completed)
    }

    private suspend fun assertOutcome(h: LateWriteHarness, before: String?, completed: Boolean) {
        val (actual, posted) = h.snapshotAtPost.await()
        val finalTarget = h.target()
        val rowA = h.db.providerAccountDao().getById("A") != null
        println(
            "case=${h.case} writerCompletedBeforeRelease=$completed before=$before " +
                "atNotify=$actual newTitle=$posted finalTarget=$finalTarget rowA=$rowA",
        )
        check(posted?.contains(if (actual == "A") "Audit Alpha" else "Audit Beta") == true) {
            "wrong frame: target=$actual, title=$posted"
        }
        check(!completed) { "writer finished inside posting boundary" }
    }

    private suspend fun runWriterTask(h: LateWriteHarness, handles: LateWriteHandles) {
        when (h.case) {
            Case.IMPORT -> {
                val ss = object : SecureSessionStore by handles.sessions {
                    override suspend fun save(envelope: ProviderSessionEnvelope) {
                        handles.sessions.save(envelope)
                        handles.atWriter.complete(Unit)
                    }
                }
                val env = ProviderSessionEnvelope(
                    "codex",
                    "B",
                    "acct-B",
                    1,
                    byteArrayOf(1),
                    byteArrayOf(2),
                    "now",
                    "now",
                )
                RoomCodexSessionImportPersistence(
                    h.db,
                    ss,
                    h.store,
                    h.guard,
                    notificationTargetBoundary = h.boundary,
                ).save(
                    h.b.toDomain(),
                    env,
                    FixtureAccounts.snapshot(localAccountId = "B", snapshotId = "import-B").toDomain(),
                    allowNewRow = true,
                    expectedLocalAccountId = null,
                )
            }
            Case.DELETE -> handles.deleteA()
            Case.ROLLBACK -> {
                h.resumeDeletion.complete(Unit)
                handles.earlyDelete!!.await()
            }
            Case.REL0GIN -> {
                h.resumeRelogin.complete(Unit)
                handles.relogin!!.await()
            }
        }
    }

    private fun createDeletionRepo(
        h: LateWriteHarness,
        sessions: FakeSecureSessionStore,
        atWriter: CompletableDeferred<Unit>,
        rowGone: CompletableDeferred<Unit>,
    ): AccountDeletionRepository {
        val cleanup = object : DeletedAccountStateCleaner {
            override suspend fun clearDeletedAccountState(
                providerId: ProviderId,
                localAccountId: LocalAccountId,
            ): DeletedAccountStateCleanup {
                atWriter.complete(Unit)
                return DeletedAccountStateCleanup {
                    if (h.case == Case.ROLLBACK) {
                        rowGone.complete(Unit)
                        h.resumeDeletion.await()
                    }
                }
            }
        }
        val deleteRows = object : AccountLocalDataDeleter {
            override suspend fun deleteLocalData(providerId: ProviderId, localAccountId: LocalAccountId) {
                if (h.case == Case.ROLLBACK) error("controlled storage failure")
                RoomAccountLocalDataDeleter(h.db).deleteLocalData(providerId, localAccountId)
                if (h.case == Case.REL0GIN) {
                    rowGone.complete(Unit)
                    h.resumeDeletion.await()
                }
            }
        }
        return AccountDeletionRepository(
            h.db,
            sessions,
            h.store,
            cleanup,
            deleteRows,
            h.prefs,
            h.guard,
            h.boundary,
        )
    }

    private class LateWriteHandles(
        val sessions: FakeSecureSessionStore,
        val atWriter: CompletableDeferred<Unit>,
        val deleteA: suspend () -> Unit,
        val earlyDelete: Deferred<Unit>?,
        val relogin: Deferred<Result<ProviderAccount>>?,
    )

    private class LateWriteHarness(val case: Case) {
        private val ctx: Context = RuntimeEnvironment.getApplication()
        private val manager = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val db = Room.inMemoryDatabaseBuilder(ctx, CodexMeterDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        private val ioExecutor = Executors.newSingleThreadExecutor()
        val io = ioExecutor.asCoroutineDispatcher()
        val openSink = CountDownLatch(1)
        val resumeDeletion = CompletableDeferred<Unit>()
        val resumeRelogin = CompletableDeferred<Unit>()
        val guard = VersionedStateGuard()
        val boundary = NotificationTargetBoundary()
        private val clock = Clock.systemUTC()
        val store = CurrentAccountPreferences.create(File(ctx.filesDir, "${UUID.randomUUID()}.preferences_pb"))
        val prefs = NotificationPreferencesDataStore(store.dataStore)
        val aProvider = if (case == Case.REL0GIN) "deepseek" else "codex"
        val b = FixtureAccounts.account(localAccountId = "B", displayName = "Audit Beta")
        val resolver = NotificationAccountTargetResolver(prefs, db.providerAccountDao(), store)
        val repo = CurrentQuotaStateRepository(
            store,
            db.providerAccountDao(),
            db.quotaSnapshotDao(),
            db.refreshAttemptDao(),
            clock = clock,
            notificationTargetResolver = resolver,
            notificationPreferenceReader = prefs,
        )
        val atSink = CompletableDeferred<Unit>()
        val loaded = CompletableDeferred<Unit>()
        val snapshotAtPost = CompletableDeferred<Pair<String?, String?>>()
        val atProvider = CompletableDeferred<Unit>()
        lateinit var publisher: CurrentQuotaNotificationPublisher
        lateinit var login: SessionLoginUseCase

        suspend fun initialize() {
            val a = FixtureAccounts.account(localAccountId = "A", displayName = "Audit Alpha")
                .copy(providerId = aProvider)
            val pinned = case in setOf(Case.DELETE, Case.REL0GIN)
            for (acct in listOf(a, b)) {
                db.providerAccountDao().upsert(acct)
                val snap = FixtureAccounts.snapshot(
                    localAccountId = acct.localAccountId,
                    snapshotId = acct.localAccountId,
                )
                db.quotaSnapshotDao().insert(snap.copy(providerId = acct.providerId))
            }
            val default = if (pinned) b else a
            store.updateCurrentAccountSelection(
                CurrentAccountSelection(ProviderId(default.providerId), LocalAccountId(default.localAccountId)),
            )
            val pinId = LocalAccountId(if (pinned) "A" else "removed")
            prefs.updateNotificationPreferences(
                prefs.notificationPreferences().copy(
                    persistentNotificationAccount = NotificationAccountSelection(ProviderId(aProvider), pinId),
                ),
            )
            publisher = buildPublisher()
            login = SessionLoginUseCase(
                SessionImportRouter(mapOf(ProviderId(aProvider) to createFakeProvider())),
                db,
                db.providerAccountDao(),
                db.quotaSnapshotDao(),
                store,
                guard,
                clock,
                boundary,
            )
            check(title() == null)
        }

        private fun buildPublisher(): CurrentQuotaNotificationPublisher {
            val sink = AndroidNotificationSink(ctx)
            return CurrentQuotaNotificationPublisher(
                notificationSink = object : NotificationSink by sink {
                    override fun post(request: NotificationRequest) {
                        atSink.complete(Unit)
                        check(openSink.await(9, TimeUnit.SECONDS))
                        val selection = runBlocking { resolver.resolveTargetSelection()?.localAccountId?.value }
                        sink.post(request)
                        snapshotAtPost.complete(selection to title())
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
                    repo.loadStatusNotificationState(frame).also { loaded.complete(Unit) }
                },
                notificationTargetResolver = resolver,
                notificationTargetBoundary = boundary,
                clock = clock,
            )
        }

        private fun createFakeProvider(): SessionImporter = object : SessionImporter {
            override suspend fun importFromApiKey(
                apiKey: String,
                account: ProviderAccount,
                apiBaseUrl: String?,
            ): Result<ValidatedSessionImport> {
                atProvider.complete(Unit)
                resumeRelogin.await()
                return Result.success(
                    ValidatedSessionImport(
                        FixtureAccounts.snapshot(localAccountId = "A", snapshotId = "renewed")
                            .copy(providerId = aProvider)
                            .toDomain(),
                        ProviderSessionEnvelope(
                            aProvider,
                            "A",
                            null,
                            1,
                            byteArrayOf(1),
                            byteArrayOf(2),
                            "now",
                            "now",
                        ),
                    ),
                )
            }

            override suspend fun importFromCookie(
                cookieJson: String,
                account: ProviderAccount,
            ): Result<ValidatedSessionImport> = error("unused")

            override suspend fun importFromOAuthPkce(
                code: String,
                verifier: String,
                redirectUri: String,
                account: ProviderAccount,
            ): Result<ValidatedSessionImport> = error("unused")
        }

        fun title(): String? =
            manager.activeNotifications.singleOrNull { it.id == 1001 }
                ?.notification?.extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString()

        suspend fun target(): String? = resolver.resolveTargetSelection()?.localAccountId?.value

        fun close() {
            openSink.countDown()
            resumeDeletion.complete(Unit)
            resumeRelogin.complete(Unit)
            ioExecutor.shutdownNow()
            db.close()
        }
    }
}
