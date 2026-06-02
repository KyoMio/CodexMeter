package com.kmnexus.codexmeter.domain.auth

import androidx.room.withTransaction
import com.kmnexus.codexmeter.data.local.dao.ProviderAccountDao
import com.kmnexus.codexmeter.data.local.dao.QuotaSnapshotDao
import com.kmnexus.codexmeter.data.local.db.CodexMeterDatabase
import com.kmnexus.codexmeter.data.preferences.CurrentAccountSelection
import com.kmnexus.codexmeter.data.preferences.CurrentAccountStore
import com.kmnexus.codexmeter.data.repository.toDomain
import com.kmnexus.codexmeter.data.repository.toEntity
import com.kmnexus.codexmeter.domain.model.AccountStatus
import com.kmnexus.codexmeter.domain.model.LocalAccountId
import com.kmnexus.codexmeter.domain.model.ProviderAccount
import com.kmnexus.codexmeter.domain.model.ProviderId
import com.kmnexus.codexmeter.providers.SessionImportRouter
import java.time.Clock
import java.util.UUID

/**
 * Validates a non-Codex credential through the provider's usage API, then persists the resulting
 * account + first snapshot atomically.
 *
 * Persistence ordering matters for the security/durability invariant: the provider [SessionImporter]
 * encrypts and writes the session envelope only after the credential validates, and the account row +
 * first snapshot are committed in a single Room transaction so a partial failure cannot leave a
 * half-imported account visible in the UI.
 */
class SessionLoginUseCase(
    private val importRouter: SessionImportRouter,
    private val database: CodexMeterDatabase,
    private val accountDao: ProviderAccountDao,
    private val snapshotDao: QuotaSnapshotDao,
    private val currentAccountStore: CurrentAccountStore,
    private val clock: Clock = Clock.systemUTC(),
) {
    suspend fun importApiKey(
        providerId: ProviderId,
        providerDisplayName: String,
        apiKey: String,
        label: String? = null,
        apiBaseUrl: String? = null,
    ): Result<ProviderAccount> = importAndPersist(providerId, providerDisplayName, label) { account ->
        importRouter.importApiKey(apiKey, account, apiBaseUrl)
    }

    suspend fun importCookie(
        providerId: ProviderId,
        providerDisplayName: String,
        cookieValue: String,
        label: String? = null,
    ): Result<ProviderAccount> = importAndPersist(providerId, providerDisplayName, label) { account ->
        importRouter.importCookie(cookieValue, account)
    }

    suspend fun importOAuthPkce(
        providerId: ProviderId,
        providerDisplayName: String,
        code: String,
        verifier: String,
        redirectUri: String,
        label: String? = null,
    ): Result<ProviderAccount> = importAndPersist(providerId, providerDisplayName, label) { account ->
        importRouter.importOAuthPkce(code, verifier, redirectUri, account)
    }

    /**
     * Re-authenticates an EXISTING account: validates the freshly captured credential, then overwrites
     * that account's session in place — same [localAccountId], display name and creation time — instead
     * of creating a duplicate. Non-Codex providers carry no account identity, so the new credential is
     * trusted to belong to the same account; the caller decides which account to re-auth.
     */
    suspend fun reloginApiKey(
        localAccountId: LocalAccountId,
        apiKey: String,
        apiBaseUrl: String? = null,
    ): Result<ProviderAccount> = reloginAndPersist(localAccountId) { account ->
        importRouter.importApiKey(apiKey, account, apiBaseUrl)
    }

    suspend fun reloginCookie(
        localAccountId: LocalAccountId,
        cookieValue: String,
    ): Result<ProviderAccount> = reloginAndPersist(localAccountId) { account ->
        importRouter.importCookie(cookieValue, account)
    }

    suspend fun reloginOAuthPkce(
        localAccountId: LocalAccountId,
        code: String,
        verifier: String,
        redirectUri: String,
    ): Result<ProviderAccount> = reloginAndPersist(localAccountId) { account ->
        importRouter.importOAuthPkce(code, verifier, redirectUri, account)
    }

    private suspend fun reloginAndPersist(
        localAccountId: LocalAccountId,
        importFn: suspend (ProviderAccount) -> Result<com.kmnexus.codexmeter.domain.quota.QuotaSnapshot>,
    ): Result<ProviderAccount> {
        val existing = accountDao.getById(localAccountId.value)?.toDomain()
            ?: return Result.failure(IllegalStateException("Account not found for re-login: ${localAccountId.value}"))
        // Reuse identity/display name; clearing NeedsReauth is the whole point of re-login.
        val account = existing.copy(status = AccountStatus.Active, updatedAt = clock.instant())

        val snapshotResult = importFn(account)
        if (snapshotResult.isFailure) {
            // Validation failed before any envelope write, so the old session stays intact.
            return Result.failure(
                snapshotResult.exceptionOrNull() ?: RuntimeException("Unknown import error"),
            )
        }
        val snapshot = snapshotResult.getOrThrow()

        database.withTransaction {
            accountDao.upsert(account.toEntity())
            snapshotDao.insert(snapshot.toEntity())
        }
        // Re-login never changes which account is current.
        return Result.success(account)
    }

    private suspend fun importAndPersist(
        providerId: ProviderId,
        providerDisplayName: String,
        label: String?,
        importFn: suspend (ProviderAccount) -> Result<com.kmnexus.codexmeter.domain.quota.QuotaSnapshot>,
    ): Result<ProviderAccount> {
        val now = clock.instant()
        val localAccountId = LocalAccountId("${providerId.value}-${UUID.randomUUID()}")
        val account = ProviderAccount.createNew(
            localAccountId = localAccountId,
            providerId = providerId,
            providerAccountId = null,
            displayName = resolveDisplayName(providerId, providerDisplayName, label),
            now = now,
        )

        val snapshotResult = importFn(account)
        if (snapshotResult.isFailure) {
            return Result.failure(
                snapshotResult.exceptionOrNull() ?: RuntimeException("Unknown import error"),
            )
        }
        val snapshot = snapshotResult.getOrThrow()

        database.withTransaction {
            accountDao.upsert(account.toEntity())
            snapshotDao.insert(snapshot.toEntity())
        }
        // Only the first connected account becomes current; later additions never steal the
        // user's selected current account.
        if (currentAccountStore.currentAccountSelection() == null) {
            currentAccountStore.updateCurrentAccountSelection(
                CurrentAccountSelection(
                    providerId = providerId,
                    localAccountId = localAccountId,
                ),
            )
        }

        return Result.success(account)
    }

    /**
     * API-key / cookie providers carry no account identity, so we auto-number duplicates:
     * "DeepSeek", "DeepSeek #2", … A user-supplied [label] always wins when present.
     */
    private suspend fun resolveDisplayName(
        providerId: ProviderId,
        providerDisplayName: String,
        label: String?,
    ): String {
        val trimmedLabel = label?.trim()
        if (!trimmedLabel.isNullOrEmpty()) {
            return trimmedLabel
        }
        val base = providerDisplayName.trim().ifEmpty { providerId.value }
        val existingCount = accountDao.listByProvider(providerId.value).size
        return if (existingCount == 0) base else "$base #${existingCount + 1}"
    }
}
