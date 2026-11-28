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
import com.kmnexus.codexmeter.data.repository.AccountMutationRepository
import com.kmnexus.codexmeter.data.repository.RoomAccountLocalDataDeleter
import com.kmnexus.codexmeter.data.repository.RoomCodexSessionImportPersistence
import com.kmnexus.codexmeter.data.repository.toDomain
import com.kmnexus.codexmeter.data.secure.FakeSecureSessionStore
import com.kmnexus.codexmeter.data.secure.ProviderSessionEnvelope
import com.kmnexus.codexmeter.domain.account.NoopDeletedAccountStateCleaner
import com.kmnexus.codexmeter.domain.model.LocalAccountId
import com.kmnexus.codexmeter.domain.model.ProviderId
import com.kmnexus.codexmeter.domain.quota.CurrentQuotaState
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
import java.io.File
import java.time.Clock
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class Cm10R2BoundaryCoverageTest {
    enum class Case { SWITCH, PIN, IMPORT, DELETE_PINNED, ROLLBACK, WRITE_FIRST, IMPORT_LIVE_PIN }

    @Test fun switchQueues() = exercise(Case.SWITCH)
    @Test fun pinQueuesAndRepublishes() = exercise(Case.PIN)
    @Test fun importCannotPostPreviousAccount() = exercise(Case.IMPORT)
    @Test fun importPreservesExistingLivePin() = exercise(Case.IMPORT_LIVE_PIN)
    @Test fun deletingPinCannotPostDeletedAccount() = exercise(Case.DELETE_PINNED)
    @Test fun rollbackCannotPostAbandonedFallback() = exercise(Case.ROLLBACK)
    @Test fun committedSwitchDropsAlreadyLoadedFrame() = exercise(Case.WRITE_FIRST)

    private fun exercise(case: Case) = runBlocking {
        withTimeout(20_000) {
            val h = BoundaryHarness(case).apply { initialize() }
            try {
                runBoundaryScenario(h)
            } finally {
                h.close()
            }
            Unit
        }
    }

    private suspend fun runBoundaryScenario(h: BoundaryHarness) = coroutineScope {
        val inDelete = CompletableDeferred<Unit>()
        val deletion = createDeletionRepo(h, inDelete)
        val rollback = if (h.case == Case.ROLLBACK) {
            async(Dispatchers.IO) {
                val err = runCatching { deletion.deleteAccount(h.provider, LocalAccountId("A")) }.exceptionOrNull()
                check(err?.message == "controlled storage failure")
            }
        } else {
            null
        }
        if (rollback != null) {
            inDelete.await()
            // 故障立抛后 restore 可能已经完成（目标回 A）或仍在飞（目标暂 B）——
            // 两者都是合法中间态，一致性由末尾标题/目标对照钉死。
            check(h.target() in setOf("A", "B")) { "unexpected target mid-rollback: ${h.target()}" }
        }
        val post = async(Dispatchers.IO) { h.guard.commitSerialized { h.publisher.publish(h.repo.loadCurrentState()) } }
        if (h.case == Case.WRITE_FIRST) {
            verifyWriteFirst(h, post)
        } else {
            verifyConcurrentWriter(h, deletion, rollback, post)
        }
    }

    private suspend fun verifyWriteFirst(h: BoundaryHarness, post: Deferred<Unit>) {
        h.atLoad.await()
        check(h.mutations.persistDefaultAccountSelection(h.provider, LocalAccountId("B")))
        h.resumeLoad.complete(Unit)
        post.await()
        check(!h.atSink.isCompleted && h.title() == null)
        println("write-first durable=B; loaded Alpha dropped; posts=0")
    }

    private suspend fun verifyConcurrentWriter(
        h: BoundaryHarness,
        deletion: AccountDeletionRepository,
        rollback: Deferred<Unit>?,
        post: Deferred<Unit>,
    ) = coroutineScope {
        h.atSink.await()
        val writer = launchWriter(h, deletion, rollback)
        val writerCompletedWhilePostHeld = AtomicBoolean(false)
        val targetMovedBeforeCommit = AtomicBoolean(false)
        checkWriterHeldByBoundary(h, writer, writerCompletedWhilePostHeld, targetMovedBeforeCommit)
        check(h.title() == null)
        val before = h.target()
        h.releasePost.countDown()
        post.await()
        writer.await()
        val actualTarget = h.sinkTarget.await()
        val actualTitle = h.sinkTitle.await()
        println(
            "case=${h.case} resolvedBeforeRelease=$before atSystemPost=$actualTarget " +
                "firstTitle=$actualTitle finalTarget=" + h.target(),
        )
        check(!writerCompletedWhilePostHeld.get() && !targetMovedBeforeCommit.get()) {
            "writer serialization broken: completedWhilePostHeld=${writerCompletedWhilePostHeld.get()} " +
                "targetMoved=${targetMovedBeforeCommit.get()} (case=${h.case})"
        }
        check(actualTitle?.contains(if (actualTarget == "A") "Audit Alpha" else "Audit Beta") == true) {
            "notification target=$actualTarget at post, new notification=$actualTitle"
        }
        if (h.case == Case.PIN) check(h.title()?.contains("Audit Beta") == true)
    }

    private fun kotlinx.coroutines.CoroutineScope.launchWriter(
        h: BoundaryHarness,
        deletion: AccountDeletionRepository,
        rollback: Deferred<Unit>?,
    ) = async(Dispatchers.IO) {
        when (h.case) {
            Case.SWITCH -> check(h.mutations.persistDefaultAccountSelection(h.provider, LocalAccountId("B")))
            Case.PIN -> {
                val repub = RepublishingNotificationPreferenceStore(
                    h.prefs,
                    h.repo,
                    h.publisher,
                    stateGuard = h.guard,
                    notificationTargetBoundary = h.boundary,
                )
                repub.updateNotificationPreferences(
                    h.prefs.notificationPreferences().copy(
                        persistentNotificationAccount = NotificationAccountSelection(h.provider, LocalAccountId("B")),
                    ),
                )
            }
            Case.IMPORT, Case.IMPORT_LIVE_PIN -> {
                val env = ProviderSessionEnvelope(
                    providerId = "codex",
                    localAccountId = "B",
                    providerAccountId = "acct-B",
                    schemaVersion = 1,
                    payloadCiphertext = byteArrayOf(1),
                    payloadNonce = byteArrayOf(2),
                    createdAt = "2026-01-01T00:00:00Z",
                    updatedAt = "2026-01-01T00:00:00Z",
                )
                RoomCodexSessionImportPersistence(
                    h.db,
                    h.sessions,
                    h.store,
                    h.guard,
                    notificationTargetBoundary = h.boundary,
                ).save(
                    h.beta.toDomain(),
                    env,
                    FixtureAccounts.snapshot(snapshotId = "import-B", localAccountId = "B").toDomain(),
                    allowNewRow = true,
                    expectedLocalAccountId = null,
                )
            }
            Case.DELETE_PINNED -> deletion.deleteAccount(h.provider, LocalAccountId("A"))
            Case.ROLLBACK -> {
                h.abortDeletion.complete(Unit)
                rollback!!.await()
            }
            else -> error("unexpected")
        }
        Unit
    }

    private suspend fun checkWriterHeldByBoundary(
        h: BoundaryHarness,
        writer: Deferred<Unit>,
        writerCompletedWhilePostHeld: AtomicBoolean,
        targetMovedBeforeCommit: AtomicBoolean,
    ) {
        // CM-10 R2 改编说明：writer 的任何目标写入都排在 post 释放锁之后，
        // 这里验证 writer 在 post 落地前被挡，post 按发布时刻目标落地。
        when (h.case) {
            Case.IMPORT, Case.IMPORT_LIVE_PIN, Case.DELETE_PINNED -> {
                if (withTimeoutOrNull(250) { writer.await(); true } != null) {
                    writerCompletedWhilePostHeld.set(true)
                }
                if (h.target() != "A") {
                    targetMovedBeforeCommit.set(true)
                }
            }
            Case.ROLLBACK -> writer.await()
            else -> {
                while (h.guard.inFlightEdits == 0 && !writer.isCompleted) delay(1)
                check(withTimeoutOrNull(250) { writer.await(); true } == null)
                check(h.target() == "A")
            }
        }
    }

    private fun createDeletionRepo(
        h: BoundaryHarness,
        inDelete: CompletableDeferred<Unit>,
    ): AccountDeletionRepository {
        val localDataDeleter = if (h.case == Case.ROLLBACK) {
            object : AccountLocalDataDeleter {
                override suspend fun deleteLocalData(providerId: ProviderId, localAccountId: LocalAccountId) {
                    // R2 改编：故障立即抛出。故障立抛后 restore 与 post 自由竞争锁，
                    // 两种排序都必须满足「标题与发布时刻目标一致」。
                    inDelete.complete(Unit)
                    error("controlled storage failure")
                }
            }
        } else {
            RoomAccountLocalDataDeleter(h.db)
        }
        return AccountDeletionRepository(
            h.db,
            h.sessions,
            h.store,
            NoopDeletedAccountStateCleaner,
            localDataDeleter = localDataDeleter,
            notificationPreferenceStore = h.prefs,
            stateGuard = h.guard,
            notificationTargetBoundary = h.boundary,
        )
    }

    private class BoundaryHarness(val case: Case) {
        private val ctx: Context = RuntimeEnvironment.getApplication()
        val db = Room.inMemoryDatabaseBuilder(ctx, CodexMeterDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        private val manager = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val releasePost = CountDownLatch(1)
        val abortDeletion = CompletableDeferred<Unit>()
        val store = CurrentAccountPreferences.create(File(ctx.filesDir, "${UUID.randomUUID()}.preferences_pb"))
        val prefs = NotificationPreferencesDataStore(store.dataStore)
        val guard = VersionedStateGuard()
        val boundary = NotificationTargetBoundary()
        private val clock = Clock.systemUTC()
        val provider = ProviderId("codex")
        val beta = FixtureAccounts.account(localAccountId = "B", displayName = "Audit Beta")
        val resolver = NotificationAccountTargetResolver(prefs, db.providerAccountDao(), store)
        val repo = CurrentQuotaStateRepository(
            store,
            db.providerAccountDao(),
            db.quotaSnapshotDao(),
            db.refreshAttemptDao(),
            clock = clock,
            notificationTargetResolver = resolver,
        )
        val atSink = CompletableDeferred<Unit>()
        val atLoad = CompletableDeferred<Unit>()
        val resumeLoad = CompletableDeferred<Unit>()
        val sinkTarget = CompletableDeferred<String?>()
        val sinkTitle = CompletableDeferred<String?>()
        val mutations = AccountMutationRepository(
            db.providerAccountDao(),
            store,
            clock = clock,
            stateGuard = guard,
            notificationTargetBoundary = boundary,
        )
        val sessions = FakeSecureSessionStore()
        lateinit var publisher: CurrentQuotaNotificationPublisher

        suspend fun initialize() {
            val alpha = FixtureAccounts.account(localAccountId = "A", displayName = "Audit Alpha")
            for (account in listOf(alpha, beta)) {
                db.providerAccountDao().upsert(account)
                val snap = FixtureAccounts.snapshot(
                    localAccountId = account.localAccountId,
                    snapshotId = account.localAccountId,
                )
                db.quotaSnapshotDao().insert(snap)
            }
            val initialSel = if (case == Case.DELETE_PINNED) "B" else "A"
            store.updateCurrentAccountSelection(CurrentAccountSelection(provider, LocalAccountId(initialSel)))
            // A stored pin can outlive its Room row (for example failed best-effort rebind).
            val initialPin = if (case in setOf(Case.DELETE_PINNED, Case.PIN, Case.IMPORT_LIVE_PIN)) {
                "A"
            } else {
                "removed"
            }
            prefs.updateNotificationPreferences(
                prefs.notificationPreferences().copy(
                    persistentNotificationAccount = NotificationAccountSelection(
                        provider,
                        LocalAccountId(initialPin),
                    ),
                ),
            )
            publisher = buildPublisher()
            check(title() == null)
        }

        private fun buildPublisher(): CurrentQuotaNotificationPublisher {
            val real = AndroidNotificationSink(ctx)
            val first = AtomicBoolean(true)
            return CurrentQuotaNotificationPublisher(
                notificationSink = object : NotificationSink by real {
                    override fun post(request: NotificationRequest) {
                        if (first.compareAndSet(true, false)) {
                            atSink.complete(Unit)
                            check(releasePost.await(7, TimeUnit.SECONDS))
                            val resolvedId = runBlocking { resolver.resolveTargetSelection()?.localAccountId?.value }
                            sinkTarget.complete(resolvedId)
                            real.post(request)
                            sinkTitle.complete(title())
                        } else {
                            real.post(request)
                        }
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
                statusNotificationStateLoader = object : StatusNotificationStateLoader {
                    override suspend fun loadStatusNotificationState(refreshedState: CurrentQuotaState) =
                        repo.loadStatusNotificationState(refreshedState).also {
                            if (case == Case.WRITE_FIRST) {
                                atLoad.complete(Unit)
                                resumeLoad.await()
                            }
                        }
                },
                notificationTargetResolver = resolver,
                notificationTargetBoundary = boundary,
                clock = clock,
            )
        }

        fun title(): String? =
            manager.activeNotifications.singleOrNull { it.id == 1001 }
                ?.notification?.extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString()

        suspend fun target(): String? = resolver.resolveTargetSelection()?.localAccountId?.value

        fun close() {
            releasePost.countDown()
            abortDeletion.complete(Unit)
            db.close()
        }
    }
}
