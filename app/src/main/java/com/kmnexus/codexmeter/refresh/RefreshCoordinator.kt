package com.kmnexus.codexmeter.refresh

import com.kmnexus.codexmeter.domain.model.AccountStatus
import com.kmnexus.codexmeter.domain.model.LocalAccountId
import com.kmnexus.codexmeter.domain.model.ProviderAccount
import com.kmnexus.codexmeter.domain.model.ProviderId
import com.kmnexus.codexmeter.domain.model.RefreshAttemptId
import com.kmnexus.codexmeter.domain.quota.CurrentQuotaState
import com.kmnexus.codexmeter.domain.quota.CurrentQuotaStateFactory
import com.kmnexus.codexmeter.domain.quota.QuotaSnapshot
import com.kmnexus.codexmeter.domain.refresh.QuotaError
import com.kmnexus.codexmeter.domain.refresh.RefreshAttempt
import com.kmnexus.codexmeter.domain.refresh.RefreshAttemptStatus
import com.kmnexus.codexmeter.domain.refresh.RefreshTrigger
import com.kmnexus.codexmeter.domain.settings.DefaultPrimaryQuotaWindowPreferenceReader
import com.kmnexus.codexmeter.domain.settings.PrimaryQuotaWindowPreferenceReader
import java.time.Clock
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Coordinates quota refreshes without owning provider-private session details.
 *
 * The single-flight map protects token refresh and quota fetch paths from running twice for the
 * same account. A failed provider result records a safe attempt but intentionally does not save a
 * quota snapshot, preserving the last successful data for app, widget and notification state.
 */
class RefreshCoordinator(
    private val provider: RefreshProvider,
    private val snapshotStore: SnapshotStore,
    private val attemptStore: RefreshAttemptStore,
    private val accountStatusStore: RefreshAccountStatusStore = NoopRefreshAccountStatusStore,
    private val currentQuotaStateFactory: CurrentQuotaStateFactory = CurrentQuotaStateFactory(),
    private val currentQuotaStatePublisher: CurrentQuotaStatePublisher = NoopCurrentQuotaStatePublisher,
    private val primaryQuotaWindowPreferenceReader: PrimaryQuotaWindowPreferenceReader =
        DefaultPrimaryQuotaWindowPreferenceReader,
    private val attemptIdProvider: AttemptIdProvider,
    private val clock: Clock,
) {
    private val inFlightMutex = Mutex()
    private val inFlight = mutableMapOf<RefreshKey, CompletableDeferred<RefreshResult>>()
    internal var beforeFlightRemoval: suspend () -> Unit = {}

    suspend fun refresh(
        account: ProviderAccount,
        trigger: RefreshTrigger,
    ): RefreshResult {
        val key = RefreshKey(account.providerId, account.localAccountId)
        val flight = inFlightMutex.withLock {
            val existing = inFlight[key]
            if (existing != null) {
                RefreshFlight(deferred = existing, owner = false)
            } else {
                val deferred = CompletableDeferred<RefreshResult>()
                inFlight[key] = deferred
                RefreshFlight(deferred = deferred, owner = true)
            }
        }

        if (!flight.owner) {
            return flight.deferred.await()
        }

        try {
            val result = executeRefresh(account = account, trigger = trigger)
            completeFlight(key = key, deferred = flight.deferred, result = result)
            return result
        } catch (throwable: Throwable) {
            completeFlightExceptionally(key = key, deferred = flight.deferred, throwable = throwable)
            throw throwable
        }
    }

    private suspend fun completeFlight(
        key: RefreshKey,
        deferred: CompletableDeferred<RefreshResult>,
        result: RefreshResult,
    ) {
        withContext(NonCancellable) {
            removeFlight(key = key, deferred = deferred)
            deferred.complete(result)
        }
    }

    private suspend fun completeFlightExceptionally(
        key: RefreshKey,
        deferred: CompletableDeferred<RefreshResult>,
        throwable: Throwable,
    ) {
        withContext(NonCancellable) {
            removeFlight(key = key, deferred = deferred)
            deferred.completeExceptionally(throwable)
        }
    }

    private suspend fun removeFlight(
        key: RefreshKey,
        deferred: CompletableDeferred<RefreshResult>,
    ) {
        beforeFlightRemoval()
        inFlightMutex.withLock {
            if (inFlight[key] === deferred) {
                inFlight.remove(key)
            }
        }
    }

    private suspend fun executeRefresh(
        account: ProviderAccount,
        trigger: RefreshTrigger,
    ): RefreshResult {
        val startedAt = clock.instant()
        return when (val providerResult = provider.refresh(account = account, trigger = trigger)) {
            is ProviderRefreshResult.Success -> recordSuccess(
                account = account,
                trigger = trigger,
                startedAt = startedAt,
                snapshot = providerResult.snapshot,
            )

            is ProviderRefreshResult.Failure -> recordFailure(
                account = account,
                trigger = trigger,
                startedAt = startedAt,
                error = providerResult.error,
            )
        }
    }

    private suspend fun recordSuccess(
        account: ProviderAccount,
        trigger: RefreshTrigger,
        startedAt: Instant,
        snapshot: QuotaSnapshot,
    ): RefreshResult.Success {
        snapshotStore.save(snapshot)
        val finishedAt = clock.instant()
        val attempt = RefreshAttempt(
            attemptId = attemptIdProvider.nextId(),
            providerId = account.providerId,
            localAccountId = account.localAccountId,
            trigger = trigger,
            startedAt = startedAt,
            finishedAt = finishedAt,
            status = RefreshAttemptStatus.Success,
            errorCode = null,
            httpStatus = null,
            retryable = null,
            userActionRequired = null,
            diagnosticsDigest = null,
        )
        attemptStore.save(attempt)
        // A successful refresh proves the credential works again, so clear a stale NeedsReauth flag;
        // otherwise the account stays locked in the "需要重新登录" state forever despite fresh data.
        val recoveredAccount = if (account.status == AccountStatus.NeedsReauth) {
            accountStatusStore.markActive(account = account, updatedAt = finishedAt)
            account.copy(status = AccountStatus.Active, updatedAt = finishedAt)
        } else {
            account
        }
        publishCurrentState(
            account = recoveredAccount,
            latestSnapshot = snapshot,
            latestAttempt = attempt,
        )
        return RefreshResult.Success(snapshot = snapshot)
    }

    private suspend fun recordFailure(
        account: ProviderAccount,
        trigger: RefreshTrigger,
        startedAt: Instant,
        error: QuotaError,
    ): RefreshResult.Failure {
        val lastKnownGood = snapshotStore.latestFor(account)
        val finishedAt = clock.instant()
        val attempt = RefreshAttempt(
            attemptId = attemptIdProvider.nextId(),
            providerId = account.providerId,
            localAccountId = account.localAccountId,
            trigger = trigger,
            startedAt = startedAt,
            finishedAt = finishedAt,
            status = RefreshAttemptStatus.Failed,
            errorCode = error.safeMessageKey,
            httpStatus = error.httpStatus,
            retryable = error.retryable,
            userActionRequired = error.userActionRequired,
            diagnosticsDigest = error.diagnosticsDigest,
        )
        attemptStore.save(attempt)
        if (error.userActionRequired) {
            accountStatusStore.markNeedsReauth(account = account, updatedAt = finishedAt)
        }
        publishCurrentState(
            account = account,
            latestSnapshot = lastKnownGood,
            latestAttempt = attempt,
        )
        return RefreshResult.Failure(error = error, lastKnownGood = lastKnownGood)
    }

    private suspend fun publishCurrentState(
        account: ProviderAccount,
        latestSnapshot: QuotaSnapshot?,
        latestAttempt: RefreshAttempt,
    ) {
        currentQuotaStatePublisher.publish(
            currentQuotaStateFactory.create(
                account = account,
                latestSnapshot = latestSnapshot,
                latestAttempt = latestAttempt,
                now = clock.instant(),
                primaryWindowId = primaryQuotaWindowPreferenceReader.primaryQuotaWindowId(),
            ),
        )
    }

    private data class RefreshKey(
        val providerId: ProviderId,
        val localAccountId: LocalAccountId,
    )

    private data class RefreshFlight(
        val deferred: CompletableDeferred<RefreshResult>,
        val owner: Boolean,
    )
}

fun interface RefreshProvider {
    suspend fun refresh(
        account: ProviderAccount,
        trigger: RefreshTrigger,
    ): ProviderRefreshResult
}

sealed interface ProviderRefreshResult {
    data class Success(val snapshot: QuotaSnapshot) : ProviderRefreshResult
    data class Failure(val error: QuotaError) : ProviderRefreshResult
}

interface SnapshotStore {
    suspend fun save(snapshot: QuotaSnapshot)

    suspend fun latestFor(account: ProviderAccount): QuotaSnapshot?
}

interface RefreshAttemptStore {
    suspend fun save(attempt: RefreshAttempt)
}

interface RefreshAccountStatusStore {
    suspend fun markNeedsReauth(account: ProviderAccount, updatedAt: Instant)

    /** Clears a NeedsReauth flag once the account refreshes successfully again. */
    suspend fun markActive(account: ProviderAccount, updatedAt: Instant) = Unit
}

private object NoopRefreshAccountStatusStore : RefreshAccountStatusStore {
    override suspend fun markNeedsReauth(account: ProviderAccount, updatedAt: Instant) = Unit
}

interface CurrentQuotaStatePublisher {
    suspend fun publish(state: CurrentQuotaState)
}

private object NoopCurrentQuotaStatePublisher : CurrentQuotaStatePublisher {
    override suspend fun publish(state: CurrentQuotaState) = Unit
}

fun interface AttemptIdProvider {
    fun nextId(): RefreshAttemptId
}

sealed interface RefreshResult {
    data class Success(val snapshot: QuotaSnapshot) : RefreshResult
    data class Failure(
        val error: QuotaError,
        val lastKnownGood: QuotaSnapshot?,
    ) : RefreshResult
}
