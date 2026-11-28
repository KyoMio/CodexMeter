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
import com.kmnexus.codexmeter.data.repository.AccountMutationRepository
import com.kmnexus.codexmeter.data.repository.RoomQuotaSnapshotStore
import com.kmnexus.codexmeter.data.repository.RoomRefreshAccountStatusStore
import com.kmnexus.codexmeter.data.repository.RoomRefreshAttemptStore
import com.kmnexus.codexmeter.data.repository.toDomain
import com.kmnexus.codexmeter.domain.currency.CurrencyPreferenceReader
import com.kmnexus.codexmeter.domain.currency.CurrencyPreferences
import com.kmnexus.codexmeter.domain.model.LocalAccountId
import com.kmnexus.codexmeter.domain.model.ProviderId
import com.kmnexus.codexmeter.domain.model.RefreshAttemptId
import com.kmnexus.codexmeter.domain.refresh.QuotaError
import com.kmnexus.codexmeter.domain.refresh.RefreshTrigger
import com.kmnexus.codexmeter.notification.AlertThresholds
import com.kmnexus.codexmeter.notification.AlertThresholdsReader
import com.kmnexus.codexmeter.notification.AndroidNotificationSink
import com.kmnexus.codexmeter.notification.CurrentQuotaNotificationPublisher
import com.kmnexus.codexmeter.notification.NotificationAccountTargetResolver
import com.kmnexus.codexmeter.notification.NotificationTargetBoundary
import com.kmnexus.codexmeter.notification.NotificationAlertStateStore
import com.kmnexus.codexmeter.notification.NotificationOrchestrator
import com.kmnexus.codexmeter.notification.NotificationRequestOptions
import com.kmnexus.codexmeter.notification.NotificationRequestOptionsReader
import com.kmnexus.codexmeter.refresh.AttemptIdProvider
import com.kmnexus.codexmeter.refresh.ProviderRefreshResult
import com.kmnexus.codexmeter.refresh.RefreshCoordinator
import com.kmnexus.codexmeter.refresh.RefreshProvider
import com.kmnexus.codexmeter.refresh.RefreshResult
import com.kmnexus.codexmeter.refresh.SelectedAccountReader
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * CM-10 regression: once selection B is in effect, a stale account-A notification must never be
 * posted again. Mirrors the audit fixture from CM7-R2 (御史复现路径) with assertions flipped to
 * the fixed behavior. Positive control: a switch completed before the target read must still post
 * the new account (no over-dropping).
 */
@RunWith(RobolectricTestRunner::class)
// Robolectric SDK 36 currently requires Java 21, while this project is pinned to Java 17.
@Config(sdk = [35])
class Cm10NotificationTargetRaceTest {

    @Test
    fun `switch saved before target read posts new account`() = runRace(
        pauseInCurrencyRead = false,
        expectStaleAlpha = false,
    )

    @Test
    fun `switch saved after target read drops stale alpha notification`() = runRace(
        pauseInCurrencyRead = true,
        expectStaleAlpha = false,
    )

    @Test
    fun `failed refresh after target read also drops stale alpha`() = runRace(
        pauseInCurrencyRead = true,
        failedRefresh = true,
        expectStaleAlpha = false,
    )

    @Test
    fun `completed edit after target read drops stale alpha`() = runRace(
        pauseInCurrencyRead = true,
        completeEditBeforeResume = true,
        expectStaleAlpha = false,
    )

    /**
     * CM-10 R1 probe ordering WRITE-FIRST: the post path is paused inside the sink (already past
     * its target re-check), yet the write must NOT be able to commit while the post holds the
     * target boundary — instead we drive the write FIRST (write-first ordering): B commits before
     * any post-state work proceeds, and from that moment no Alpha post may ever land.
     */
    @Test
    fun `r1 write-first committed switch forbids any new alpha post`() = runRace(
        pauseInCurrencyRead = false,
        gateAtSink = true,
        writeFirstOrdering = true,
        expectStaleAlpha = false,
    )

    /**
     * CM-10 R1 probe ordering POST-FIRST: the post path enters the sink while the selection write
     * is waiting on the target boundary. The write must only complete AFTER the post finished —
     * B 的写入应排在该发布之后. The Alpha post itself is legal (target at post time was A);
     * the notification is corrected by the post-switch refresh.
     */
    @Test
    fun `r1 post-first serializes switch write after the post`() = runRace(
        pauseInCurrencyRead = false,
        gateAtSink = true,
        postFirstOrdering = true,
        expectStaleAlpha = false,
    )

    private fun runRace(
        pauseInCurrencyRead: Boolean,
        failedRefresh: Boolean = false,
        completeEditBeforeResume: Boolean = false,
        gateAtSink: Boolean = false,
        writeFirstOrdering: Boolean = false,
        postFirstOrdering: Boolean = false,
        expectStaleAlpha: Boolean,
    ) = runBlocking {
        withTimeout(30_000L) {
            val ctx = RuntimeEnvironment.getApplication() as Context
            val db = Room.inMemoryDatabaseBuilder(ctx, CodexMeterDatabase::class.java)
                .allowMainThreadQueries()
                .build()
            try {
                val manager = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                val clock = Clock.fixed(Instant.parse("2026-05-23T12:00:00Z"), ZoneOffset.UTC)
                val alpha = FixtureAccounts.account(localAccountId = "A", displayName = "Audit Alpha")
                val beta = FixtureAccounts.account(localAccountId = "B", displayName = "Audit Beta")
                db.providerAccountDao().upsert(alpha)
                db.providerAccountDao().upsert(beta)
                db.quotaSnapshotDao().insert(
                    FixtureAccounts.snapshot(
                        fetchedAt = Instant.parse("2026-05-23T11:50:00Z"),
                        snapshotId = "B-seed",
                        localAccountId = "B",
                    ),
                )
                val store = CurrentAccountPreferences.create(
                    File(ctx.filesDir, "cm10-" + UUID.randomUUID() + ".preferences_pb"),
                )
                val selectionA = CurrentAccountSelection(ProviderId("codex"), LocalAccountId("A"))
                store.updateCurrentAccountSelection(selectionA)

                val paused = CompletableDeferred<Unit>()
                val resume = CompletableDeferred<Unit>()
                val switchSaved = CompletableDeferred<Unit>()
                val endEdit = CompletableDeferred<Unit>()
                val writeCommittedNanos = java.util.concurrent.atomic.AtomicLong(0)
                val postCompletedNanos = java.util.concurrent.atomic.AtomicLong(0)
                suspend fun pauseHere() {
                    paused.complete(Unit)
                    resume.await()
                }
                val guard = VersionedStateGuard()
                val targetBoundary = NotificationTargetBoundary()
                val mutation = AccountMutationRepository(
                    providerAccountDao = db.providerAccountDao(),
                    clock = clock,
                    stateGuard = guard,
                    notificationTargetBoundary = targetBoundary,
                    currentAccountStore = object : CurrentAccountStore by store {
                        override suspend fun updateCurrentAccountSelection(selection: CurrentAccountSelection?) {
                            store.updateCurrentAccountSelection(selection)
                            // R1 probe 时序证据：提交时刻必须晚于 post 完成（post-first 排序）。
                            // 记录的是 DataStore 提交完成的时刻而非协程挂起点——即使协程被
                            // endEdit 门控挂住，「写入已提交」这一事实在挂起之前已发生。
                            writeCommittedNanos.set(System.nanoTime())
                            switchSaved.complete(Unit)
                            endEdit.await()
                        }
                    },
                )
                val repo = CurrentQuotaStateRepository(
                    store,
                    db.providerAccountDao(),
                    db.quotaSnapshotDao(),
                    db.refreshAttemptDao(),
                    clock = clock,
                    currencyPreferenceReader = object : CurrencyPreferenceReader {
                        override suspend fun currencyPreferences(): CurrencyPreferences {
                            if (pauseInCurrencyRead) pauseHere()
                            return CurrencyPreferences()
                        }
                    },
                )
                val resolver = NotificationAccountTargetResolver(
                    notificationPreferenceReader = object : com.kmnexus.codexmeter.domain.settings.NotificationPreferenceReader {
                        override suspend fun notificationPreferences() =
                            com.kmnexus.codexmeter.domain.settings.NotificationPreferences()
                    },
                    providerAccountDao = db.providerAccountDao(),
                    currentAccountReader = store,
                )
                val allowPost = java.util.concurrent.CountDownLatch(1)
                val postCompleted = java.util.concurrent.atomic.AtomicInteger()
                val realSink = AndroidNotificationSink(ctx)
                val publisher = CurrentQuotaNotificationPublisher(
                    notificationSink = if (gateAtSink) {
                        object : com.kmnexus.codexmeter.notification.NotificationSink by realSink {
                            override fun post(request: com.kmnexus.codexmeter.notification.NotificationRequest) {
                                if (!writeFirstOrdering) {
                                    // POST-FIRST probe: pause inside the system post — the write
                                    // is already waiting on the target boundary and must not be
                                    // able to complete before this post finishes.
                                    paused.complete(Unit)
                                    check(allowPost.await(10, java.util.concurrent.TimeUnit.SECONDS))
                                }
                                realSink.post(request)
                                postCompleted.incrementAndGet()
                                postCompletedNanos.compareAndSet(0, System.nanoTime())
                            }
                        }
                    } else {
                        realSink
                    },
                    alertStateStore = object : NotificationAlertStateStore {
                        override suspend fun hasNotified(key: com.kmnexus.codexmeter.notification.AlertDedupeKey) =
                            error("unexpected alert")
                        override suspend fun markNotified(
                            event: com.kmnexus.codexmeter.notification.QuotaAlertEvent,
                            notifiedAt: Instant,
                        ): Unit = error("unexpected alert")
                    },
                    optionsReader = NotificationRequestOptionsReader {
                        // WRITE-FIRST probe: pause BEFORE load/recheck so the write commits first;
                        // POST-FIRST probe: no pause here — the sink gate is the pause point.
                        val pauseHereIsWriteFirstGate = writeFirstOrdering
                        if (!pauseInCurrencyRead && (!gateAtSink || pauseHereIsWriteFirstGate)) pauseHere()
                        NotificationRequestOptions(
                            notificationPermissionAvailable = true,
                            statusNotificationEnabled = true,
                            quotaAlertsEnabled = false,
                            accountErrorsEnabled = false,
                        )
                    },
                    alertThresholdsReader = AlertThresholdsReader { AlertThresholds() },
                    statusNotificationStateLoader = repo,
                    notificationTargetResolver = resolver,
                    notificationTargetBoundary = targetBoundary,
                    clock = clock,
                )
                val refresh = RefreshCoordinator(
                    provider = RefreshProvider { acc, _ ->
                        if (failedRefresh && acc.localAccountId.value == "A") {
                            ProviderRefreshResult.Failure(QuotaError.Network("audit-io"))
                        } else {
                            ProviderRefreshResult.Success(
                                FixtureAccounts.snapshot(
                                    snapshotId = "new-" + acc.localAccountId.value,
                                    localAccountId = acc.localAccountId.value,
                                ).toDomain(),
                            )
                        }
                    },
                    snapshotStore = RoomQuotaSnapshotStore(db.quotaSnapshotDao()),
                    attemptStore = RoomRefreshAttemptStore(db.refreshAttemptDao()),
                    accountStatusStore = RoomRefreshAccountStatusStore(db.providerAccountDao()),
                    currentQuotaStatePublisher = publisher,
                    attemptIdProvider = AttemptIdProvider { RefreshAttemptId(UUID.randomUUID().toString()) },
                    clock = clock,
                    stateGuard = guard,
                    selectedAccountReader = SelectedAccountReader {
                        store.currentAccountSelection()?.localAccountId
                    },
                )

                fun postedTitleOrNull(): String? =
                    manager.activeNotifications
                        .singleOrNull { it.id == NotificationOrchestrator.STATUS_NOTIFICATION_ID }
                        ?.notification
                        ?.extras
                        ?.getCharSequence(Notification.EXTRA_TITLE)
                        ?.toString()

                val alphaRefresh = async(Dispatchers.Default) {
                    refresh.refresh(alpha.toDomain(), RefreshTrigger.Manual)
                }
                paused.await()
                val switch = async(Dispatchers.Default) {
                    mutation.persistDefaultAccountSelection(selectionA.providerId, LocalAccountId("B"))
                }
                if (!postFirstOrdering) {
                    switchSaved.await()
                }
                try {
                    if (completeEditBeforeResume) {
                        endEdit.complete(Unit)
                        check(switch.await())
                    }
                    when {
                        postFirstOrdering -> {
                            // ★ R1 POST-FIRST：write 必须排在 post 之后。
                            // post 此刻正停在 sink 闸门里（已过 recheck、持 boundary），write 在
                            // boundary 上排队、switchSaved 不可能已经触发。
                            check(!switchSaved.isCompleted) {
                                "write committed while the post held the target boundary — serialization broken"
                            }
                            check(writeCommittedNanos.get() == 0L) {
                                "write committed BEFORE the post finished — ordering violated"
                            }
                            allowPost.countDown()
                            // post 先完成；write 此后才获准进入 boundary
                            kotlinx.coroutines.delay(200)
                            check(postCompleted.get() >= 1) { "post never completed after the gate opened" }
                            // 若 write 在闸门打开后仍先提交（无 boundary 时必然如此），
                            // 时间戳对照必然翻转——这是判决要求的「write 排在发布之后」。
                            // endEdit 只影响 write 协程的完成，不影响 commit 时序（commit 时刻
                            // 由 writeCommittedNanos 钉死）；必须在此放行，否则 write 协程
                            // 卡在 endEdit 等待、alphaRefresh 被 boundary 挡住、死锁。
                            endEdit.complete(Unit)
                            resume.complete(Unit)
                        }
                        writeFirstOrdering -> {
                            // ★ R1 WRITE-FIRST：B 先提交。post 之后才开始 recheck——重核必须看到
                            // 新目标并弃发，从此刻起绝不许再新落 Alpha。
                            endEdit.complete(Unit)
                            check(switch.await())
                            check(store.currentAccountSelection()?.localAccountId?.value == "B")
                            resume.complete(Unit)
                        }
                        else -> {
                            if (!gateAtSink) {
                                check(store.currentAccountSelection()?.localAccountId?.value == "B")
                            }
                            // R1: the write now holds the target boundary while suspended on endEdit.
                            // If alphaRefresh must enter the boundary after the write committed,
                            // it can only do so after the write exits — endEdit must be released
                            // BEFORE the refresh proceeds, or the harness deadlocks. In production
                            // endEdit follows the write's completion with no external gate; the
                            // gate here is purely a scheduling instrument.
                            endEdit.complete(Unit)
                            resume.complete(Unit)
                        }
                    }
                    val outcome = alphaRefresh.await()
                    if (failedRefresh) {
                        check(outcome is RefreshResult.Failure)
                    } else {
                        check(outcome is RefreshResult.Success)
                    }
                    if (postFirstOrdering) {
                        // write 必须等到 post 完成后才提交（endEdit 已放行，write 要么在 boundary
                        // 上排队要么已提交——两种情况时间戳都可断言）
                        val wc = writeCommittedNanos.get()
                        val pc = postCompletedNanos.get()
                        println("post-first ordering: writeCommittedNanos=$wc postCompletedNanos=$pc")
                        check(wc == 0L || pc == 0L || wc >= pc) {
                            "write committed before the post finished (wc=$wc pc=$pc) — serialization broken"
                        }
                    }
                    val posted = postedTitleOrNull()
                    println(
                        "cm10 pauseInCurrencyRead=$pauseInCurrencyRead failed=$failedRefresh " +
                            "completeEdit=$completeEditBeforeResume posted=$posted",
                    )
                    if (expectStaleAlpha) {
                        check(posted?.contains("Audit Alpha") == true)
                    } else {
                        // ★ CM-10 验收核心：B 已生效时 A 的旧通知绝不能落地——
                        // 要么被弃（无通知或残留旧通知），要么已是 Beta；绝不能是 Alpha。
                        if (writeFirstOrdering) {
                            check(posted?.contains("Audit Alpha") != true) {
                                "new Alpha notification landed after the committed switch (write-first): $posted"
                            }
                        } else if (postFirstOrdering) {
                            // post-first 的 Alpha 落地合法（发布时目标确为 A）——不追加约束，
                            // 排序正确性已在上面的排队断言中钉死；纠正性由结尾 B 刷新验证。
                        } else if (!pauseInCurrencyRead) {
                            // 切换先于读取的正对照：通知必须是 Beta（不过度丢弃）
                            check(posted?.contains("Audit Beta") == true) {
                                "positive control broken: expected Beta, got $posted"
                            }
                        } else {
                            check(posted?.contains("Audit Alpha") != true) {
                                "stale Alpha notification posted after switch took effect: $posted"
                            }
                        }
                    }
                } finally {
                    endEdit.complete(Unit)
                }
                check(switch.await())
                // 下一次 B 刷新必须纠正为 Beta（活性收敛）
                check(refresh.refresh(beta.toDomain(), RefreshTrigger.AccountSwitch) is RefreshResult.Success)
                val finalTitle = postedTitleOrNull()
                check(finalTitle?.contains("Audit Beta") == true) {
                    "next B refresh did not correct the notification: $finalTitle"
                }
                println("after next B refresh: $finalTitle")
            } finally {
                db.close()
            }
            Unit
        }
    }
}
