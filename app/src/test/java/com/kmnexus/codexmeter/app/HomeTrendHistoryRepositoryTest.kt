package com.kmnexus.codexmeter.app

import androidx.room.Room
import com.kmnexus.codexmeter.data.local.db.CodexMeterDatabase
import com.kmnexus.codexmeter.data.local.entity.ProviderAccountEntity
import com.kmnexus.codexmeter.data.local.entity.QuotaSnapshotEntity
import com.kmnexus.codexmeter.data.preferences.CurrentAccountReader
import com.kmnexus.codexmeter.data.preferences.CurrentAccountSelection
import com.kmnexus.codexmeter.domain.model.LocalAccountId
import com.kmnexus.codexmeter.domain.model.ProviderId
import com.kmnexus.codexmeter.domain.quota.QuotaWindowDisplayKind
import com.kmnexus.codexmeter.ui.home.HomeTrendQuery
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class HomeTrendHistoryRepositoryTest {

    private val percentQuery = HomeTrendQuery("five_hour", QuotaWindowDisplayKind.Percent, useModelBucketSum = false)

    @Test
    fun `percent window charts hourly increase in used percent`() = runTest {
        withRepository { db, reader, repository ->
            db.providerAccountDao().upsert(account())
            reader.selection = CurrentAccountSelection(ProviderId("codex"), LocalAccountId("local-1"))
            // 09:30 used=10, 10:30 used=25 (+15), 11:30 used=40 (+15)
            db.quotaSnapshotDao().insert(percentSnapshot("a", Instant.parse("2026-05-24T09:30:00Z"), used = 10))
            db.quotaSnapshotDao().insert(percentSnapshot("b", Instant.parse("2026-05-24T10:30:00Z"), used = 25))
            db.quotaSnapshotDao().insert(percentSnapshot("c", Instant.parse("2026-05-24T11:30:00Z"), used = 40))

            val points = repository.loadTrend(LocalAccountId("local-1"), percentQuery)

            assertEquals(listOf(15.0, 15.0), points.map { it.usageValue })
            assertEquals(
                listOf(Instant.parse("2026-05-24T10:30:00Z"), Instant.parse("2026-05-24T11:30:00Z")),
                points.map { it.capturedAt },
            )
        }
    }

    @Test
    fun `window reset clamps the negative delta to zero`() = runTest {
        withRepository { db, reader, repository ->
            db.providerAccountDao().upsert(account())
            reader.selection = CurrentAccountSelection(ProviderId("codex"), LocalAccountId("local-1"))
            // 90% -> reset to 5% should not produce a negative bar
            db.quotaSnapshotDao().insert(percentSnapshot("a", Instant.parse("2026-05-24T10:00:00Z"), used = 90))
            db.quotaSnapshotDao().insert(percentSnapshot("b", Instant.parse("2026-05-24T11:00:00Z"), used = 5))
            db.quotaSnapshotDao().insert(percentSnapshot("c", Instant.parse("2026-05-24T11:30:00Z"), used = 12))

            val points = repository.loadTrend(LocalAccountId("local-1"), percentQuery)

            assertEquals(listOf(0.0, 7.0), points.map { it.usageValue })
        }
    }

    @Test
    fun `balance window charts hourly spend and clamps top-ups`() = runTest {
        withRepository { db, reader, repository ->
            db.providerAccountDao().upsert(account(provider = "deepseek"))
            reader.selection = CurrentAccountSelection(ProviderId("deepseek"), LocalAccountId("local-1"))
            // 100 -> 92 (spend 8) -> 120 (top-up, clamp 0) -> 117 (spend 3)
            db.quotaSnapshotDao().insert(balanceSnapshot("a", Instant.parse("2026-05-24T09:00:00Z"), balance = "100"))
            db.quotaSnapshotDao().insert(balanceSnapshot("b", Instant.parse("2026-05-24T10:00:00Z"), balance = "92"))
            db.quotaSnapshotDao().insert(balanceSnapshot("c", Instant.parse("2026-05-24T11:00:00Z"), balance = "120"))
            db.quotaSnapshotDao().insert(balanceSnapshot("d", Instant.parse("2026-05-24T11:30:00Z"), balance = "117"))

            val points = repository.loadTrend(
                LocalAccountId("local-1"),
                HomeTrendQuery("balance", QuotaWindowDisplayKind.Balance, useModelBucketSum = false),
            )

            assertEquals(listOf(8.0, 0.0, 3.0), points.map { it.usageValue })
        }
    }

    @Test
    fun `single snapshot yields no usage points`() = runTest {
        withRepository { db, reader, repository ->
            db.providerAccountDao().upsert(account())
            reader.selection = CurrentAccountSelection(ProviderId("codex"), LocalAccountId("local-1"))
            db.quotaSnapshotDao().insert(percentSnapshot("a", Instant.parse("2026-05-24T11:00:00Z"), used = 10))

            val points = repository.loadTrend(LocalAccountId("local-1"), percentQuery)

            assertEquals(emptyList<Double>(), points.map { it.usageValue })
        }
    }

    @Test
    fun `model bucket sum adds per-model usage and clamps per model`() = runTest {
        withRepository { db, reader, repository ->
            db.providerAccountDao().upsert(account(provider = "antigravity"))
            reader.selection = CurrentAccountSelection(ProviderId("antigravity"), LocalAccountId("local-1"))
            // claude 0.9->0.7 remaining (+0.2 used); gemini 0.5->0.6 remaining (usage decreased, clamp 0) => 0.2 total
            db.quotaSnapshotDao().insert(
                bucketSnapshot("a", Instant.parse("2026-05-24T10:00:00Z"), claudeRemaining = 0.9, geminiRemaining = 0.5),
            )
            db.quotaSnapshotDao().insert(
                bucketSnapshot("b", Instant.parse("2026-05-24T11:00:00Z"), claudeRemaining = 0.7, geminiRemaining = 0.6),
            )

            val points = repository.loadTrend(
                LocalAccountId("local-1"),
                HomeTrendQuery("antigravity_claude_window", QuotaWindowDisplayKind.MultiModelFraction, useModelBucketSum = true),
            )

            assertEquals(1, points.size)
            assertEquals(0.2, points.single().usageValue, 1e-9)
        }
    }

    @Test
    fun `model bucket sum aggregates deltas across multiple family windows in one snapshot`() = runTest {
        withRepository { db, reader, repository ->
            db.providerAccountDao().upsert(account(provider = "antigravity"))
            reader.selection = CurrentAccountSelection(ProviderId("antigravity"), LocalAccountId("local-1"))
            // claude remaining 0.9->0.8 (+0.1 used); gemini remaining 0.6->0.5 (+0.1 used) => total 0.2
            db.quotaSnapshotDao().insert(
                multiWindowBucketSnapshot("a", Instant.parse("2026-05-24T10:00:00Z"), claudeRemaining = 0.9, geminiRemaining = 0.6),
            )
            db.quotaSnapshotDao().insert(
                multiWindowBucketSnapshot("b", Instant.parse("2026-05-24T11:00:00Z"), claudeRemaining = 0.8, geminiRemaining = 0.5),
            )

            val points = repository.loadTrend(
                LocalAccountId("local-1"),
                HomeTrendQuery("antigravity_claude_window", QuotaWindowDisplayKind.MultiModelFraction, useModelBucketSum = true),
            )

            assertEquals(1, points.size)
            assertEquals(0.2, points.single().usageValue, 1e-9)
        }
    }

    @Test
    fun `snapshots before 24 hour window start are excluded from trend calculation`() = runTest {
        withRepository { db, reader, repository ->
            db.providerAccountDao().upsert(account())
            reader.selection = CurrentAccountSelection(ProviderId("codex"), LocalAccountId("local-1"))
            // Clock is fixed at 2026-05-24T12:00:00Z, so windowStart = 2026-05-23T12:00:00Z.
            // The "old" snapshot at 11:59:59 is one second before the window and must not be paired.
            db.quotaSnapshotDao().insert(percentSnapshot("old", Instant.parse("2026-05-23T11:59:59Z"), used = 5))
            db.quotaSnapshotDao().insert(percentSnapshot("b", Instant.parse("2026-05-24T10:00:00Z"), used = 10))
            db.quotaSnapshotDao().insert(percentSnapshot("c", Instant.parse("2026-05-24T11:00:00Z"), used = 18))

            val points = repository.loadTrend(LocalAccountId("local-1"), percentQuery)

            // Only the two in-window snapshots produce a delta (18 - 10 = 8); the old snapshot is excluded.
            assertEquals(listOf(8.0), points.map { it.usageValue })
        }
    }

    @Test
    fun `usage points are positioned by timestamp inside the 24 hour window`() = runTest {
        withRepository { db, reader, repository ->
            db.providerAccountDao().upsert(account())
            reader.selection = CurrentAccountSelection(ProviderId("codex"), LocalAccountId("local-1"))
            db.quotaSnapshotDao().insert(percentSnapshot("a", Instant.parse("2026-05-23T18:00:00Z"), used = 10))
            db.quotaSnapshotDao().insert(percentSnapshot("b", Instant.parse("2026-05-24T00:00:00Z"), used = 20))

            val points = repository.loadTrend(LocalAccountId("local-1"), percentQuery)

            assertEquals(1, points.size)
            assertEquals(0.5f, points.single().xPositionInWindow!!, 0.001f)
        }
    }

    private suspend fun withRepository(
        block: suspend (CodexMeterDatabase, InMemoryCurrentAccountReader, HomeTrendHistoryRepository) -> Unit,
    ) {
        val db = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            CodexMeterDatabase::class.java,
        ).build()
        val reader = InMemoryCurrentAccountReader()
        val repository = HomeTrendHistoryRepository(
            currentAccountReader = reader,
            providerAccountDao = db.providerAccountDao(),
            quotaSnapshotDao = db.quotaSnapshotDao(),
            clock = Clock.fixed(Instant.parse("2026-05-24T12:00:00Z"), ZoneOffset.UTC),
        )
        try {
            block(db, reader, repository)
        } finally {
            db.close()
        }
    }

    private class InMemoryCurrentAccountReader : CurrentAccountReader {
        var selection: CurrentAccountSelection? = null
        override suspend fun currentAccountSelection(): CurrentAccountSelection? = selection
    }

    private fun account(provider: String = "codex") = ProviderAccountEntity(
        localAccountId = "local-1",
        providerId = provider,
        providerAccountId = "acct-1",
        displayName = "Work",
        avatarInitial = "W",
        avatarColorKey = "local-1",
        status = "active",
        createdAt = Instant.parse("2026-05-23T11:00:00Z").toEpochMilli(),
        updatedAt = Instant.parse("2026-05-23T11:30:00Z").toEpochMilli(),
        lastSuccessfulRefreshAt = null,
    )

    private fun entity(id: String, provider: String, fetchedAt: Instant, windowsJson: String) =
        QuotaSnapshotEntity(
            snapshotId = "snapshot-$id",
            providerId = provider,
            localAccountId = "local-1",
            providerAccountId = "acct-1",
            fetchedAt = fetchedAt.toEpochMilli(),
            source = "manualRefresh",
            planType = "plus",
            windowsJson = windowsJson,
            creditsJson = null,
            responseDigest = "safe-digest",
        )

    private fun percentSnapshot(id: String, fetchedAt: Instant, used: Int) = entity(
        id, "codex", fetchedAt,
        """[{"windowId":"five_hour","titleKey":"k","usedPercent":$used,"resetAt":1779555600000,"limitWindowSeconds":18000,"isPrimaryCandidate":true,"availability":"Available"}]""",
    )

    private fun balanceSnapshot(id: String, fetchedAt: Instant, balance: String) = entity(
        id, "deepseek", fetchedAt,
        """[{"windowId":"balance","titleKey":"k","usedPercent":null,"resetAt":null,"limitWindowSeconds":null,"isPrimaryCandidate":true,"availability":"Available","displayKind":"Balance","balanceAmount":"$balance"}]""",
    )

    private fun bucketSnapshot(id: String, fetchedAt: Instant, claudeRemaining: Double, geminiRemaining: Double) = entity(
        id, "antigravity", fetchedAt,
        """[{"windowId":"antigravity_claude_window","titleKey":"k","usedPercent":10,"resetAt":null,"limitWindowSeconds":null,"isPrimaryCandidate":true,"availability":"Available","displayKind":"MultiModelFraction","usesModelBucketSum":true,"modelBuckets":[{"modelId":"claude","displayName":"Claude","remainingFraction":$claudeRemaining},{"modelId":"gemini","displayName":"Gemini","remainingFraction":$geminiRemaining}]}]""",
    )

    /**
     * Two distinct model-family windows in a single snapshot, each with usesModelBucketSum=true.
     * Mirrors real Antigravity snapshots where each family is a separate window object.
     */
    private fun multiWindowBucketSnapshot(id: String, fetchedAt: Instant, claudeRemaining: Double, geminiRemaining: Double) = entity(
        id, "antigravity", fetchedAt,
        """[""" +
            """{"windowId":"antigravity_claude_window","titleKey":"k","usedPercent":10,"resetAt":null,"limitWindowSeconds":null,"isPrimaryCandidate":true,"availability":"Available","displayKind":"MultiModelFraction","usesModelBucketSum":true,"modelBuckets":[{"modelId":"claude","displayName":"Claude","remainingFraction":$claudeRemaining}]},""" +
            """{"windowId":"antigravity_gemini_pro_window","titleKey":"k","usedPercent":10,"resetAt":null,"limitWindowSeconds":null,"isPrimaryCandidate":false,"availability":"Available","displayKind":"MultiModelFraction","usesModelBucketSum":true,"modelBuckets":[{"modelId":"gemini","displayName":"Gemini","remainingFraction":$geminiRemaining}]}""" +
            """]""",
    )
}
