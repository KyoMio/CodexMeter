package com.kmnexus.codexmeter.app

import androidx.room.Room
import com.kmnexus.codexmeter.data.currency.ExchangeRateReader
import com.kmnexus.codexmeter.data.local.db.CodexMeterDatabase
import com.kmnexus.codexmeter.data.local.entity.ProviderAccountEntity
import com.kmnexus.codexmeter.data.local.entity.QuotaSnapshotEntity
import com.kmnexus.codexmeter.domain.currency.CurrencyPreferenceReader
import com.kmnexus.codexmeter.domain.currency.CurrencyPreferences
import com.kmnexus.codexmeter.domain.currency.ExchangeRates
import com.kmnexus.codexmeter.domain.settings.NotificationPreferenceReader
import com.kmnexus.codexmeter.domain.settings.NotificationPreferences
import com.kmnexus.codexmeter.widget.WidgetQuotaConfiguration
import com.kmnexus.codexmeter.widget.WidgetQuotaTone
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
// Robolectric SDK 36 currently requires Java 21, while this project is pinned to Java 17.
@Config(sdk = [35])
class WidgetQuotaStateRepositoryTest {
    @Test
    fun `default configuration yields unconfigured state with accounts flag`() = runTest {
        withRepository { db, repository ->
            // 库中存在至少一个非删除账号，但配置为空 -> 引导态、hasAccounts=true
            db.providerAccountDao().upsert(account(localAccountId = "local-1", displayName = "Work"))
            db.quotaSnapshotDao().insert(snapshot(snapshotId = "snapshot-1", localAccountId = "local-1"))

            val state = repository.loadWidgetQuotaState(WidgetQuotaConfiguration())

            assertTrue(state.isUnconfigured)
            assertTrue(state.hasAccounts)
        }
    }

    @Test
    fun `configured account loads only selected windows in order`() = runTest {
        withRepository { db, repository ->
            db.providerAccountDao().upsert(account(localAccountId = "acc-1", displayName = "Work"))
            db.quotaSnapshotDao().insert(snapshot(snapshotId = "snapshot-1", localAccountId = "acc-1"))

            val config = WidgetQuotaConfiguration(
                providerId = "codex", localAccountId = "acc-1",
                selectedWindowIds = listOf("weekly", "five_hour"),
            )
            val state = repository.loadWidgetQuotaState(config)
            assertFalse(state.isUnconfigured)
            assertEquals(listOf("five_hour", "weekly"), state.fields.map { it.windowId })
        }
    }

    @Test
    fun `widget field carries provider-reported window duration`() = runTest {
        withRepository { db, repository ->
            db.providerAccountDao().upsert(account(localAccountId = "acc-1", displayName = "Work"))
            // Codex 移除 5 小时窗口后，five_hour 槽位可能实际承载周额度（604800 秒）。
            // 微件字段必须携带上报时长，标签才能按时长而非槽位选取。
            db.quotaSnapshotDao().insert(
                snapshot(snapshotId = "snapshot-1", localAccountId = "acc-1", fiveHourWindowSeconds = 604800),
            )

            val config = WidgetQuotaConfiguration(
                providerId = "codex", localAccountId = "acc-1",
                selectedWindowIds = listOf("five_hour"),
            )
            val state = repository.loadWidgetQuotaState(config)

            assertEquals(listOf("five_hour"), state.fields.map { it.windowId })
            assertEquals(604800, state.fields[0].limitWindowSeconds)
        }
    }

    @Test
    fun `balance field tone follows thresholds in target currency`() = runTest {
        withRepository(
            notificationPreferences = NotificationPreferences(
                balanceCautionThreshold = 10.0,
                balanceWarningThreshold = 2.0,
            ),
            rates = ExchangeRates(
                base = "USD",
                rates = mapOf("USD" to 1.0, "CNY" to 7.0),
                fetchedAt = Instant.parse("2026-05-23T11:00:00Z"),
            ),
        ) { db, repository ->
            db.providerAccountDao().upsert(account(localAccountId = "acc-1", displayName = "Work"))
            // ¥63.00 按汇率 7 折成 $9.00，落在注意阈值（$10）与紧张阈值（$2）之间；
            // 与首页/通知一致，先换算再比较阈值，显示值也用目标货币。
            db.quotaSnapshotDao().insert(
                balanceSnapshot(
                    snapshotId = "snapshot-1",
                    localAccountId = "acc-1",
                    amount = "63.00",
                    currency = "CNY",
                ),
            )

            val config = WidgetQuotaConfiguration(
                providerId = "codex", localAccountId = "acc-1",
                selectedWindowIds = listOf("balance"),
            )
            val state = repository.loadWidgetQuotaState(config)

            val field = state.fields.single()
            assertEquals("9.00", field.balanceAmount)
            assertEquals("USD", field.balanceCurrency)
            assertEquals(WidgetQuotaTone.Warning, field.tone)
            assertEquals(WidgetQuotaTone.Warning, state.tone)
        }
    }

    private suspend fun withRepository(
        notificationPreferences: NotificationPreferences = NotificationPreferences(),
        rates: ExchangeRates? = null,
        block: suspend (CodexMeterDatabase, WidgetQuotaStateRepository) -> Unit,
    ) {
        val db = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            CodexMeterDatabase::class.java,
        ).build()
        val repository = WidgetQuotaStateRepository(
            providerAccountDao = db.providerAccountDao(),
            quotaSnapshotDao = db.quotaSnapshotDao(),
            refreshAttemptDao = db.refreshAttemptDao(),
            notificationPreferenceReader = StaticNotificationPreferenceReader(notificationPreferences),
            currencyPreferenceReader = StaticCurrencyPreferenceReader(CurrencyPreferences()),
            exchangeRateReader = StaticExchangeRateReader(rates),
            clock = Clock.fixed(Instant.parse("2026-05-23T12:00:00Z"), ZoneOffset.UTC),
        )

        try {
            block(db, repository)
        } finally {
            db.close()
        }
    }

    private class StaticNotificationPreferenceReader(
        private val notificationPreferences: NotificationPreferences,
    ) : NotificationPreferenceReader {
        override suspend fun notificationPreferences(): NotificationPreferences = notificationPreferences
    }

    private class StaticCurrencyPreferenceReader(
        private val preferences: CurrencyPreferences,
    ) : CurrencyPreferenceReader {
        override suspend fun currencyPreferences(): CurrencyPreferences = preferences
    }

    private class StaticExchangeRateReader(
        private val rates: ExchangeRates?,
    ) : ExchangeRateReader {
        override suspend fun currentRates(): ExchangeRates? = rates
    }

    private fun account(
        localAccountId: String,
        displayName: String,
    ) = ProviderAccountEntity(
        localAccountId = localAccountId,
        providerId = "codex",
        providerAccountId = "acct-$localAccountId",
        displayName = displayName,
        avatarInitial = displayName.first().toString(),
        avatarColorKey = localAccountId,
        status = "active",
        createdAt = Instant.parse("2026-05-23T11:00:00Z").toEpochMilli(),
        updatedAt = Instant.parse("2026-05-23T11:30:00Z").toEpochMilli(),
        lastSuccessfulRefreshAt = null,
    )

    private fun snapshot(
        snapshotId: String,
        localAccountId: String,
        fiveHourUsed: Int = 62,
        weeklyUsed: Int = 41,
        fiveHourWindowSeconds: Int = 18000,
    ) = QuotaSnapshotEntity(
        snapshotId = snapshotId,
        providerId = "codex",
        localAccountId = localAccountId,
        providerAccountId = "acct-$localAccountId",
        fetchedAt = Instant.parse("2026-05-23T11:50:00Z").toEpochMilli(),
        source = "manualRefresh",
        planType = "plus",
        windowsJson = """[{"windowId":"five_hour","titleKey":"quota_window_five_hour","usedPercent":$fiveHourUsed,"resetAt":1779555600000,"limitWindowSeconds":$fiveHourWindowSeconds,"isPrimaryCandidate":true,"availability":"Available"},{"windowId":"weekly","titleKey":"quota_window_weekly","usedPercent":$weeklyUsed,"resetAt":1780012800000,"limitWindowSeconds":604800,"isPrimaryCandidate":true,"availability":"Available"}]""",
        creditsJson = null,
        responseDigest = "safe-digest-$snapshotId",
    )

    private fun balanceSnapshot(
        snapshotId: String,
        localAccountId: String,
        amount: String,
        currency: String,
    ) = QuotaSnapshotEntity(
        snapshotId = snapshotId,
        providerId = "codex",
        localAccountId = localAccountId,
        providerAccountId = "acct-$localAccountId",
        fetchedAt = Instant.parse("2026-05-23T11:50:00Z").toEpochMilli(),
        source = "manualRefresh",
        planType = null,
        windowsJson = """[{"windowId":"balance","titleKey":"quota_window_balance","usedPercent":null,"resetAt":null,"limitWindowSeconds":null,"isPrimaryCandidate":true,"availability":"Available","displayKind":"Balance","balanceAmount":"$amount","balanceCurrency":"$currency"}]""",
        creditsJson = null,
        responseDigest = "safe-digest-$snapshotId",
    )
}
