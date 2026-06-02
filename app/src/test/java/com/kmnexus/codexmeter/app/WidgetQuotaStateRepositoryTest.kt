package com.kmnexus.codexmeter.app

import androidx.room.Room
import com.kmnexus.codexmeter.data.local.db.CodexMeterDatabase
import com.kmnexus.codexmeter.data.local.entity.ProviderAccountEntity
import com.kmnexus.codexmeter.data.local.entity.QuotaSnapshotEntity
import com.kmnexus.codexmeter.domain.settings.NotificationPreferenceReader
import com.kmnexus.codexmeter.domain.settings.NotificationPreferences
import com.kmnexus.codexmeter.widget.WidgetQuotaConfiguration
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

    private suspend fun withRepository(
        notificationPreferences: NotificationPreferences = NotificationPreferences(),
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
    ) = QuotaSnapshotEntity(
        snapshotId = snapshotId,
        providerId = "codex",
        localAccountId = localAccountId,
        providerAccountId = "acct-$localAccountId",
        fetchedAt = Instant.parse("2026-05-23T11:50:00Z").toEpochMilli(),
        source = "manualRefresh",
        planType = "plus",
        windowsJson = """[{"windowId":"five_hour","titleKey":"quota_window_five_hour","usedPercent":$fiveHourUsed,"resetAt":1779555600000,"limitWindowSeconds":18000,"isPrimaryCandidate":true,"availability":"Available"},{"windowId":"weekly","titleKey":"quota_window_weekly","usedPercent":$weeklyUsed,"resetAt":1780012800000,"limitWindowSeconds":604800,"isPrimaryCandidate":true,"availability":"Available"}]""",
        creditsJson = null,
        responseDigest = "safe-digest-$snapshotId",
    )
}
