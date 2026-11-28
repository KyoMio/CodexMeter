package com.kmnexus.codexmeter.data.repository

import androidx.room.withTransaction
import com.kmnexus.codexmeter.core.concurrency.VersionedStateGuard
import com.kmnexus.codexmeter.data.local.dao.ProviderAccountDao
import com.kmnexus.codexmeter.data.local.dao.QuotaSnapshotDao
import com.kmnexus.codexmeter.data.local.dao.RefreshAttemptDao
import com.kmnexus.codexmeter.data.local.db.CodexMeterDatabase
import com.kmnexus.codexmeter.data.local.entity.ProviderAccountEntity
import com.kmnexus.codexmeter.data.local.entity.QuotaSnapshotEntity
import com.kmnexus.codexmeter.data.local.entity.RefreshAttemptEntity
import com.kmnexus.codexmeter.data.preferences.CurrentAccountStore
import com.kmnexus.codexmeter.data.preferences.CurrentAccountSelection
import com.kmnexus.codexmeter.data.secure.ProviderSessionEnvelope
import com.kmnexus.codexmeter.data.secure.SecureSessionStore
import com.kmnexus.codexmeter.domain.model.AccountStatus
import com.kmnexus.codexmeter.domain.model.LocalAccountId
import com.kmnexus.codexmeter.domain.model.ProviderAccount
import com.kmnexus.codexmeter.domain.model.ProviderAccountId
import com.kmnexus.codexmeter.domain.model.ProviderId
import com.kmnexus.codexmeter.domain.model.QuotaWindowId
import com.kmnexus.codexmeter.domain.model.RefreshAttemptId
import com.kmnexus.codexmeter.domain.model.SnapshotId
import com.kmnexus.codexmeter.domain.quota.Credits
import com.kmnexus.codexmeter.domain.quota.QuotaSnapshot
import com.kmnexus.codexmeter.domain.quota.QuotaSnapshotSource
import com.kmnexus.codexmeter.domain.quota.QuotaModelBucket
import com.kmnexus.codexmeter.domain.quota.QuotaWindow
import com.kmnexus.codexmeter.domain.quota.QuotaWindowAvailability
import com.kmnexus.codexmeter.domain.quota.QuotaWindowDisplayKind
import com.kmnexus.codexmeter.domain.refresh.RefreshAttempt
import com.kmnexus.codexmeter.domain.refresh.RefreshAttemptStatus
import com.kmnexus.codexmeter.domain.refresh.RefreshTrigger
import com.kmnexus.codexmeter.providers.codex.auth.CodexSessionImporter
import com.kmnexus.codexmeter.refresh.RefreshAccountStatusStore
import com.kmnexus.codexmeter.refresh.RefreshAttemptStore
import com.kmnexus.codexmeter.refresh.SnapshotStore
import java.time.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class RoomQuotaSnapshotStore(
    private val quotaSnapshotDao: QuotaSnapshotDao,
) : SnapshotStore {
    override suspend fun save(snapshot: QuotaSnapshot) {
        quotaSnapshotDao.insert(snapshot.toEntity())
    }

    override suspend fun latestFor(account: ProviderAccount): QuotaSnapshot? =
        quotaSnapshotDao.getLatestForAccount(
            providerId = account.providerId.value,
            localAccountId = account.localAccountId.value,
        )?.toDomain()
}

class RoomRefreshAttemptStore(
    private val refreshAttemptDao: RefreshAttemptDao,
) : RefreshAttemptStore {
    override suspend fun save(attempt: RefreshAttempt) {
        refreshAttemptDao.insert(attempt.toEntity())
    }

    override suspend fun latestFor(account: ProviderAccount): RefreshAttempt? =
        refreshAttemptDao.getLatestForAccount(
            providerId = account.providerId.value,
            localAccountId = account.localAccountId.value,
        )?.toDomain()
}

class RoomRefreshAccountStatusStore(
    private val providerAccountDao: ProviderAccountDao,
) : RefreshAccountStatusStore {
    override suspend fun existsAndNotDeleted(account: ProviderAccount): Boolean {
        val row = providerAccountDao.getById(account.localAccountId.value) ?: return false
        return row.providerId == account.providerId.value &&
            row.status.lowercase() != AccountStatus.Deleted.storageName()
    }

    override suspend fun markNeedsReauth(account: ProviderAccount, updatedAt: Instant) {
        providerAccountDao.updateStatus(
            providerId = account.providerId.value,
            localAccountId = account.localAccountId.value,
            status = AccountStatus.NeedsReauth.storageName(),
            updatedAt = updatedAt.toEpochMilli(),
        )
    }

    override suspend fun markActive(account: ProviderAccount, updatedAt: Instant) {
        providerAccountDao.updateStatus(
            providerId = account.providerId.value,
            localAccountId = account.localAccountId.value,
            status = AccountStatus.Active.storageName(),
            updatedAt = updatedAt.toEpochMilli(),
        )
    }
}

class RoomCodexSessionImportPersistence(
    private val database: CodexMeterDatabase,
    private val sessionStore: SecureSessionStore,
    private val currentAccountStore: CurrentAccountStore,
    private val stateGuard: VersionedStateGuard,
    private val beforeRoomCommit: suspend () -> Unit = {},
    private val notificationTargetBoundary: com.kmnexus.codexmeter.notification.NotificationTargetBoundary =
        com.kmnexus.codexmeter.notification.NotificationTargetBoundary(),
) : CodexSessionImporter.ImportPersistence {
    override suspend fun save(
        account: ProviderAccount,
        sessionEnvelope: ProviderSessionEnvelope,
        snapshot: QuotaSnapshot,
        allowNewRow: Boolean,
        expectedLocalAccountId: LocalAccountId?,
    ): CodexSessionImporter.CommittedImport {
        stateGuard.beginEdit()
        try {
            return saveGuarded(
                account = account,
                sessionEnvelope = sessionEnvelope,
                snapshot = snapshot,
                allowNewRow = allowNewRow,
                expectedLocalAccountId = expectedLocalAccountId,
            )
        } finally {
            stateGuard.endEdit()
        }
    }

    /**
     * CM-10 R4/R5 ordering protocol — same-account deletion vs late-arriving import writes.
     *
     *  1. STAGE (outside the lock): the encrypted envelope is written under a random,
     *     single-use staging key — never under the account's real credential key. The real
     *     credential file is only written inside the boundary in step 3, after the final local
     *     id has been verified, so a cancelled or discarded login leaves nothing behind and
     *     compensation only ever touches writes this operation itself owns. Staging must stay
     *     outside the boundary because a deletion has to be able to complete while the writer
     *     is suspended inside its own save.
     *  2. DECIDE (inside the lock, one consistent read): re-read the identity match atomically
     *     against deletion. [allowNewRow] carries the re-login intent:
     *      - re-login (allowNewRow=false): the matched row must still exist; gone → DISCARD.
     *      - [expectedLocalAccountId] binds the re-login to the original account generation:
     *        a row re-added under a NEW local id after deletion is not the original row →
     *        DISCARD, never overwrite the replacement's credential.
     *      - add (allowNewRow=true): a row matched at stage-time but gone now means the account
     *        was deleted mid-validation → DISCARD; never seen at either read → genuine insert.
     *  3. COMMIT+COMPENSATE (inside the same critical section): consume the staging key,
     *     capture the previous credential at the final id, write the real credential file,
     *     write the selection, then the Room row + snapshot. Boundary acquisition stays
     *     cancellable (a cancelled login must stop), but every exit — failure or cancellation —
     *     funnels into a cancellation-proof compensation that completes fully. Compensation is
     *     ownership-guarded by content: it deletes or restores the credential file only when
     *     the file still holds exactly what this operation wrote — a concurrent committed
     *     import that superseded it is never undone.
     */
    private suspend fun saveGuarded(
        account: ProviderAccount,
        sessionEnvelope: ProviderSessionEnvelope,
        snapshot: QuotaSnapshot,
        allowNewRow: Boolean,
        expectedLocalAccountId: LocalAccountId?,
    ): CodexSessionImporter.CommittedImport {
        val preMatch = findProviderAccount(account)
        val staged = stageEnvelope(account, sessionEnvelope, preMatch)
        val state = GuardedCommitState(consumedStagingKey = staged.stagingKey)
        // Cancellation contract (CM-10 R5): acquiring the boundary stays cancellable — a login
        // cancelled while queued must actually stop. Compensation, however, must always complete,
        // so any failure or cancellation anywhere in the commit pipeline funnels into a
        // NonCancellable cleanup that reverses only the writes this operation itself owns.
        return try {
            val request = GuardedCommitRequest(
                account = account,
                snapshot = snapshot,
                allowNewRow = allowNewRow,
                expectedLocalAccountId = expectedLocalAccountId,
            )
            notificationTargetBoundary.serialize {
                commitInsideBoundary(
                    request = request,
                    preMatch = preMatch,
                    staged = staged,
                    state = state,
                )
            }
        } catch (throwable: Throwable) {
            // Reached when the boundary entry or any stage above was cancelled or failed before
            // its own compensation completed. Cleanup is cancellation-proof and only ever
            // reverses writes owned by this operation.
            cleanupOuterFailure(staged = staged, state = state)
            throw throwable
        }
    }

    private suspend fun findProviderAccount(account: ProviderAccount) =
        account.providerAccountId?.let {
            database.providerAccountDao().getByProviderAccountId(
                providerId = account.providerId.value,
                providerAccountId = it.value,
            )
        }

    private suspend fun stageEnvelope(
        account: ProviderAccount,
        sessionEnvelope: ProviderSessionEnvelope,
        preMatch: com.kmnexus.codexmeter.data.local.entity.ProviderAccountEntity?,
    ): StagedImport {
        // ---- STAGE (outside the boundary): random single-use key, never the real credential key ----
        val stagedAccount = canonicalAccount(account, preMatch?.toDomain())
        val stagedEnvelope = sessionEnvelope.copy(
            localAccountId = stagedAccount.localAccountId.value,
            providerAccountId = stagedAccount.providerAccountId?.value,
        )
        val stagingKey = "staged-${java.util.UUID.randomUUID()}"
        val stagingEnvelope = stagedEnvelope.copy(localAccountId = stagingKey)
        try {
            sessionStore.save(stagingEnvelope)
        } catch (throwable: Throwable) {
            // Cancelled (or failed) mid-write: clean the possibly partial staging file. Cleanup
            // must complete even under cancellation.
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                runCatching {
                    sessionStore.delete(
                        providerId = stagingEnvelope.providerId,
                        localAccountId = stagingKey,
                    )
                }
            }
            throw throwable
        }
        return StagedImport(
            stagedAccount = stagedAccount,
            stagedEnvelope = stagedEnvelope,
            stagingKey = stagingKey,
            stagingEnvelope = stagingEnvelope,
        )
    }

    private suspend fun commitInsideBoundary(
        request: GuardedCommitRequest,
        preMatch: com.kmnexus.codexmeter.data.local.entity.ProviderAccountEntity?,
        staged: StagedImport,
        state: GuardedCommitState,
    ): CodexSessionImporter.CommittedImport {
        val account = request.account
        // ---- DECIDE (one consistent read inside the lock) ----
        val nowMatch = findProviderAccount(account)
            ?.takeIf { !it.status.equals(AccountStatus.Deleted.name, ignoreCase = true) }
        if (shouldDiscardCommit(preMatch, nowMatch, request.allowNewRow, request.expectedLocalAccountId)) {
            // Remove ONLY this operation's staging write. Never restore anything — the
            // account is deleted (or superseded); restoring old state would resurrect it.
            runCatching {
                sessionStore.delete(
                    providerId = staged.stagingEnvelope.providerId,
                    localAccountId = staged.stagingKey,
                )
            }
            state.consumedStagingKey = null
            error(
                "Import discarded: account ${staged.stagedAccount.localAccountId.value} " +
                    "was deleted while the credential was being validated",
            )
        }

        val committedAccount = canonicalAccount(account, nowMatch?.toDomain())
        val committedEnvelope = staged.stagedEnvelope.copy(
            localAccountId = committedAccount.localAccountId.value,
            providerAccountId = committedAccount.providerAccountId?.value,
        )
        val committed = CodexSessionImporter.CommittedImport(
            account = committedAccount,
            sessionEnvelope = committedEnvelope,
            snapshot = request.snapshot.copyForAccount(committedAccount),
        )

        // Consume the staging write, then capture the previous credential AT THE FINAL
        // KEY inside the lock — the only correct compensation baseline. Because the
        // real file is written here for the first time, nothing outside the lock ever
        // aliases the live credential key.
        runCatching {
            sessionStore.delete(
                providerId = staged.stagingEnvelope.providerId,
                localAccountId = staged.stagingKey,
            )
        }
        state.consumedStagingKey = null
        val previousEnvelopeAtFinalKey = sessionStore.load(
            providerId = committedEnvelope.providerId,
            localAccountId = committedEnvelope.localAccountId,
        )
        sessionStore.save(committedEnvelope)
        state.envelopeFileWritten = true
        state.committedForCompensation = committed
        state.previousEnvelopeForCompensation = previousEnvelopeAtFinalKey
        state.compensationBaselineValid = true

        commitSelectionAndRows(
            committed = committed,
            previousEnvelopeAtFinalKey = previousEnvelopeAtFinalKey,
            state = state,
        )
        return committed
    }

    private fun shouldDiscardCommit(
        preMatch: com.kmnexus.codexmeter.data.local.entity.ProviderAccountEntity?,
        nowMatch: com.kmnexus.codexmeter.data.local.entity.ProviderAccountEntity?,
        allowNewRow: Boolean,
        expectedLocalAccountId: LocalAccountId?,
    ): Boolean {
        val matchLostInFlight = preMatch != null &&
            (nowMatch == null || nowMatch.localAccountId != preMatch.localAccountId)
        val generationLost = expectedLocalAccountId != null &&
            nowMatch?.localAccountId != expectedLocalAccountId.value
        return generationLost || (!allowNewRow && nowMatch == null) || (allowNewRow && matchLostInFlight)
    }

    private suspend fun commitSelectionAndRows(
        committed: CodexSessionImporter.CommittedImport,
        previousEnvelopeAtFinalKey: ProviderSessionEnvelope?,
        state: GuardedCommitState,
    ) {
        val previousSelection = currentAccountStore.currentAccountSelection()
        var selectionWritten = false
        try {
            currentAccountStore.updateCurrentAccountSelection(
                CurrentAccountSelection(
                    providerId = committed.account.providerId,
                    localAccountId = committed.account.localAccountId,
                ),
            )
            selectionWritten = true
            database.withTransaction {
                database.providerAccountDao().upsert(committed.account.toEntity())
                beforeRoomCommit()
                database.quotaSnapshotDao().insert(committed.snapshot.toEntity())
            }
        } catch (throwable: Throwable) {
            if (selectionWritten) {
                runCatching {
                    currentAccountStore.updateCurrentAccountSelection(previousSelection)
                }
            }
            compensateOwnedEnvelopeWrite(
                committedEnvelope = committed.sessionEnvelope,
                previousEnvelopeAtFinalKey = previousEnvelopeAtFinalKey,
            )
            state.envelopeFileWritten = false
            throw throwable
        }
    }

    private suspend fun cleanupOuterFailure(
        staged: StagedImport,
        state: GuardedCommitState,
    ) {
        kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
            state.consumedStagingKey?.let { key ->
                runCatching {
                    sessionStore.delete(
                        providerId = staged.stagingEnvelope.providerId,
                        localAccountId = key,
                    )
                }
            }
            if (state.envelopeFileWritten && state.compensationBaselineValid) {
                compensateOwnedEnvelopeWrite(
                    committedEnvelope = state.committedForCompensation!!.sessionEnvelope,
                    previousEnvelopeAtFinalKey = state.previousEnvelopeForCompensation,
                )
            }
        }
    }

    private class GuardedCommitRequest(
        val account: ProviderAccount,
        val snapshot: QuotaSnapshot,
        val allowNewRow: Boolean,
        val expectedLocalAccountId: LocalAccountId?,
    )

    private class StagedImport(
        val stagedAccount: ProviderAccount,
        val stagedEnvelope: ProviderSessionEnvelope,
        val stagingKey: String,
        val stagingEnvelope: ProviderSessionEnvelope,
    )

    private class GuardedCommitState(
        var envelopeFileWritten: Boolean = false,
        var consumedStagingKey: String?,
        var committedForCompensation: CodexSessionImporter.CommittedImport? = null,
        var previousEnvelopeForCompensation: ProviderSessionEnvelope? = null,
        var compensationBaselineValid: Boolean = false,
    )

    /**
     * Ownership-guarded credential compensation: act only while the file still holds exactly
     * what THIS operation wrote. A concurrent import that committed over it owns the file now —
     * undoing it would destroy a successful write (CM-10 R5 merge case).
     */
    private suspend fun compensateOwnedEnvelopeWrite(
        committedEnvelope: ProviderSessionEnvelope,
        previousEnvelopeAtFinalKey: ProviderSessionEnvelope?,
    ) {
        runCatching {
            val current = sessionStore.load(
                providerId = committedEnvelope.providerId,
                localAccountId = committedEnvelope.localAccountId,
            )
            if (current != null && envelopeContentEquals(current, committedEnvelope)) {
                if (previousEnvelopeAtFinalKey == null) {
                    sessionStore.delete(
                        providerId = committedEnvelope.providerId,
                        localAccountId = committedEnvelope.localAccountId,
                    )
                } else {
                    sessionStore.save(previousEnvelopeAtFinalKey)
                }
            }
        }
    }

    private fun canonicalAccount(
        account: ProviderAccount,
        existingAccount: ProviderAccount?,
    ): ProviderAccount =
        if (existingAccount == null) {
            account
        } else {
            existingAccount.copy(
                status = account.status,
                updatedAt = account.updatedAt,
            )
        }
}

private fun envelopeContentEquals(
    a: ProviderSessionEnvelope,
    b: ProviderSessionEnvelope,
): Boolean =
    a.providerId == b.providerId &&
        a.localAccountId == b.localAccountId &&
        a.providerAccountId == b.providerAccountId &&
        a.schemaVersion == b.schemaVersion &&
        a.payloadCiphertext.contentEquals(b.payloadCiphertext) &&
        a.payloadNonce.contentEquals(b.payloadNonce)

fun ProviderAccount.toEntity(): ProviderAccountEntity =
    ProviderAccountEntity(
        localAccountId = localAccountId.value,
        providerId = providerId.value,
        providerAccountId = providerAccountId?.value,
        displayName = displayName,
        avatarInitial = avatarInitial,
        avatarColorKey = avatarColorKey,
        status = status.storageName(),
        createdAt = createdAt.toEpochMilli(),
        updatedAt = updatedAt.toEpochMilli(),
        lastSuccessfulRefreshAt = lastSuccessfulRefreshAt?.toEpochMilli(),
    )

fun ProviderAccountEntity.toDomain(): ProviderAccount =
    ProviderAccount(
        localAccountId = LocalAccountId(localAccountId),
        providerId = ProviderId(providerId),
        providerAccountId = providerAccountId?.let(::ProviderAccountId),
        displayName = displayName,
        avatarInitial = avatarInitial,
        avatarColorKey = avatarColorKey,
        status = status.toAccountStatus(),
        createdAt = Instant.ofEpochMilli(createdAt),
        updatedAt = Instant.ofEpochMilli(updatedAt),
        lastSuccessfulRefreshAt = lastSuccessfulRefreshAt?.let(Instant::ofEpochMilli),
    )

fun QuotaSnapshot.toEntity(): QuotaSnapshotEntity =
    QuotaSnapshotEntity(
        snapshotId = snapshotId.value,
        providerId = providerId.value,
        localAccountId = localAccountId.value,
        providerAccountId = providerAccountId?.value,
        fetchedAt = fetchedAt.toEpochMilli(),
        source = source.storageName(),
        planType = planType,
        windowsJson = roomQuotaJson.encodeToString(windows.map { it.toStoredWindow() }),
        creditsJson = credits?.let { roomQuotaJson.encodeToString(it.toStoredCredits()) },
        responseDigest = responseDigest,
    )

fun QuotaSnapshotEntity.toDomain(): QuotaSnapshot =
    QuotaSnapshot(
        snapshotId = SnapshotId(snapshotId),
        providerId = ProviderId(providerId),
        localAccountId = LocalAccountId(localAccountId),
        providerAccountId = providerAccountId?.let(::ProviderAccountId),
        fetchedAt = Instant.ofEpochMilli(fetchedAt),
        source = source.toQuotaSnapshotSource(),
        planType = planType,
        windows = roomQuotaJson.decodeFromString<List<StoredQuotaWindow>>(windowsJson)
            .map { it.toDomain() },
        credits = creditsJson?.let { roomQuotaJson.decodeFromString<StoredCredits>(it).toDomain() },
        responseDigest = responseDigest,
    )

fun RefreshAttempt.toEntity(): RefreshAttemptEntity =
    RefreshAttemptEntity(
        attemptId = attemptId.value,
        providerId = providerId.value,
        localAccountId = localAccountId.value,
        trigger = trigger.storageName(),
        startedAt = startedAt.toEpochMilli(),
        finishedAt = finishedAt?.toEpochMilli(),
        status = status.storageName(),
        errorCode = errorCode,
        httpStatus = httpStatus,
        retryable = retryable,
        userActionRequired = userActionRequired,
        diagnosticsDigest = diagnosticsDigest,
    )

fun RefreshAttemptEntity.toDomain(): RefreshAttempt =
    RefreshAttempt(
        attemptId = RefreshAttemptId(attemptId),
        providerId = ProviderId(providerId),
        localAccountId = LocalAccountId(localAccountId),
        trigger = trigger.toRefreshTrigger(),
        startedAt = Instant.ofEpochMilli(startedAt),
        finishedAt = finishedAt?.let(Instant::ofEpochMilli),
        status = status.toRefreshAttemptStatus(),
        errorCode = errorCode,
        httpStatus = httpStatus,
        retryable = retryable,
        userActionRequired = userActionRequired,
        diagnosticsDigest = diagnosticsDigest,
    )

private fun QuotaSnapshot.copyForAccount(account: ProviderAccount): QuotaSnapshot =
    copy(
        snapshotId = SnapshotId(
            "${account.providerId.value}:${account.localAccountId.value}:${fetchedAt.toEpochMilli()}:${source.name}",
        ),
        providerId = account.providerId,
        localAccountId = account.localAccountId,
        providerAccountId = account.providerAccountId,
    )

private fun QuotaWindow.toStoredWindow(): StoredQuotaWindow =
    StoredQuotaWindow(
        windowId = windowId.value,
        titleKey = titleKey,
        usedPercent = usedPercent,
        resetAt = resetAt?.toEpochMilli(),
        limitWindowSeconds = limitWindowSeconds,
        isPrimaryCandidate = isPrimaryCandidate,
        availability = availability.name,
        displayKind = displayKind.name,
        balanceAmount = balanceAmount,
        balanceCurrency = balanceCurrency,
        grantedBalance = grantedBalance,
        toppedUpBalance = toppedUpBalance,
        usedCount = usedCount,
        limitCount = limitCount,
        subLabel = subLabel,
        modelBuckets = modelBuckets.map { it.toStoredBucket() },
        usesModelBucketSum = usesModelBucketSum,
    )

private fun StoredQuotaWindow.toDomain(): QuotaWindow =
    QuotaWindow(
        windowId = QuotaWindowId(windowId),
        titleKey = titleKey,
        usedPercent = usedPercent,
        resetAt = resetAt?.let(Instant::ofEpochMilli),
        limitWindowSeconds = limitWindowSeconds,
        isPrimaryCandidate = isPrimaryCandidate,
        availability = availability.toQuotaWindowAvailability(),
        displayKind = displayKind.toDisplayKind(),
        balanceAmount = balanceAmount,
        balanceCurrency = balanceCurrency,
        grantedBalance = grantedBalance,
        toppedUpBalance = toppedUpBalance,
        usedCount = usedCount,
        limitCount = limitCount,
        subLabel = subLabel,
        modelBuckets = modelBuckets.map { it.toDomain() },
        usesModelBucketSum = usesModelBucketSum,
    )

private fun QuotaModelBucket.toStoredBucket(): StoredModelBucket =
    StoredModelBucket(
        modelId = modelId,
        displayName = displayName,
        remainingFraction = remainingFraction,
        resetAt = resetAt?.toEpochMilli(),
    )

private fun StoredModelBucket.toDomain(): QuotaModelBucket =
    QuotaModelBucket(
        modelId = modelId,
        displayName = displayName,
        remainingFraction = remainingFraction,
        resetAt = resetAt?.let(Instant::ofEpochMilli),
    )

private fun String.toDisplayKind(): QuotaWindowDisplayKind =
    QuotaWindowDisplayKind.entries.firstOrNull { it.name == this }
        ?: QuotaWindowDisplayKind.Percent

private fun Credits.toStoredCredits(): StoredCredits =
    StoredCredits(
        hasCredits = hasCredits,
        unlimited = unlimited,
        balance = balance,
    )

private fun StoredCredits.toDomain(): Credits =
    Credits(
        hasCredits = hasCredits,
        unlimited = unlimited,
        balance = balance,
    )

private fun String.toAccountStatus(): AccountStatus =
    when (lowercase()) {
        "active" -> AccountStatus.Active
        "needsreauth", "needs_reauth" -> AccountStatus.NeedsReauth
        "disabled" -> AccountStatus.Disabled
        "deleted" -> AccountStatus.Deleted
        else -> AccountStatus.Disabled
    }

private fun AccountStatus.storageName(): String =
    when (this) {
        AccountStatus.Active -> "active"
        AccountStatus.NeedsReauth -> "needs_reauth"
        AccountStatus.Disabled -> "disabled"
        AccountStatus.Deleted -> "deleted"
    }

private fun String.toQuotaWindowAvailability(): QuotaWindowAvailability =
    runCatching { QuotaWindowAvailability.valueOf(this) }.getOrDefault(QuotaWindowAvailability.DecodeFailed)

private fun String.toQuotaSnapshotSource(): QuotaSnapshotSource =
    when (this) {
        "deviceCodeLogin" -> QuotaSnapshotSource.DeviceCodeLogin
        "oauthWebView" -> QuotaSnapshotSource.OAuthWebView
        "authJsonImport" -> QuotaSnapshotSource.AuthJsonImport
        "backgroundRefresh" -> QuotaSnapshotSource.BackgroundRefresh
        "manualRefresh" -> QuotaSnapshotSource.ManualRefresh
        "widgetRefresh" -> QuotaSnapshotSource.WidgetRefresh
        "appOpenRefresh" -> QuotaSnapshotSource.AppOpenRefresh
        else -> runCatching { QuotaSnapshotSource.valueOf(this) }.getOrDefault(QuotaSnapshotSource.BackgroundRefresh)
    }

private fun QuotaSnapshotSource.storageName(): String =
    when (this) {
        QuotaSnapshotSource.DeviceCodeLogin -> "deviceCodeLogin"
        QuotaSnapshotSource.OAuthWebView -> "oauthWebView"
        QuotaSnapshotSource.AuthJsonImport -> "authJsonImport"
        QuotaSnapshotSource.BackgroundRefresh -> "backgroundRefresh"
        QuotaSnapshotSource.ManualRefresh -> "manualRefresh"
        QuotaSnapshotSource.WidgetRefresh -> "widgetRefresh"
        QuotaSnapshotSource.AppOpenRefresh -> "appOpenRefresh"
        QuotaSnapshotSource.ApiKeyImport -> "apiKeyImport"
        QuotaSnapshotSource.CookieAuth -> "cookieAuth"
        QuotaSnapshotSource.OAuthPkceLogin -> "oauthPkceLogin"
    }

private fun RefreshTrigger.storageName(): String =
    when (this) {
        RefreshTrigger.AppOpen -> "appOpen"
        RefreshTrigger.Manual -> "manual"
        RefreshTrigger.Widget -> "widget"
        RefreshTrigger.ImportValidation -> "importValidation"
        RefreshTrigger.AccountSwitch -> "accountSwitch"
        RefreshTrigger.Periodic -> "periodic"
    }

private fun RefreshAttemptStatus.storageName(): String =
    when (this) {
        RefreshAttemptStatus.Success -> "success"
        RefreshAttemptStatus.Failed -> "failed"
        RefreshAttemptStatus.Skipped -> "skipped"
        RefreshAttemptStatus.Cancelled -> "cancelled"
    }

private fun String.toRefreshTrigger(): RefreshTrigger =
    when (this) {
        "appOpen" -> RefreshTrigger.AppOpen
        "manual" -> RefreshTrigger.Manual
        "widget" -> RefreshTrigger.Widget
        "importValidation" -> RefreshTrigger.ImportValidation
        "accountSwitch" -> RefreshTrigger.AccountSwitch
        "periodic" -> RefreshTrigger.Periodic
        else -> runCatching { RefreshTrigger.valueOf(this) }.getOrDefault(RefreshTrigger.Periodic)
    }

private fun String.toRefreshAttemptStatus(): RefreshAttemptStatus =
    when (this) {
        "success" -> RefreshAttemptStatus.Success
        "failed" -> RefreshAttemptStatus.Failed
        "skipped" -> RefreshAttemptStatus.Skipped
        "cancelled" -> RefreshAttemptStatus.Cancelled
        else -> runCatching { RefreshAttemptStatus.valueOf(this) }.getOrDefault(RefreshAttemptStatus.Failed)
    }

@Serializable
private data class StoredQuotaWindow(
    val windowId: String,
    val titleKey: String,
    val usedPercent: Int?,
    val resetAt: Long?,
    val limitWindowSeconds: Int?,
    val isPrimaryCandidate: Boolean,
    val availability: String,
    val displayKind: String = "Percent",
    val balanceAmount: String? = null,
    val balanceCurrency: String? = null,
    val grantedBalance: String? = null,
    val toppedUpBalance: String? = null,
    val usedCount: Int? = null,
    val limitCount: Int? = null,
    val subLabel: String? = null,
    val modelBuckets: List<StoredModelBucket> = emptyList(),
    val usesModelBucketSum: Boolean = false,
)

@Serializable
private data class StoredModelBucket(
    val modelId: String,
    val displayName: String,
    val remainingFraction: Double,
    val resetAt: Long? = null,
)

@Serializable
private data class StoredCredits(
    val hasCredits: Boolean,
    val unlimited: Boolean,
    val balance: Double?,
)

private val roomQuotaJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}
