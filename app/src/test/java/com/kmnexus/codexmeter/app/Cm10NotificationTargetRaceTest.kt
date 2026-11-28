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

    private fun runRace(
        pauseInCurrencyRead: Boolean,
        failedRefresh: Boolean = false,
        completeEditBeforeResume: Boolean = false,
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
                suspend fun pauseHere() {
                    paused.complete(Unit)
                    resume.await()
                }
                val guard = VersionedStateGuard()
                val mutation = AccountMutationRepository(
                    providerAccountDao = db.providerAccountDao(),
                    clock = clock,
                    stateGuard = guard,
                    currentAccountStore = object : CurrentAccountStore by store {
                        override suspend fun updateCurrentAccountSelection(selection: CurrentAccountSelection?) {
                            store.updateCurrentAccountSelection(selection)
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
                val publisher = CurrentQuotaNotificationPublisher(
                    notificationSink = AndroidNotificationSink(ctx),
                    alertStateStore = object : NotificationAlertStateStore {
                        override suspend fun hasNotified(key: com.kmnexus.codexmeter.notification.AlertDedupeKey) =
                            error("unexpected alert")
                        override suspend fun markNotified(
                            event: com.kmnexus.codexmeter.notification.QuotaAlertEvent,
                            notifiedAt: Instant,
                        ): Unit = error("unexpected alert")
                    },
                    optionsReader = NotificationRequestOptionsReader {
                        if (!pauseInCurrencyRead) pauseHere()
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
                switchSaved.await()
                try {
                    if (completeEditBeforeResume) {
                        endEdit.complete(Unit)
                        check(switch.await())
                    }
                    check(store.currentAccountSelection()?.localAccountId?.value == "B")
                    resume.complete(Unit)
                    val outcome = alphaRefresh.await()
                    if (failedRefresh) {
                        check(outcome is RefreshResult.Failure)
                    } else {
                        check(outcome is RefreshResult.Success)
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
                        if (!pauseInCurrencyRead) {
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
