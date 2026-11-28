package com.kmnexus.codexmeter.app

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.room.Room
import com.kmnexus.codexmeter.core.concurrency.VersionedStateGuard
import com.kmnexus.codexmeter.core.network.ProviderHttpClient
import com.kmnexus.codexmeter.data.local.db.CodexMeterDatabase
import com.kmnexus.codexmeter.data.preferences.CurrentAccountPreferences
import com.kmnexus.codexmeter.data.preferences.CurrentAccountSelection
import com.kmnexus.codexmeter.data.preferences.NotificationPreferencesDataStore
import com.kmnexus.codexmeter.data.repository.AccountDeletionRepository
import com.kmnexus.codexmeter.data.repository.AccountLocalDataDeleter
import com.kmnexus.codexmeter.data.repository.RoomAccountLocalDataDeleter
import com.kmnexus.codexmeter.data.repository.RoomCodexSessionImportPersistence
import com.kmnexus.codexmeter.data.repository.toDomain
import com.kmnexus.codexmeter.data.secure.AesGcmPayloadCipher
import com.kmnexus.codexmeter.data.secure.FileSecureSessionStore
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
import com.kmnexus.codexmeter.providers.deepseek.auth.DeepSeekSessionImporter
import com.kmnexus.codexmeter.providers.deepseek.network.DeepSeekBalanceClient
import java.io.File
import java.time.Clock
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.crypto.spec.SecretKeySpec
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
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class Cm10R4EnvelopeTest {
    enum class Case { IMPORT, DELETE, ROLLBACK, REL0GIN }

    @Test fun importVsPost() = race(Case.IMPORT)
    @Test fun deletePinnedVsPost() = race(Case.DELETE)
    @Test fun restoreVsPost() = race(Case.ROLLBACK)
    @Test fun lateReloginVsPost() = race(Case.REL0GIN)

    /** Live positive: with no deletion, the wired store must hold the re-imported credential. */
    @Test fun liveStorePositive() = race(Case.REL0GIN, liveControl = true)

    private fun race(case: Case, liveControl: Boolean = false) = runBlocking {
        withTimeout(25_000) {
            val h = EnvelopeRaceHarness(case).apply { initialize() }
            try {
                if (liveControl) verifyLivePositive(h) else runContentionScenario(h)
            } finally {
                h.close()
            }
            Unit
        }
    }

    private suspend fun verifyLivePositive(h: EnvelopeRaceHarness) {
        h.resumeRelogin.complete(Unit)
        check(h.login.reloginApiKey(LocalAccountId("A"), "fixture").isSuccess)
        val persisted = checkNotNull(h.sessions.load(h.aProvider, "A"))
        val decrypted = h.cipher.decrypt(persisted.payloadCiphertext, persisted.payloadNonce).decodeToString()
        check(decrypted.contains("fixture"))
        println("liveStorePositive realOwnerWired=true syntheticCredentialPersisted=true")
    }

    private suspend fun runContentionScenario(h: EnvelopeRaceHarness) = coroutineScope {
        val atWriter = CompletableDeferred<Unit>()
        val rowGone = CompletableDeferred<Unit>()
        val deletion = createDeletionRepo(h, atWriter, rowGone)
        val relogin = if (h.case == Case.REL0GIN) {
            async(Dispatchers.IO) { h.login.reloginApiKey(LocalAccountId("A"), "fixture") }
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
            check(h.sessions.load(h.aProvider, "A") == null)
        }
        val post = async(h.io) { h.guard.commitSerialized { h.publisher.publish(h.repo.loadCurrentState()) } }
        if (h.case == Case.REL0GIN) {
            h.loaded.await()
            // Earlier publisher turn reached the mutex before the deletion releases it.
            withContext(h.io) { Unit }
            h.resumeDeletion.complete(Unit)
        }
        h.atSink.await()
        val handles = WriterHandles(atWriter, ::deleteA, earlyDelete, relogin)
        val writer = async(Dispatchers.IO) { runWriterTask(h, handles) }
        if (h.case == Case.IMPORT || h.case == Case.DELETE) atWriter.await()
        val completed = withTimeoutOrNull(400) { writer.await(); true } == true
        val before = h.target()
        check(h.title() == null)
        h.openSink.countDown()
        post.await()
        writer.await()
        earlyDelete?.await()
        assertFinalPostState(h, before, completed, relogin)
    }

    private suspend fun runWriterTask(h: EnvelopeRaceHarness, handles: WriterHandles) {
        when (h.case) {
            Case.IMPORT -> {
                val ss = object : SecureSessionStore by h.sessions {
                    override suspend fun save(envelope: ProviderSessionEnvelope) {
                        h.sessions.save(envelope)
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

    private suspend fun assertFinalPostState(
        h: EnvelopeRaceHarness,
        before: String?,
        completed: Boolean,
        relogin: Deferred<Result<ProviderAccount>>?,
    ) {
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
        if (h.case == Case.REL0GIN) {
            val reason = relogin!!.await().exceptionOrNull()?.message
            val row = h.db.providerAccountDao().getById("A")
            val snap = h.db.quotaSnapshotDao().getLatestForAccount(h.aProvider, "A")
            println("discarded=$reason row=${row != null} snapshot=${snap != null}")
            val persisted = h.sessions.load(h.aProvider, "A")
            val syntheticMatches = persisted?.let {
                h.cipher.decrypt(it.payloadCiphertext, it.payloadNonce).decodeToString().contains("fixture")
            } ?: false
            println("after discard: encryptedFilePresent=${persisted != null} containsTestCredential=$syntheticMatches")
            check(persisted == null) { "Deleted account credential envelope was written again" }
            check(reason?.startsWith("Re-login discarded:") == true && row == null && snap == null)
        }
        check(!completed) { "writer finished inside posting boundary" }
    }

    private fun createDeletionRepo(
        h: EnvelopeRaceHarness,
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
            h.sessions,
            h.store,
            cleanup,
            deleteRows,
            h.prefs,
            h.guard,
            h.boundary,
        )
    }

    private class WriterHandles(
        val atWriter: CompletableDeferred<Unit>,
        val deleteA: suspend () -> Unit,
        val earlyDelete: Deferred<Unit>?,
        val relogin: Deferred<Result<ProviderAccount>>?,
    )

    private class EnvelopeRaceHarness(val case: Case) {
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
        val sessions = FileSecureSessionStore(File(ctx.filesDir, UUID.randomUUID().toString()))
        val cipher = AesGcmPayloadCipher { SecretKeySpec(ByteArray(16) { 3 }, "AES") }
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
            login = buildLoginUseCase()
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

        private fun buildLoginUseCase(): SessionLoginUseCase {
            val http = OkHttpClient.Builder().addInterceptor { chain ->
                atProvider.complete(Unit)
                runBlocking { resumeRelogin.await() }
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("fixture")
                    .body("""{"is_available":true,"balance_infos":[]}""".toResponseBody())
                    .build()
            }.build()
            val provider = DeepSeekSessionImporter(
                DeepSeekBalanceClient(ProviderHttpClient(http)),
                cipher,
                clock,
            )
            // CM-10 R5: the store MUST be wired like production (AppContainer).
            return SessionLoginUseCase(
                SessionImportRouter(mapOf(ProviderId(aProvider) to provider)),
                db,
                db.providerAccountDao(),
                db.quotaSnapshotDao(),
                store,
                guard,
                clock,
                boundary,
                secureSessionStore = sessions,
            )
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
