package com.kmnexus.codexmeter.domain.auth

import androidx.room.withTransaction
import com.kmnexus.codexmeter.core.concurrency.VersionedStateGuard
import com.kmnexus.codexmeter.data.local.dao.ProviderAccountDao
import com.kmnexus.codexmeter.data.local.dao.QuotaSnapshotDao
import com.kmnexus.codexmeter.data.local.db.CodexMeterDatabase
import com.kmnexus.codexmeter.data.preferences.CurrentAccountSelection
import com.kmnexus.codexmeter.data.preferences.CurrentAccountStore
import com.kmnexus.codexmeter.data.secure.ProviderSessionEnvelope
import com.kmnexus.codexmeter.data.secure.SecureSessionStore
import com.kmnexus.codexmeter.data.repository.toDomain
import com.kmnexus.codexmeter.data.repository.toEntity
import com.kmnexus.codexmeter.domain.model.AccountStatus
import com.kmnexus.codexmeter.domain.model.LocalAccountId
import com.kmnexus.codexmeter.domain.model.ProviderAccount
import com.kmnexus.codexmeter.domain.model.ProviderId
import com.kmnexus.codexmeter.providers.SessionImportRouter
import com.kmnexus.codexmeter.providers.ValidatedSessionImport
import java.time.Clock
import java.util.UUID

/**
 * Validates a credential through the provider's usage API, then persists the resulting
 * account + first snapshot atomically.
 *
 * CM-10 R4 ordering protocol — same-account deletion vs late-arriving login writes:
 * the importer VALIDATES and encrypts the session envelope outside any lock but never writes it
 * (see [ValidatedSessionImport]). The commit is one critical section inside the shared
 * notification-target boundary — the same boundary [com.kmnexus.codexmeter.data.repository.AccountDeletionRepository]
 * serializes the destructive delete core through:
 *   1. re-check (inside the lock, atomic against deletion): for re-login the original row must
 *      still exist; a row deleted mid-validation means the late result is DISCARDED and NOTHING
 *      is written — resurrecting a deleted account row (or its credential file) is a real
 *      persistence corruption, not just a wrong notification.
 *   2. writes in order: credential envelope file → current-selection write (only when it moves) →
 *      Room row + snapshot transaction.
 *   3. compensation runs INSIDE the same critical section and reverses only this operation's own
 *      writes (previous states captured inside the lock). Because deletion is serialized through
 *      the same boundary, compensation can never overwrite a committed deletion.
 */
class SessionLoginUseCase(
    private val importRouter: SessionImportRouter,
    private val database: CodexMeterDatabase,
    private val accountDao: ProviderAccountDao,
    private val snapshotDao: QuotaSnapshotDao,
    private val currentAccountStore: CurrentAccountStore,
    private val stateGuard: VersionedStateGuard,
    private val clock: Clock = Clock.systemUTC(),
    private val notificationTargetBoundary: com.kmnexus.codexmeter.notification.NotificationTargetBoundary =
        com.kmnexus.codexmeter.notification.NotificationTargetBoundary(),
    private val secureSessionStore: SecureSessionStore? = null,
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
        importFn: suspend (ProviderAccount) -> Result<ValidatedSessionImport>,
    ): Result<ProviderAccount> {
        val existing = accountDao.getById(localAccountId.value)?.toDomain()
            ?: return Result.failure(IllegalStateException("Account not found for re-login: ${localAccountId.value}"))
        // Reuse identity/display name; clearing NeedsReauth is the whole point of re-login.
        val account = existing.copy(status = AccountStatus.Active, updatedAt = clock.instant())

        val importResult = importFn(account)
        if (importResult.isFailure) {
            // Validation failed before any envelope write, so the old session stays intact.
            return Result.failure(
                importResult.exceptionOrNull() ?: RuntimeException("Unknown import error"),
            )
        }
        val validated = importResult.getOrThrow()
        val snapshot = validated.snapshot
        val envelope = validated.sessionEnvelope

        stateGuard.beginEdit()
        try {
            return notificationTargetBoundary.serialize {
                val stillAlive = accountDao.getById(localAccountId.value)
                    ?.takeIf { !it.status.equals(AccountStatus.Deleted.name, ignoreCase = true) }
                if (stillAlive == null) {
                    // The delete won the race while the credential validated. Discard without
                    // writing anything: the credential file never touches disk.
                    Result.failure(
                        IllegalStateException(
                            "Re-login discarded: account ${localAccountId.value} was deleted while the credential was being validated",
                        ),
                    )
                } else {
                    commitWithCompensation(
                        envelope = envelope,
                        writeRows = {
                            database.withTransaction {
                                accountDao.upsert(account.toEntity())
                                snapshotDao.insert(snapshot.toEntity())
                            }
                        },
                        writeSelection = null,
                    )
                    Result.success(account)
                }
            }
        } finally {
            stateGuard.endEdit()
        }
    }

    private suspend fun importAndPersist(
        providerId: ProviderId,
        providerDisplayName: String,
        label: String?,
        importFn: suspend (ProviderAccount) -> Result<ValidatedSessionImport>,
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

        val importResult = importFn(account)
        if (importResult.isFailure) {
            return Result.failure(
                importResult.exceptionOrNull() ?: RuntimeException("Unknown import error"),
            )
        }
        val validated = importResult.getOrThrow()
        val snapshot = validated.snapshot
        val envelope = validated.sessionEnvelope

        stateGuard.beginEdit()
        try {
            return notificationTargetBoundary.serialize {
                // A fresh UUID row can never be "resurrecting" — it never existed. There is no
                // liveness re-check here because there is no prior account to delete; the whole
                // commit still runs inside the boundary so a same-time deletion of ANOTHER
                // account (which may move the fallback selection) cannot interleave mid-commit.
                val selectionNeedsWrite = currentAccountStore.currentAccountSelection() == null
                commitWithCompensation(
                    envelope = envelope,
                    writeRows = {
                        database.withTransaction {
                            accountDao.upsert(account.toEntity())
                            snapshotDao.insert(snapshot.toEntity())
                        }
                    },
                    writeSelection = if (selectionNeedsWrite) {
                        CurrentAccountSelection(
                            providerId = providerId,
                            localAccountId = localAccountId,
                        )
                    } else {
                        null
                    },
                )
                Result.success(account)
            }
        } finally {
            stateGuard.endEdit()
        }
    }

    /**
     * Commits one login write inside the boundary (the caller must already be serialized):
     * envelope file first, then the optional selection write, then Room rows. On failure,
     * reverses only the writes this operation owns, using previous states captured inside the
     * lock — compensation can never overwrite a deletion because deletion serializes through
     * the same boundary.
     *
     * The envelope write is skipped when no [SecureSessionStore] is wired (tests), matching the
     * pre-R4 behavior of importers owning persistence.
     */
    private suspend fun commitWithCompensation(
        envelope: ProviderSessionEnvelope,
        writeRows: suspend () -> Unit,
        writeSelection: CurrentAccountSelection?,
    ) {
        val store = secureSessionStore
        var previousEnvelope: ProviderSessionEnvelope? = null
        var envelopeWritten = false
        var selectionWritten = false
        val previousSelection = currentAccountStore.currentAccountSelection()

        try {
            if (store != null) {
                previousEnvelope = store.load(
                    providerId = envelope.providerId,
                    localAccountId = envelope.localAccountId,
                )
                store.save(envelope)
                envelopeWritten = true
            }
            if (writeSelection != null) {
                currentAccountStore.updateCurrentAccountSelection(writeSelection)
                selectionWritten = true
            }
            writeRows()
        } catch (throwable: Throwable) {
            if (selectionWritten) {
                runCatching { currentAccountStore.updateCurrentAccountSelection(previousSelection) }
            }
            if (envelopeWritten && store != null) {
                runCatching {
                    if (previousEnvelope == null) {
                        store.delete(
                            providerId = envelope.providerId,
                            localAccountId = envelope.localAccountId,
                        )
                    } else {
                        store.save(previousEnvelope)
                    }
                }
            }
            throw throwable
        }
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
