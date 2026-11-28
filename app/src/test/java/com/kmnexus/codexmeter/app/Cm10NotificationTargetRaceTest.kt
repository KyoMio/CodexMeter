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
import com.kmnexus.codexmeter.domain.settings.NotificationPreferenceReader
import com.kmnexus.codexmeter.domain.settings.NotificationPreferences
import com.kmnexus.codexmeter.notification.AlertDedupeKey
import com.kmnexus.codexmeter.notification.AlertThresholds
import com.kmnexus.codexmeter.notification.AlertThresholdsReader
import com.kmnexus.codexmeter.notification.AndroidNotificationSink
import com.kmnexus.codexmeter.notification.CurrentQuotaNotificationPublisher
import com.kmnexus.codexmeter.notification.NotificationAccountTargetResolver
import com.kmnexus.codexmeter.notification.NotificationAlertStateStore
import com.kmnexus.codexmeter.notification.NotificationOrchestrator
import com.kmnexus.codexmeter.notification.NotificationRequest
import com.kmnexus.codexmeter.notification.NotificationRequestOptions
import com.kmnexus.codexmeter.notification.NotificationRequestOptionsReader
import com.kmnexus.codexmeter.notification.NotificationSink
import com.kmnexus.codexmeter.notification.NotificationTargetBoundary
import com.kmnexus.codexmeter.notification.QuotaAlertEvent
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
        RaceConfig(pauseInCurrencyRead = false, expectStaleAlpha = false),
    )

    @Test
    fun `switch saved after target read drops stale alpha notification`() = runRace(
        RaceConfig(pauseInCurrencyRead = true, expectStaleAlpha = false),
    )

    @Test
    fun `failed refresh after target read also drops stale alpha`() = runRace(
        RaceConfig(pauseInCurrencyRead = true, failedRefresh = true, expectStaleAlpha = false),
    )

    @Test
    fun `completed edit after target read drops stale alpha`() = runRace(
        RaceConfig(pauseInCurrencyRead = true, completeEditBeforeResume = true, expectStaleAlpha = false),
    )

    /**
     * CM-10 R1 probe ordering WRITE-FIRST: the post path is paused inside the sink (already past
     * its target re-check), yet the write must NOT be able to commit while the post holds the
     * target boundary — instead we drive the write FIRST (write-first ordering): B commits before
     * any post-state work proceeds, and from that moment no Alpha post may ever land.
     */
    @Test
    fun `r1 write-first committed switch forbids any new alpha post`() = runRace(
        RaceConfig(
            pauseInCurrencyRead = false,
            gateAtSink = true,
            writeFirstOrdering = true,
            expectStaleAlpha = false,
        ),
    )

    /**
     * CM-10 R1 probe ordering POST-FIRST: the post path enters the sink while the selection write
     * is waiting on the target boundary. The write must only complete AFTER the post finished —
     * B 的写入应排在该发布之后. The Alpha post itself is legal (target at post time was A);
     * the notification is corrected by the post-switch refresh.
     */
    @Test
    fun `r1 post-first serializes switch write after the post`() = runRace(
        RaceConfig(
            pauseInCurrencyRead = false,
            gateAtSink = true,
            postFirstOrdering = true,
            expectStaleAlpha = false,
        ),
    )

    private data class RaceConfig(
        val pauseInCurrencyRead: Boolean,
        val failedRefresh: Boolean = false,
        val completeEditBeforeResume: Boolean = false,
        val gateAtSink: Boolean = false,
        val writeFirstOrdering: Boolean = false,
        val postFirstOrdering: Boolean = false,
        val expectStaleAlpha: Boolean,
    )

    private fun runRace(config: RaceConfig) = runBlocking {
        withTimeout(30_000L) {
            val h = RaceHarness(config).apply { initialize() }
            try {
                executeRace(h, config)
            } finally {
                h.db.close()
            }
            Unit
        }
    }

    private suspend fun executeRace(h: RaceHarness, config: RaceConfig) = coroutineScope {
        val alphaRefresh = async(Dispatchers.Default) {
            h.refresh.refresh(h.alpha.toDomain(), RefreshTrigger.Manual)
        }
        h.paused.await()
        val switch = async(Dispatchers.Default) {
            h.mutation.persistDefaultAccountSelection(h.selectionA.providerId, LocalAccountId("B"))
        }
        if (!config.postFirstOrdering) {
            h.switchSaved.await()
        }
        try {
            if (config.completeEditBeforeResume) {
                h.endEdit.complete(Unit)
                check(switch.await())
            }
            coordinateOrdering(h, config, switch)
            val outcome = alphaRefresh.await()
            val expectedSuccess = !config.failedRefresh
            check(outcome is RefreshResult.Success == expectedSuccess)
            assertRaceResult(h, config)
        } finally {
            h.endEdit.complete(Unit)
        }
        check(switch.await())
        // 下一次 B 刷新必须纠正为 Beta（活性收敛）
        check(h.refresh.refresh(h.beta.toDomain(), RefreshTrigger.AccountSwitch) is RefreshResult.Success)
        val finalTitle = h.postedTitleOrNull()
        check(finalTitle?.contains("Audit Beta") == true) {
            "next B refresh did not correct the notification: $finalTitle"
        }
        println("after next B refresh: $finalTitle")
    }

    private suspend fun coordinateOrdering(
        h: RaceHarness,
        config: RaceConfig,
        switch: Deferred<Boolean>,
    ) {
        when {
            config.postFirstOrdering -> {
                // ★ R1 POST-FIRST：write 必须排在 post 之后。
                check(!h.switchSaved.isCompleted) {
                    "write committed while the post held the target boundary — serialization broken"
                }
                check(h.writeCommittedNanos.get() == 0L) {
                    "write committed BEFORE the post finished — ordering violated"
                }
                h.allowPost.countDown()
                delay(200)
                check(h.postCompleted.get() >= 1) { "post never completed after the gate opened" }
                h.endEdit.complete(Unit)
                h.resume.complete(Unit)
            }
            config.writeFirstOrdering -> {
                // ★ R1 WRITE-FIRST：B 先提交。
                h.endEdit.complete(Unit)
                check(switch.await())
                check(h.store.currentAccountSelection()?.localAccountId?.value == "B")
                h.resume.complete(Unit)
            }
            else -> {
                if (!config.gateAtSink) {
                    check(h.store.currentAccountSelection()?.localAccountId?.value == "B")
                }
                h.endEdit.complete(Unit)
                h.resume.complete(Unit)
            }
        }
    }

    private fun assertRaceResult(h: RaceHarness, config: RaceConfig) {
        if (config.postFirstOrdering) {
            val wc = h.writeCommittedNanos.get()
            val pc = h.postCompletedNanos.get()
            println("post-first ordering: writeCommittedNanos=$wc postCompletedNanos=$pc")
            check(wc == 0L || pc == 0L || wc >= pc) {
                "write committed before the post finished (wc=$wc pc=$pc) — serialization broken"
            }
        }
        val posted = h.postedTitleOrNull()
        println(
            "cm10 pauseInCurrencyRead=${config.pauseInCurrencyRead} failed=${config.failedRefresh} " +
                "completeEdit=${config.completeEditBeforeResume} posted=$posted",
        )
        if (config.expectStaleAlpha) {
            check(posted?.contains("Audit Alpha") == true)
        } else if (config.writeFirstOrdering) {
            check(posted?.contains("Audit Alpha") != true) {
                "new Alpha notification landed after the committed switch (write-first): $posted"
            }
        } else if (!config.postFirstOrdering && !config.pauseInCurrencyRead) {
            // 切换先于读取的正对照：通知必须是 Beta（不过度丢弃）
            check(posted?.contains("Audit Beta") == true) {
                "positive control broken: expected Beta, got $posted"
            }
        } else if (!config.postFirstOrdering) {
            check(posted?.contains("Audit Alpha") != true) {
                "stale Alpha notification posted after switch took effect: $posted"
            }
        }
    }

    private class RaceHarness(val config: RaceConfig) {
        private val ctx = RuntimeEnvironment.getApplication() as Context
        val db = Room.inMemoryDatabaseBuilder(ctx, CodexMeterDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        private val manager = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        private val clock = Clock.fixed(Instant.parse("2026-05-23T12:00:00Z"), ZoneOffset.UTC)
        val alpha = FixtureAccounts.account(localAccountId = "A", displayName = "Audit Alpha")
        val beta = FixtureAccounts.account(localAccountId = "B", displayName = "Audit Beta")
        val store = CurrentAccountPreferences.create(
            File(ctx.filesDir, "cm10-" + UUID.randomUUID() + ".preferences_pb"),
        )
        val selectionA = CurrentAccountSelection(ProviderId("codex"), LocalAccountId("A"))
        val paused = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        val switchSaved = CompletableDeferred<Unit>()
        val endEdit = CompletableDeferred<Unit>()
        val writeCommittedNanos = AtomicLong(0)
        val postCompletedNanos = AtomicLong(0)
        val allowPost = CountDownLatch(1)
        val postCompleted = AtomicInteger()
        private val guard = VersionedStateGuard()
        private val targetBoundary = NotificationTargetBoundary()
        lateinit var mutation: AccountMutationRepository
        lateinit var refresh: RefreshCoordinator

        suspend fun initialize() {
            db.providerAccountDao().upsert(alpha)
            db.providerAccountDao().upsert(beta)
            db.quotaSnapshotDao().insert(
                FixtureAccounts.snapshot(
                    fetchedAt = Instant.parse("2026-05-23T11:50:00Z"),
                    snapshotId = "B-seed",
                    localAccountId = "B",
                ),
            )
            store.updateCurrentAccountSelection(selectionA)
            mutation = buildMutationRepo()
            val publisher = buildPublisher()
            refresh = buildRefreshCoordinator(publisher)
        }

        private suspend fun pauseHere() {
            paused.complete(Unit)
            resume.await()
        }

        private fun buildMutationRepo() = AccountMutationRepository(
            providerAccountDao = db.providerAccountDao(),
            clock = clock,
            stateGuard = guard,
            notificationTargetBoundary = targetBoundary,
            currentAccountStore = object : CurrentAccountStore by store {
                override suspend fun updateCurrentAccountSelection(selection: CurrentAccountSelection?) {
                    store.updateCurrentAccountSelection(selection)
                    // R1 probe 时序证据：提交时刻必须晚于 post 完成（post-first 排序）。
                    writeCommittedNanos.set(System.nanoTime())
                    switchSaved.complete(Unit)
                    endEdit.await()
                }
            },
        )

        private fun buildPublisher(): CurrentQuotaNotificationPublisher {
            val repo = CurrentQuotaStateRepository(
                store,
                db.providerAccountDao(),
                db.quotaSnapshotDao(),
                db.refreshAttemptDao(),
                clock = clock,
                currencyPreferenceReader = object : CurrencyPreferenceReader {
                    override suspend fun currencyPreferences(): CurrencyPreferences {
                        if (config.pauseInCurrencyRead) pauseHere()
                        return CurrencyPreferences()
                    }
                },
            )
            val resolver = NotificationAccountTargetResolver(
                notificationPreferenceReader = object : NotificationPreferenceReader {
                    override suspend fun notificationPreferences() = NotificationPreferences()
                },
                providerAccountDao = db.providerAccountDao(),
                currentAccountReader = store,
            )
            val realSink = AndroidNotificationSink(ctx)
            return CurrentQuotaNotificationPublisher(
                notificationSink = if (config.gateAtSink) {
                    object : NotificationSink by realSink {
                        override fun post(request: NotificationRequest) {
                            if (!config.writeFirstOrdering) {
                                paused.complete(Unit)
                                check(allowPost.await(10, TimeUnit.SECONDS))
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
                    override suspend fun hasNotified(key: AlertDedupeKey) = error("unexpected alert")
                    override suspend fun markNotified(event: QuotaAlertEvent, notifiedAt: Instant): Unit =
                        error("unexpected alert")
                },
                optionsReader = NotificationRequestOptionsReader {
                    val pauseHereIsWriteFirstGate = config.writeFirstOrdering
                    if (!config.pauseInCurrencyRead && (!config.gateAtSink || pauseHereIsWriteFirstGate)) pauseHere()
                    NotificationRequestOptions(true, true, false, false)
                },
                alertThresholdsReader = AlertThresholdsReader { AlertThresholds() },
                statusNotificationStateLoader = repo,
                notificationTargetResolver = resolver,
                notificationTargetBoundary = targetBoundary,
                clock = clock,
            )
        }

        private fun buildRefreshCoordinator(publisher: CurrentQuotaNotificationPublisher) = RefreshCoordinator(
            provider = RefreshProvider { acc, _ ->
                if (config.failedRefresh && acc.localAccountId.value == "A") {
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
    }
}
