package com.kmnexus.codexmeter.app

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.room.Room
import com.kmnexus.codexmeter.core.concurrency.VersionedStateGuard
import com.kmnexus.codexmeter.data.local.db.CodexMeterDatabase
import com.kmnexus.codexmeter.data.preferences.*
import com.kmnexus.codexmeter.data.repository.*
import com.kmnexus.codexmeter.data.secure.*
import com.kmnexus.codexmeter.domain.account.NoopDeletedAccountStateCleaner
import com.kmnexus.codexmeter.domain.model.*
import com.kmnexus.codexmeter.domain.settings.NotificationAccountSelection
import com.kmnexus.codexmeter.notification.*
import kotlinx.coroutines.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.time.Clock
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

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
            val ctx: Context = RuntimeEnvironment.getApplication()
            val db = Room.inMemoryDatabaseBuilder(ctx, CodexMeterDatabase::class.java).allowMainThreadQueries().build()
            val releasePost = CountDownLatch(1)
            val abortDeletion = CompletableDeferred<Unit>()
            try {
                val manager = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                fun title() = manager.activeNotifications.singleOrNull { it.id == 1001 }
                    ?.notification?.extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString()
                check(title() == null)
                val store = CurrentAccountPreferences.create(File(ctx.filesDir, UUID.randomUUID().toString() + ".preferences_pb"))
                val prefs = NotificationPreferencesDataStore(store.dataStore)
                val guard = VersionedStateGuard()
                val boundary = NotificationTargetBoundary()
                val clock = Clock.systemUTC()
                val provider = ProviderId("codex")
                fun selection(id: String) = CurrentAccountSelection(provider, LocalAccountId(id))
                fun pinned(id: String) = NotificationAccountSelection(provider, LocalAccountId(id))
                val alpha = FixtureAccounts.account(localAccountId="A", displayName="Audit Alpha")
                val beta = FixtureAccounts.account(localAccountId="B", displayName="Audit Beta")
                for (account in listOf(alpha, beta)) {
                    db.providerAccountDao().upsert(account)
                    db.quotaSnapshotDao().insert(FixtureAccounts.snapshot(localAccountId=account.localAccountId, snapshotId=account.localAccountId))
                }
                store.updateCurrentAccountSelection(selection(if (case == Case.DELETE_PINNED) "B" else "A"))
                // A stored pin can outlive its Room row (for example failed best-effort rebind).
                val initialPin = if (case in setOf(Case.DELETE_PINNED,Case.PIN,Case.IMPORT_LIVE_PIN)) "A" else "removed"
                prefs.updateNotificationPreferences(prefs.notificationPreferences().copy(persistentNotificationAccount=pinned(initialPin)))
                val resolver = NotificationAccountTargetResolver(prefs, db.providerAccountDao(), store)
                suspend fun target() = resolver.resolveTargetSelection()?.localAccountId?.value
                val repo = CurrentQuotaStateRepository(store,db.providerAccountDao(),db.quotaSnapshotDao(),db.refreshAttemptDao(), clock=clock, notificationTargetResolver=resolver)
                val real = AndroidNotificationSink(ctx)
                val atSink = CompletableDeferred<Unit>()
                val atLoad = CompletableDeferred<Unit>()
                val resumeLoad = CompletableDeferred<Unit>()
                val first = AtomicBoolean(true)
                val writerCompletedWhilePostHeld = AtomicBoolean(false)
                val targetMovedBeforeCommit = AtomicBoolean(false)
                val sinkTarget = CompletableDeferred<String?>()
                val sinkTitle = CompletableDeferred<String?>()
                val publisher = CurrentQuotaNotificationPublisher(
                    notificationSink=object : NotificationSink by real {
                        override fun post(request: NotificationRequest) {
                            if (first.compareAndSet(true,false)) {
                                atSink.complete(Unit)
                                check(releasePost.await(7,TimeUnit.SECONDS))
                                sinkTarget.complete(runBlocking { target() })
                                real.post(request)
                                sinkTitle.complete(title())
                            } else real.post(request)
                        }
                    },
                    alertStateStore=object : NotificationAlertStateStore {
                        override suspend fun hasNotified(key: AlertDedupeKey) = false
                        override suspend fun markNotified(event: QuotaAlertEvent,notifiedAt: Instant) = Unit
                    },
                    optionsReader=NotificationRequestOptionsReader { NotificationRequestOptions(true,true,false,false) },
                    alertThresholdsReader=AlertThresholdsReader { AlertThresholds() },
                    statusNotificationStateLoader=object : StatusNotificationStateLoader {
                        override suspend fun loadStatusNotificationState(refreshedState: com.kmnexus.codexmeter.domain.quota.CurrentQuotaState) =
                            repo.loadStatusNotificationState(refreshedState).also {
                                if (case == Case.WRITE_FIRST) { atLoad.complete(Unit); resumeLoad.await() }
                            }
                    },
                    notificationTargetResolver=resolver, notificationTargetBoundary=boundary, clock=clock)
                val mutations = AccountMutationRepository(db.providerAccountDao(),store,clock=clock,stateGuard=guard,notificationTargetBoundary=boundary)
                val inDelete = CompletableDeferred<Unit>()
                val sessions = FakeSecureSessionStore()
                val deletion = AccountDeletionRepository(
                    db,sessions,store,NoopDeletedAccountStateCleaner,
                    localDataDeleter=if (case == Case.ROLLBACK) object : AccountLocalDataDeleter {
                        override suspend fun deleteLocalData(providerId: ProviderId, localAccountId: LocalAccountId) {
                            // R2 改编：故障立即抛出。生产的存储故障不会挂起等待外部放行——
                            // 原编排的 abortDeletion 挂起点把调度控制埋进了 boundary 临界区
                            // （删除协程持锁挂起/post 进不了锁/放行者没机会启动的三方死锁），
                            // 该交错在互斥实现下不是可达生产形态。故障立抛后 restore 与 post
                            // 自由竞争锁，两种排序都必须满足「标题与发布时刻目标一致」。
                            inDelete.complete(Unit); error("controlled storage failure")
                        }
                    } else RoomAccountLocalDataDeleter(db),
                    notificationPreferenceStore=prefs,stateGuard=guard,notificationTargetBoundary=boundary)
                val rollback = if (case == Case.ROLLBACK) async(Dispatchers.IO) {
                    check(runCatching { deletion.deleteAccount(provider,LocalAccountId("A")) }.exceptionOrNull()?.message == "controlled storage failure")
                } else null
                if (rollback != null) {
                    inDelete.await()
                    // 故障立抛后 restore 可能已经完成（目标回 A）或仍在飞（目标暂 B）——
                    // 两者都是合法中间态，一致性由末尾标题/目标对照钉死。
                    check(target() in setOf("A", "B")) { "unexpected target mid-rollback: ${target()}" }
                }

                val post = async(Dispatchers.IO) { guard.commitSerialized { publisher.publish(repo.loadCurrentState()) } }
                if (case == Case.WRITE_FIRST) {
                    atLoad.await()
                    check(mutations.persistDefaultAccountSelection(provider,LocalAccountId("B")))
                    resumeLoad.complete(Unit)
                    post.await()
                    check(!atSink.isCompleted && title() == null)
                    println("write-first durable=B; loaded Alpha dropped; posts=0")
                } else {
                    atSink.await()
                    val writer = async(Dispatchers.IO) {
                        when (case) {
                            Case.SWITCH -> check(mutations.persistDefaultAccountSelection(provider,LocalAccountId("B")))
                            Case.PIN -> RepublishingNotificationPreferenceStore(prefs,repo,publisher,stateGuard=guard,notificationTargetBoundary=boundary)
                                .updateNotificationPreferences(prefs.notificationPreferences().copy(persistentNotificationAccount=pinned("B")))
                            Case.IMPORT, Case.IMPORT_LIVE_PIN -> RoomCodexSessionImportPersistence(db,sessions,store,guard,notificationTargetBoundary=boundary).save(
                                beta.toDomain(), ProviderSessionEnvelope(providerId="codex",localAccountId="B",providerAccountId="acct-B",schemaVersion=1,
                                    payloadCiphertext=byteArrayOf(1),payloadNonce=byteArrayOf(2),createdAt="2026-01-01T00:00:00Z",updatedAt="2026-01-01T00:00:00Z"),
                                FixtureAccounts.snapshot(snapshotId="import-B",localAccountId="B").toDomain(), allowNewRow = true)
                            Case.DELETE_PINNED -> deletion.deleteAccount(provider,LocalAccountId("A"))
                            Case.ROLLBACK -> { abortDeletion.complete(Unit); rollback!!.await() }

                            else -> error("unexpected")
                        }
                        Unit
                    }
                    // CM-10 R2 改编说明：御史原编排让 writer 在 post 持锁期间完成。
                    // 互斥实现下该交错不可达——writer 的任何目标写入都排在 post 释放锁之后，
                    // 这正是修复要达成的不变量（写与重核+发布互斥）。因此这里验证两种
                    // 可达排序各自的一致性：writer 在 post 落地前必须被挡（不得完成），
                    // post 按发布时刻的目标落地，writer 随后完成并纠正终态。
                    when (case) {
                        Case.IMPORT, Case.IMPORT_LIVE_PIN, Case.DELETE_PINNED -> {
                            // 记录制串行证据：writer 是否趁 post 持锁完成、目标是否提前漂移。
                            // 不立即 check——让执行继续到末尾的不变量断言，mutation 下错发
                            // 会以「标题与发布时刻目标不符」的形式显形（御史原始失败形态）。
                            if (withTimeoutOrNull(250) { writer.await(); true } != null) {
                                writerCompletedWhilePostHeld.set(true)
                            }
                            if (target() != "A") {
                                targetMovedBeforeCommit.set(true)
                            }
                        }
                        Case.ROLLBACK -> {
                            // restore 与 post 自由竞争 boundary：restore 先完成（post 重核见 A）
                            // 或 post 先落地（restore 排队）都是合法排序——一致性由末尾的
                            // 标题/发布时刻目标对照钉死，这里只等 writer 落地。
                            writer.await()
                        }
                        else -> {
                            while (guard.inFlightEdits == 0 && !writer.isCompleted) delay(1)
                            check(withTimeoutOrNull(250) { writer.await(); true } == null)
                            check(target()=="A")
                        }
                    }
                    check(title()==null)
                    val before = target()
                    releasePost.countDown()
                    post.await(); writer.await()
                    val actualTarget = sinkTarget.await()
                    val actualTitle = sinkTitle.await()
                    println("case=$case resolvedBeforeRelease=$before atSystemPost=$actualTarget firstTitle=$actualTitle finalTarget=" + target())
                    check(!writerCompletedWhilePostHeld.get() && !targetMovedBeforeCommit.get()) {
                        "writer serialization broken: completedWhilePostHeld=${writerCompletedWhilePostHeld.get()} " +
                            "targetMoved=${targetMovedBeforeCommit.get()} (case=$case)"
                    }
                    check(actualTitle?.contains(if (actualTarget=="A") "Audit Alpha" else "Audit Beta")==true) {
                        "notification target=$actualTarget at post, new notification=$actualTitle"
                    }
                    if (case==Case.PIN) check(title()?.contains("Audit Beta")==true)
                }
            } finally { releasePost.countDown(); abortDeletion.complete(Unit); db.close() }
            Unit
        }
    }
}
