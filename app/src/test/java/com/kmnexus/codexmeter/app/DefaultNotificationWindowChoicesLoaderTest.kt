package com.kmnexus.codexmeter.app

import androidx.room.Room
import com.kmnexus.codexmeter.data.local.db.CodexMeterDatabase
import com.kmnexus.codexmeter.data.local.entity.ProviderAccountEntity
import com.kmnexus.codexmeter.data.local.entity.QuotaSnapshotEntity
import com.kmnexus.codexmeter.data.preferences.CurrentAccountReader
import com.kmnexus.codexmeter.data.preferences.CurrentAccountSelection
import com.kmnexus.codexmeter.domain.model.LocalAccountId
import com.kmnexus.codexmeter.domain.model.ProviderId
import java.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
// Robolectric SDK 36 currently requires Java 21, while this project is pinned to Java 17.
@Config(sdk = [35])
class DefaultNotificationWindowChoicesLoaderTest {
    @Test
    fun `window choices carry the provider-reported window duration`() = runTest {
        withLoader { db, currentAccountReader, loader ->
            db.providerAccountDao().upsert(account())
            currentAccountReader.selection = CurrentAccountSelection(ProviderId("codex"), LocalAccountId("local-1"))
            db.quotaSnapshotDao().insert(snapshot())

            val choices = loader.windowChoices(null, null)

            // Codex dropped the 5-hour window, so primary_window now reports a weekly duration.
            assertEquals(604_800, choices.single { it.windowId.value == "five_hour" }.limitWindowSeconds)
        }
    }

    @Test
    fun `window choices skip a slot the provider did not send`() = runTest {
        withLoader { db, currentAccountReader, loader ->
            db.providerAccountDao().upsert(account())
            currentAccountReader.selection = CurrentAccountSelection(ProviderId("codex"), LocalAccountId("local-1"))
            db.quotaSnapshotDao().insert(snapshot())

            val choices = loader.windowChoices(null, null)

            // secondary_window is absent, so the mapper emitted a Missing "weekly" slot.
            assertEquals(listOf("five_hour"), choices.map { it.windowId.value })
        }
    }

    private suspend fun withLoader(
        block: suspend (CodexMeterDatabase, InMemoryCurrentAccountReader, DefaultNotificationWindowChoicesLoader) -> Unit,
    ) {
        val db = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            CodexMeterDatabase::class.java,
        ).build()
        val currentAccountReader = InMemoryCurrentAccountReader()
        val loader = DefaultNotificationWindowChoicesLoader(
            currentAccountReader = currentAccountReader,
            quotaSnapshotDao = db.quotaSnapshotDao(),
        )

        try {
            block(db, currentAccountReader, loader)
        } finally {
            db.close()
        }
    }

    private class InMemoryCurrentAccountReader : CurrentAccountReader {
        var selection: CurrentAccountSelection? = null

        override suspend fun currentAccountSelection(): CurrentAccountSelection? = selection
    }

    private fun account() = ProviderAccountEntity(
        localAccountId = "local-1",
        providerId = "codex",
        providerAccountId = "acct-1",
        displayName = "Work",
        avatarInitial = "W",
        avatarColorKey = "local-1",
        status = "active",
        createdAt = Instant.parse("2026-05-23T11:00:00Z").toEpochMilli(),
        updatedAt = Instant.parse("2026-05-23T11:30:00Z").toEpochMilli(),
        lastSuccessfulRefreshAt = null,
    )

    private fun snapshot() = QuotaSnapshotEntity(
        snapshotId = "snapshot-1",
        providerId = "codex",
        localAccountId = "local-1",
        providerAccountId = "acct-1",
        fetchedAt = Instant.parse("2026-05-23T11:50:00Z").toEpochMilli(),
        source = "manualRefresh",
        planType = "plus",
        windowsJson = """[{"windowId":"five_hour","titleKey":"quota_window_five_hour","usedPercent":41,"resetAt":1780012800000,"limitWindowSeconds":604800,"isPrimaryCandidate":true,"availability":"Available"},{"windowId":"weekly","titleKey":"quota_window_weekly","usedPercent":null,"resetAt":null,"limitWindowSeconds":null,"isPrimaryCandidate":true,"availability":"Missing"}]""",
        creditsJson = null,
        responseDigest = "safe-digest",
    )
}
