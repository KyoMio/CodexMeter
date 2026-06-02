package com.kmnexus.codexmeter.app

import androidx.room.Room
import com.kmnexus.codexmeter.data.local.db.CodexMeterDatabase
import com.kmnexus.codexmeter.data.local.entity.ProviderAccountEntity
import com.kmnexus.codexmeter.data.local.entity.RefreshAttemptEntity
import com.kmnexus.codexmeter.data.preferences.CurrentAccountReader
import com.kmnexus.codexmeter.data.preferences.CurrentAccountSelection
import com.kmnexus.codexmeter.domain.model.LocalAccountId
import com.kmnexus.codexmeter.domain.model.ProviderId
import com.kmnexus.codexmeter.ui.settings.SettingsBackgroundRefreshStatus
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
class SettingsBackgroundRefreshStatusRepositoryTest {
    @Test
    fun `latest background refresh status ignores newer manual attempts`() = runTest {
        withRepository { db, currentAccountReader, repository ->
            db.providerAccountDao().upsert(account())
            currentAccountReader.selection = CurrentAccountSelection(ProviderId("codex"), LocalAccountId("local-1"))
            db.refreshAttemptDao().insert(
                attempt(id = "periodic", trigger = "periodic", status = "success", startedAt = "2026-05-24T09:00:00Z"),
            )
            db.refreshAttemptDao().insert(
                attempt(id = "manual", trigger = "manual", status = "failed", startedAt = "2026-05-24T09:10:00Z"),
            )

            assertEquals(SettingsBackgroundRefreshStatus.Success, repository.latestBackgroundRefreshStatus())
        }
    }

    @Test
    fun `missing background refresh attempt reports no attempts`() = runTest {
        withRepository { db, currentAccountReader, repository ->
            db.providerAccountDao().upsert(account())
            currentAccountReader.selection = CurrentAccountSelection(ProviderId("codex"), LocalAccountId("local-1"))

            assertEquals(SettingsBackgroundRefreshStatus.NoAttempts, repository.latestBackgroundRefreshStatus())
        }
    }

    @Test
    fun `retryable background refresh failure reports retrying instead of terminal failure`() = runTest {
        withRepository { db, currentAccountReader, repository ->
            db.providerAccountDao().upsert(account())
            currentAccountReader.selection = CurrentAccountSelection(ProviderId("codex"), LocalAccountId("local-1"))
            db.refreshAttemptDao().insert(
                attempt(
                    id = "periodic-retryable",
                    trigger = "periodic",
                    status = "failed",
                    startedAt = "2026-05-24T09:00:00Z",
                    retryable = true,
                    userActionRequired = false,
                ),
            )

            assertEquals(SettingsBackgroundRefreshStatus.Retrying, repository.latestBackgroundRefreshStatus())
        }
    }

    @Test
    fun `terminal background refresh failure still reports failed`() = runTest {
        withRepository { db, currentAccountReader, repository ->
            db.providerAccountDao().upsert(account())
            currentAccountReader.selection = CurrentAccountSelection(ProviderId("codex"), LocalAccountId("local-1"))
            db.refreshAttemptDao().insert(
                attempt(
                    id = "periodic-auth",
                    trigger = "periodic",
                    status = "failed",
                    startedAt = "2026-05-24T09:00:00Z",
                    retryable = false,
                    userActionRequired = true,
                ),
            )

            assertEquals(SettingsBackgroundRefreshStatus.Failed, repository.latestBackgroundRefreshStatus())
        }
    }

    private suspend fun withRepository(
        block: suspend (CodexMeterDatabase, InMemoryCurrentAccountReader, SettingsBackgroundRefreshStatusRepository) -> Unit,
    ) {
        val db = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            CodexMeterDatabase::class.java,
        ).build()
        val currentAccountReader = InMemoryCurrentAccountReader()
        val repository = SettingsBackgroundRefreshStatusRepository(
            currentAccountReader = currentAccountReader,
            providerAccountDao = db.providerAccountDao(),
            refreshAttemptDao = db.refreshAttemptDao(),
        )

        try {
            block(db, currentAccountReader, repository)
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

    private fun attempt(
        id: String,
        trigger: String,
        status: String,
        startedAt: String,
        retryable: Boolean? = null,
        userActionRequired: Boolean? = null,
    ) = RefreshAttemptEntity(
        attemptId = "attempt-$id",
        providerId = "codex",
        localAccountId = "local-1",
        trigger = trigger,
        startedAt = Instant.parse(startedAt).toEpochMilli(),
        finishedAt = Instant.parse(startedAt).plusSeconds(5).toEpochMilli(),
        status = status,
        errorCode = null,
        httpStatus = null,
        retryable = retryable,
        userActionRequired = userActionRequired,
        diagnosticsDigest = null,
    )
}
