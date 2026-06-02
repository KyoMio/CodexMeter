package com.kmnexus.codexmeter.data.repository

import androidx.room.withTransaction
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
}

class RoomRefreshAccountStatusStore(
    private val providerAccountDao: ProviderAccountDao,
) : RefreshAccountStatusStore {
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
    private val beforeRoomCommit: suspend () -> Unit = {},
) : CodexSessionImporter.ImportPersistence {
    override suspend fun save(
        account: ProviderAccount,
        sessionEnvelope: ProviderSessionEnvelope,
        snapshot: QuotaSnapshot,
    ): CodexSessionImporter.CommittedImport {
        val committedAccount = committedAccountFor(account)
        val committedEnvelope = sessionEnvelope.copy(
            localAccountId = committedAccount.localAccountId.value,
            providerAccountId = committedAccount.providerAccountId?.value,
        )
        val committedSnapshot = snapshot.copyForAccount(committedAccount)
        val committed = CodexSessionImporter.CommittedImport(
            account = committedAccount,
            sessionEnvelope = committedEnvelope,
            snapshot = committedSnapshot,
        )
        val previousSelection = currentAccountStore.currentAccountSelection()
        val previousEnvelope = sessionStore.load(
            providerId = committed.sessionEnvelope.providerId,
            localAccountId = committed.sessionEnvelope.localAccountId,
        )

        try {
            sessionStore.save(committed.sessionEnvelope)
            currentAccountStore.updateCurrentAccountSelection(
                CurrentAccountSelection(
                    providerId = committed.account.providerId,
                    localAccountId = committed.account.localAccountId,
                ),
            )
            database.withTransaction {
                database.providerAccountDao().upsert(committed.account.toEntity())
                beforeRoomCommit()
                database.quotaSnapshotDao().insert(committed.snapshot.toEntity())
            }
        } catch (throwable: Throwable) {
            runCatching {
                currentAccountStore.updateCurrentAccountSelection(previousSelection)
            }
            runCatching {
                if (previousEnvelope == null) {
                    sessionStore.delete(
                        providerId = committed.sessionEnvelope.providerId,
                        localAccountId = committed.sessionEnvelope.localAccountId,
                    )
                } else {
                    sessionStore.save(previousEnvelope)
                }
            }
            throw throwable
        }

        return committed
    }

    private suspend fun committedAccountFor(account: ProviderAccount): ProviderAccount {
        val existingAccount = account.providerAccountId?.let {
            database.providerAccountDao().getByProviderAccountId(
                providerId = account.providerId.value,
                providerAccountId = it.value,
            )
        }?.toDomain()

        return if (existingAccount == null) {
            account
        } else {
            existingAccount.copy(
                status = account.status,
                updatedAt = account.updatedAt,
            )
        }
    }
}

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
