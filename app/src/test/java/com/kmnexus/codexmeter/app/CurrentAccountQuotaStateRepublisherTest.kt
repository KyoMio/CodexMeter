package com.kmnexus.codexmeter.app

import androidx.room.Room
import com.kmnexus.codexmeter.data.local.db.CodexMeterDatabase
import com.kmnexus.codexmeter.data.local.entity.ProviderAccountEntity
import com.kmnexus.codexmeter.data.local.entity.QuotaSnapshotEntity
import com.kmnexus.codexmeter.data.preferences.CurrentAccountReader
import com.kmnexus.codexmeter.data.preferences.CurrentAccountSelection
import com.kmnexus.codexmeter.domain.model.LocalAccountId
import com.kmnexus.codexmeter.domain.model.ProviderAccount
import com.kmnexus.codexmeter.domain.model.ProviderAccountId
import com.kmnexus.codexmeter.domain.model.ProviderId
import com.kmnexus.codexmeter.domain.model.QuotaWindowId
import com.kmnexus.codexmeter.domain.quota.CurrentQuotaState
import com.kmnexus.codexmeter.domain.settings.PrimaryQuotaWindowPreferenceReader
import com.kmnexus.codexmeter.refresh.CurrentQuotaStatePublisher
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
// Robolectric SDK 36 currently requires Java 21, while this project is pinned to Java 17.
@Config(sdk = [35])
class CurrentAccountQuotaStateRepublisherTest {
    @Test
    fun `republish current account state uses persisted renamed account and latest snapshot`() = runTest {
        withRepublisher { db, currentAccountReader, publisher, republisher ->
            db.providerAccountDao().upsert(account(displayName = "Work", avatarInitial = "W"))
            db.quotaSnapshotDao().insert(snapshot())
            currentAccountReader.selection = CurrentAccountSelection(ProviderId("codex"), LocalAccountId("local-1"))

            republisher.republishCurrentAccountState(accountDomain(displayName = "Work", avatarInitial = "W"))

            val state = publisher.publishedStates.single()
            assertEquals("Work", state.account?.displayName)
            assertEquals("W", state.account?.avatarInitial)
            assertEquals("snapshot-1", state.snapshot?.snapshotId?.value)
            assertEquals(62, state.primaryWindow?.usedPercent)
        }
    }

    @Test
    fun `republish current account state uses persisted primary quota window`() = runTest {
        withRepublisher(primaryWindowId = QuotaWindowId("weekly")) { db, currentAccountReader, publisher, republisher ->
            db.providerAccountDao().upsert(account(displayName = "Work", avatarInitial = "W"))
            db.quotaSnapshotDao().insert(snapshot())
            currentAccountReader.selection = CurrentAccountSelection(ProviderId("codex"), LocalAccountId("local-1"))

            republisher.republishCurrentAccountState(accountDomain(displayName = "Work", avatarInitial = "W"))

            val state = publisher.publishedStates.single()
            assertEquals(QuotaWindowId("weekly"), state.primaryWindow?.windowId)
            assertEquals(41, state.primaryWindow?.usedPercent)
        }
    }

    @Test
    fun `republish ignores non-current account`() = runTest {
        withRepublisher { db, currentAccountReader, publisher, republisher ->
            db.providerAccountDao().upsert(account(displayName = "Work", avatarInitial = "W"))
            currentAccountReader.selection = CurrentAccountSelection(ProviderId("codex"), LocalAccountId("other"))

            republisher.republishCurrentAccountState(accountDomain(displayName = "Work", avatarInitial = "W"))

            assertEquals(emptyList<CurrentQuotaState>(), publisher.publishedStates)
        }
    }

    private suspend fun withRepublisher(
        primaryWindowId: QuotaWindowId = QuotaWindowId("five_hour"),
        block: suspend (
            CodexMeterDatabase,
            InMemoryCurrentAccountReader,
            RecordingCurrentQuotaStatePublisher,
            CurrentAccountQuotaStateRepublisher,
        ) -> Unit,
    ) {
        val db = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            CodexMeterDatabase::class.java,
        ).build()
        val currentAccountReader = InMemoryCurrentAccountReader()
        val publisher = RecordingCurrentQuotaStatePublisher()
        val republisher = CurrentAccountQuotaStateRepublisher(
            currentAccountReader = currentAccountReader,
            providerAccountDao = db.providerAccountDao(),
            quotaSnapshotDao = db.quotaSnapshotDao(),
            refreshAttemptDao = db.refreshAttemptDao(),
            currentQuotaStatePublisher = publisher,
            primaryQuotaWindowPreferenceReader = StaticPrimaryQuotaWindowPreferenceReader(primaryWindowId),
            clock = Clock.fixed(Instant.parse("2026-05-23T12:00:00Z"), ZoneOffset.UTC),
        )

        try {
            block(db, currentAccountReader, publisher, republisher)
        } finally {
            db.close()
        }
    }

    private class InMemoryCurrentAccountReader : CurrentAccountReader {
        var selection: CurrentAccountSelection? = null

        override suspend fun currentAccountSelection(): CurrentAccountSelection? = selection
    }

    private class RecordingCurrentQuotaStatePublisher : CurrentQuotaStatePublisher {
        val publishedStates = mutableListOf<CurrentQuotaState>()

        override suspend fun publish(state: CurrentQuotaState) {
            publishedStates += state
        }
    }

    private class StaticPrimaryQuotaWindowPreferenceReader(
        private val windowId: QuotaWindowId,
    ) : PrimaryQuotaWindowPreferenceReader {
        override suspend fun primaryQuotaWindowId(): QuotaWindowId = windowId
    }

    private fun account(
        displayName: String,
        avatarInitial: String,
    ) = ProviderAccountEntity(
        localAccountId = "local-1",
        providerId = "codex",
        providerAccountId = "acct-1",
        displayName = displayName,
        avatarInitial = avatarInitial,
        avatarColorKey = "local-1",
        status = "active",
        createdAt = Instant.parse("2026-05-23T11:00:00Z").toEpochMilli(),
        updatedAt = Instant.parse("2026-05-23T11:30:00Z").toEpochMilli(),
        lastSuccessfulRefreshAt = null,
    )

    private fun accountDomain(
        displayName: String,
        avatarInitial: String,
    ) = ProviderAccount(
        localAccountId = LocalAccountId("local-1"),
        providerId = ProviderId("codex"),
        providerAccountId = ProviderAccountId("acct-1"),
        displayName = displayName,
        avatarInitial = avatarInitial,
        avatarColorKey = "local-1",
        status = com.kmnexus.codexmeter.domain.model.AccountStatus.Active,
        createdAt = Instant.parse("2026-05-23T11:00:00Z"),
        updatedAt = Instant.parse("2026-05-23T11:30:00Z"),
        lastSuccessfulRefreshAt = null,
    )

    private fun snapshot() = QuotaSnapshotEntity(
        snapshotId = "snapshot-1",
        providerId = "codex",
        localAccountId = "local-1",
        providerAccountId = "acct-1",
        fetchedAt = Instant.parse("2026-05-23T12:00:00Z").toEpochMilli(),
        source = "manualRefresh",
        planType = "plus",
        windowsJson = """[{"windowId":"five_hour","titleKey":"quota_window_five_hour","usedPercent":62,"resetAt":1779555600000,"limitWindowSeconds":18000,"isPrimaryCandidate":true,"availability":"Available"},{"windowId":"weekly","titleKey":"quota_window_weekly","usedPercent":41,"resetAt":1780012800000,"limitWindowSeconds":604800,"isPrimaryCandidate":true,"availability":"Available"}]""",
        creditsJson = null,
        responseDigest = "safe-digest",
    )
}
