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

class ApiKeyLoginUseCase(
    private val importRouter: SessionImportRouter,
    private val accountDao: ProviderAccountDao,
    private val snapshotDao: QuotaSnapshotDao,
    private val currentAccountStore: CurrentAccountPreferences,
    private val clock: Clock = Clock.systemUTC(),
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

        val snapshotResult = importRouter.importApiKey(apiKey, account)
        if (snapshotResult.isFailure) {
            return Result.failure(
                snapshotResult.exceptionOrNull()
                    ?: RuntimeException("Unknown import error"),
            )
        }
        val snapshot = snapshotResult.getOrThrow()

        accountDao.upsert(account.toEntity())
        snapshotDao.insert(snapshot.toEntity())
        currentAccountStore.updateCurrentAccountSelection(
            CurrentAccountSelection(
                providerId = providerId,
                localAccountId = localAccountId,
            ),
        )

        return Result.success(account)
    }
}
