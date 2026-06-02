package com.kmnexus.codexmeter.app

import androidx.room.Room
import com.kmnexus.codexmeter.data.local.db.CodexMeterDatabase
import com.kmnexus.codexmeter.data.local.entity.ProviderAccountEntity
import com.kmnexus.codexmeter.data.preferences.CurrentAccountReader
import com.kmnexus.codexmeter.data.preferences.CurrentAccountSelection
import com.kmnexus.codexmeter.data.repository.RoomQuotaSnapshotStore
import com.kmnexus.codexmeter.data.repository.RoomRefreshAttemptStore
import com.kmnexus.codexmeter.domain.model.LocalAccountId
import com.kmnexus.codexmeter.domain.model.ProviderAccount
import com.kmnexus.codexmeter.domain.model.ProviderAccountId
import com.kmnexus.codexmeter.domain.model.ProviderId
import com.kmnexus.codexmeter.domain.model.QuotaWindowId
import com.kmnexus.codexmeter.domain.model.RefreshAttemptId
import com.kmnexus.codexmeter.domain.model.SnapshotId
import com.kmnexus.codexmeter.domain.quota.QuotaSnapshot
import com.kmnexus.codexmeter.domain.quota.QuotaSnapshotSource
import com.kmnexus.codexmeter.domain.quota.QuotaWindow
import com.kmnexus.codexmeter.domain.quota.QuotaWindowAvailability
import com.kmnexus.codexmeter.domain.refresh.RefreshTrigger
import com.kmnexus.codexmeter.refresh.AttemptIdProvider
import com.kmnexus.codexmeter.refresh.ProviderRefreshResult
import com.kmnexus.codexmeter.refresh.RefreshCoordinator
import com.kmnexus.codexmeter.refresh.RefreshProvider
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
// Robolectric SDK 36 currently requires Java 21, while this project is pinned to Java 17.
@Config(sdk = [35])
class HomeRefreshCoordinatorUseCaseTest {
    @Test
    fun `app open refresh routes through coordinator with app open trigger`() = runTest {
        withUseCase { db, currentAccountReader, provider, useCase ->
            db.providerAccountDao().upsert(account())
            currentAccountReader.selection = CurrentAccountSelection(ProviderId("codex"), LocalAccountId("local-1"))

            val state = useCase.refreshForAppOpen()

            assertEquals(listOf(RefreshTrigger.AppOpen), provider.triggers)
            assertEquals(62, state.primaryWindow?.usedPercent)
        }
    }

    @Test
    fun `manual refresh routes through coordinator with manual trigger`() = runTest {
        withUseCase { db, currentAccountReader, provider, useCase ->
            db.providerAccountDao().upsert(account())
            currentAccountReader.selection = CurrentAccountSelection(ProviderId("codex"), LocalAccountId("local-1"))

            useCase.refreshCurrentState()

            assertEquals(listOf(RefreshTrigger.Manual), provider.triggers)
        }
    }

    @Test
    fun `manual refresh propagates coordinator infrastructure exceptions`() = runTest {
        withUseCase { db, currentAccountReader, provider, useCase ->
            db.providerAccountDao().upsert(account())
            currentAccountReader.selection = CurrentAccountSelection(ProviderId("codex"), LocalAccountId("local-1"))
            provider.exceptionToThrow = IllegalStateException("store failed")

            try {
                useCase.refreshCurrentState()
                fail("Expected infrastructure exception to propagate")
            } catch (exception: IllegalStateException) {
                assertEquals("store failed", exception.message)
            }
        }
    }

    private suspend fun withUseCase(
        block: suspend (
            CodexMeterDatabase,
            InMemoryCurrentAccountReader,
            RecordingRefreshProvider,
            HomeRefreshCoordinatorUseCase,
        ) -> Unit,
    ) {
        val db = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            CodexMeterDatabase::class.java,
        ).build()
        val currentAccountReader = InMemoryCurrentAccountReader()
        val provider = RecordingRefreshProvider()
        val clock = Clock.fixed(Instant.parse("2026-05-23T12:00:00Z"), ZoneOffset.UTC)
        val refreshCoordinator = RefreshCoordinator(
            provider = provider,
            snapshotStore = RoomQuotaSnapshotStore(db.quotaSnapshotDao()),
            attemptStore = RoomRefreshAttemptStore(db.refreshAttemptDao()),
            attemptIdProvider = AttemptIdProvider { RefreshAttemptId("attempt-1") },
            clock = clock,
        )
        val currentQuotaStateRepository = CurrentQuotaStateRepository(
            currentAccountReader = currentAccountReader,
            providerAccountDao = db.providerAccountDao(),
            quotaSnapshotDao = db.quotaSnapshotDao(),
            refreshAttemptDao = db.refreshAttemptDao(),
            clock = clock,
        )
        val useCase = HomeRefreshCoordinatorUseCase(
            currentAccountStore = CurrentQuotaRefreshAccountStore(
                currentAccountReader = currentAccountReader,
                providerAccountDao = db.providerAccountDao(),
            ),
            refreshCoordinator = refreshCoordinator,
            currentQuotaStateLoader = currentQuotaStateRepository,
        )

        try {
            block(db, currentAccountReader, provider, useCase)
        } finally {
            db.close()
        }
    }

    private class InMemoryCurrentAccountReader : CurrentAccountReader {
        var selection: CurrentAccountSelection? = null

        override suspend fun currentAccountSelection(): CurrentAccountSelection? = selection
    }

    private inner class RecordingRefreshProvider : RefreshProvider {
        val triggers = mutableListOf<RefreshTrigger>()
        var exceptionToThrow: RuntimeException? = null

        override suspend fun refresh(account: ProviderAccount, trigger: RefreshTrigger): ProviderRefreshResult {
            triggers += trigger
            exceptionToThrow?.let { throw it }
            return ProviderRefreshResult.Success(snapshot())
        }
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

    private fun snapshot() = QuotaSnapshot(
        snapshotId = SnapshotId("snapshot-1"),
        providerId = ProviderId("codex"),
        localAccountId = LocalAccountId("local-1"),
        providerAccountId = ProviderAccountId("acct-1"),
        fetchedAt = Instant.parse("2026-05-23T12:00:00Z"),
        source = QuotaSnapshotSource.AppOpenRefresh,
        planType = "plus",
        windows = listOf(
            QuotaWindow(
                windowId = QuotaWindowId("five_hour"),
                titleKey = "quota_window_five_hour",
                usedPercent = 62,
                resetAt = Instant.parse("2026-05-23T17:00:00Z"),
                limitWindowSeconds = 18_000,
                isPrimaryCandidate = true,
                availability = QuotaWindowAvailability.Available,
            ),
        ),
        credits = null,
        responseDigest = "safe-digest",
    )
}
