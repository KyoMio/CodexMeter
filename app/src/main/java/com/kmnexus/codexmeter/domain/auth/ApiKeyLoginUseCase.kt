package com.kmnexus.codexmeter.domain.auth

import com.kmnexus.codexmeter.data.local.dao.ProviderAccountDao
import com.kmnexus.codexmeter.data.local.dao.QuotaSnapshotDao
import com.kmnexus.codexmeter.data.preferences.CurrentAccountPreferences
import com.kmnexus.codexmeter.data.preferences.CurrentAccountSelection
import com.kmnexus.codexmeter.data.repository.toEntity
import com.kmnexus.codexmeter.domain.model.LocalAccountId
import com.kmnexus.codexmeter.domain.model.ProviderAccount
import com.kmnexus.codexmeter.domain.model.ProviderId
import com.kmnexus.codexmeter.providers.SessionImportRouter
import java.time.Clock
import java.util.UUID

// CM-10 R4 note: the wired container uses SessionLoginUseCase for all imports (this class only
// remains as a legacy type). Envelope persistence lives in SessionLoginUseCase's boundary commit;
// this path does not write credentials to disk and should not be rewired without adopting the
// same ordering protocol.
class ApiKeyLoginUseCase(
    private val importRouter: SessionImportRouter,
    private val accountDao: ProviderAccountDao,
    private val snapshotDao: QuotaSnapshotDao,
    private val currentAccountStore: CurrentAccountPreferences,
    private val clock: Clock = Clock.systemUTC(),
    private val notificationTargetBoundary: com.kmnexus.codexmeter.notification.NotificationTargetBoundary =
        com.kmnexus.codexmeter.notification.NotificationTargetBoundary(),
) {
    suspend fun importApiKey(
        providerId: ProviderId,
        displayName: String,
        apiKey: String,
    ): Result<ProviderAccount> {
        val now = clock.instant()
        val localAccountId = LocalAccountId("${providerId.value}-${UUID.randomUUID()}")
        val account = ProviderAccount.createNew(
            localAccountId = localAccountId,
            providerId = providerId,
            providerAccountId = null,
            displayName = displayName,
            now = now,
        )

        val importResult = importRouter.importApiKey(apiKey, account)
        if (importResult.isFailure) {
            return Result.failure(
                importResult.exceptionOrNull()
                    ?: RuntimeException("Unknown import error"),
            )
        }
        val snapshot = importResult.getOrThrow().snapshot

        accountDao.upsert(account.toEntity())
        snapshotDao.insert(snapshot.toEntity())
        // CM-10 R2: API-key login selects the account — target moves; serialize against posts.
        notificationTargetBoundary.serialize {
            currentAccountStore.updateCurrentAccountSelection(
                CurrentAccountSelection(
                    providerId = providerId,
                    localAccountId = localAccountId,
                ),
            )
        }

        return Result.success(account)
    }
}
