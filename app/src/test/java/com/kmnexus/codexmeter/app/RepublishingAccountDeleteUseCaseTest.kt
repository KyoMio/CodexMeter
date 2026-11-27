package com.kmnexus.codexmeter.app

import androidx.room.Room
import com.kmnexus.codexmeter.core.concurrency.VersionedStateGuard
import com.kmnexus.codexmeter.data.local.db.CodexMeterDatabase
import com.kmnexus.codexmeter.data.local.entity.ProviderAccountEntity
import com.kmnexus.codexmeter.data.preferences.CurrentAccountSelection
import com.kmnexus.codexmeter.data.preferences.CurrentAccountStore
import com.kmnexus.codexmeter.data.repository.AccountDeletionRepository
import com.kmnexus.codexmeter.data.secure.FakeSecureSessionStore
import com.kmnexus.codexmeter.domain.account.AccountDeleteUseCase
import com.kmnexus.codexmeter.domain.account.NoopDeletedAccountStateCleaner
import com.kmnexus.codexmeter.domain.model.LocalAccountId
import com.kmnexus.codexmeter.domain.model.ProviderId
import com.kmnexus.codexmeter.domain.quota.CurrentQuotaFreshness
import com.kmnexus.codexmeter.domain.quota.CurrentQuotaState
import com.kmnexus.codexmeter.domain.quota.CurrentQuotaStatus
import com.kmnexus.codexmeter.domain.settings.NotificationAccountSelection
import com.kmnexus.codexmeter.domain.settings.NotificationPreferenceStore
import com.kmnexus.codexmeter.domain.settings.NotificationPreferences
import com.kmnexus.codexmeter.refresh.CurrentQuotaStatePublisher
import com.kmnexus.codexmeter.ui.home.HomeCurrentQuotaStateLoader
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
// Robolectric SDK 36 currently requires Java 21, while this project is pinned to Java 17.
@Config(sdk = [35])
class RepublishingAccountDeleteUseCaseTest {
    private val clock = Clock.fixed(Instant.parse("2026-05-23T12:00:00Z"), ZoneOffset.UTC)

    @Test
    fun `deleting notification-bound account republishes fallback account state`() = runTest {
        val db = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            CodexMeterDatabase::class.java,
        ).build()
        try {
            db.providerAccountDao().upsert(accountEntity(providerId = "codex", localAccountId = "target"))
            db.providerAccountDao().upsert(accountEntity(providerId = "deepseek", localAccountId = "remaining"))
            val currentAccountStore = InMemoryCurrentAccountStore(
                CurrentAccountSelection(ProviderId("codex"), LocalAccountId("target")),
            )
            val notificationStore = InMemoryNotificationPreferenceStore(
                NotificationPreferences(
                    persistentNotificationAccount = NotificationAccountSelection(
                        providerId = ProviderId("codex"),
                        localAccountId = LocalAccountId("target"),
                    ),
                ),
            )
            val guard = VersionedStateGuard()
            val deletionRepository = AccountDeletionRepository(
                database = db,
                secureSessionStore = FakeSecureSessionStore(),
                currentAccountStore = currentAccountStore,
                deletedAccountStateCleaner = NoopDeletedAccountStateCleaner,
                notificationPreferenceStore = notificationStore,
                stateGuard = guard,
            )
            val stateRepository = CurrentQuotaStateRepository(
                currentAccountReader = currentAccountStore,
                providerAccountDao = db.providerAccountDao(),
                quotaSnapshotDao = db.quotaSnapshotDao(),
                refreshAttemptDao = db.refreshAttemptDao(),
                notificationPreferenceReader = notificationStore,
                clock = clock,
            )
            val publisher = RecordingCurrentQuotaStatePublisher()
            val useCase = RepublishingAccountDeleteUseCase(
                delegate = AccountDeleteUseCase { providerId, localAccountId ->
                    deletionRepository.deleteAccount(providerId, localAccountId)
                },
                currentQuotaStateLoader = stateRepository,
                currentQuotaStatePublisher = publisher,
                stateGuard = guard,
            )

            useCase.deleteAccount(ProviderId("codex"), LocalAccountId("target"))

            assertEquals(1, publisher.publishedStates.size)
            assertEquals(LocalAccountId("remaining"), publisher.publishedStates.single().account?.localAccountId)
            assertEquals(ProviderId("deepseek"), publisher.publishedStates.single().account?.providerId)
            assertEquals(
                NotificationAccountSelection(ProviderId("deepseek"), LocalAccountId("remaining")),
                notificationStore.stored.persistentNotificationAccount,
            )
        } finally {
            db.close()
        }
    }

    @Test
    fun `deleting last account republishes unauthenticated state`() = runTest {
        val db = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            CodexMeterDatabase::class.java,
        ).build()
        try {
            db.providerAccountDao().upsert(accountEntity(providerId = "codex", localAccountId = "target"))
            val currentAccountStore = InMemoryCurrentAccountStore(
                CurrentAccountSelection(ProviderId("codex"), LocalAccountId("target")),
            )
            val notificationStore = InMemoryNotificationPreferenceStore(
                NotificationPreferences(
                    persistentNotificationAccount = NotificationAccountSelection(
                        providerId = ProviderId("codex"),
                        localAccountId = LocalAccountId("target"),
                    ),
                ),
            )
            val guard = VersionedStateGuard()
            val deletionRepository = AccountDeletionRepository(
                database = db,
                secureSessionStore = FakeSecureSessionStore(),
                currentAccountStore = currentAccountStore,
                deletedAccountStateCleaner = NoopDeletedAccountStateCleaner,
                notificationPreferenceStore = notificationStore,
                stateGuard = guard,
            )
            val stateRepository = CurrentQuotaStateRepository(
                currentAccountReader = currentAccountStore,
                providerAccountDao = db.providerAccountDao(),
                quotaSnapshotDao = db.quotaSnapshotDao(),
                refreshAttemptDao = db.refreshAttemptDao(),
                notificationPreferenceReader = notificationStore,
                clock = clock,
            )
            val publisher = RecordingCurrentQuotaStatePublisher()
            val useCase = RepublishingAccountDeleteUseCase(
                delegate = AccountDeleteUseCase { providerId, localAccountId ->
                    deletionRepository.deleteAccount(providerId, localAccountId)
                },
                currentQuotaStateLoader = stateRepository,
                currentQuotaStatePublisher = publisher,
                stateGuard = guard,
            )

            useCase.deleteAccount(ProviderId("codex"), LocalAccountId("target"))

            assertEquals(1, publisher.publishedStates.size)
            assertEquals(CurrentQuotaStatus.Unauthenticated, publisher.publishedStates.single().status)
            assertNull(publisher.publishedStates.single().account)
            assertNull(notificationStore.stored.persistentNotificationAccount)
        } finally {
            db.close()
        }
    }

    @Test
    fun `republish failure does not roll back or fail account deletion`() = runTest {
        var deleted = false
        val useCase = RepublishingAccountDeleteUseCase(
            delegate = AccountDeleteUseCase { _, _ -> deleted = true },
            currentQuotaStateLoader = HomeCurrentQuotaStateLoader { unauthenticatedState() },
            currentQuotaStatePublisher = object : CurrentQuotaStatePublisher {
                override suspend fun publish(state: CurrentQuotaState) {
                    throw IllegalStateException("publish failed")
                }
            },
            stateGuard = VersionedStateGuard(),
        )

        useCase.deleteAccount(ProviderId("codex"), LocalAccountId("target"))

        assertTrue(deleted)
    }

    @Test
    fun `delegate deletion failure skips republish and propagates`() = runTest {
        val publisher = RecordingCurrentQuotaStatePublisher()
        val useCase = RepublishingAccountDeleteUseCase(
            delegate = AccountDeleteUseCase { _, _ -> throw IllegalStateException("delete failed") },
            currentQuotaStateLoader = HomeCurrentQuotaStateLoader { unauthenticatedState() },
            currentQuotaStatePublisher = publisher,
            stateGuard = VersionedStateGuard(),
        )

        val result = runCatching<Unit> {
            useCase.deleteAccount(ProviderId("codex"), LocalAccountId("target"))
        }

        assertEquals("delete failed", result.exceptionOrNull()?.message)
        assertTrue(publisher.publishedStates.isEmpty())
    }

    private fun accountEntity(providerId: String, localAccountId: String) = ProviderAccountEntity(
        localAccountId = localAccountId,
        providerId = providerId,
        providerAccountId = "provider-$localAccountId",
        displayName = localAccountId,
        avatarInitial = localAccountId.first().uppercase(),
        avatarColorKey = localAccountId,
        status = "active",
        createdAt = 1_779_408_000_000L,
        updatedAt = 1_779_408_000_000L,
        lastSuccessfulRefreshAt = null,
    )

    private fun unauthenticatedState() = CurrentQuotaState(
        status = CurrentQuotaStatus.Unauthenticated,
        freshness = CurrentQuotaFreshness.Unknown,
        account = null,
        snapshot = null,
        latestAttempt = null,
        primaryWindow = null,
        secondaryWindows = emptyList(),
        primaryWindowCanAlert = false,
        error = null,
    )

    private class InMemoryCurrentAccountStore(
        private var selection: CurrentAccountSelection?,
    ) : CurrentAccountStore {
        override suspend fun currentAccountSelection(): CurrentAccountSelection? = selection

        override suspend fun updateCurrentAccountSelection(selection: CurrentAccountSelection?) {
            this.selection = selection
        }
    }

    private class InMemoryNotificationPreferenceStore(
        initial: NotificationPreferences,
    ) : NotificationPreferenceStore {
        var stored: NotificationPreferences = initial
            private set

        override suspend fun notificationPreferences(): NotificationPreferences = stored

        override suspend fun updateNotificationPreferences(preferences: NotificationPreferences) {
            stored = preferences
        }
    }

    private class RecordingCurrentQuotaStatePublisher : CurrentQuotaStatePublisher {
        val publishedStates = mutableListOf<CurrentQuotaState>()

        override suspend fun publish(state: CurrentQuotaState) {
            publishedStates += state
        }
    }
}
