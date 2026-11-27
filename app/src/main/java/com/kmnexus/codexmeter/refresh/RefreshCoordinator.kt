package com.kmnexus.codexmeter.refresh

import com.kmnexus.codexmeter.core.concurrency.SnapshotEpoch
import com.kmnexus.codexmeter.core.concurrency.VersionedStateGuard
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
import kotlinx.coroutines.CancellationException
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
    private val stateGuard: VersionedStateGuard = VersionedStateGuard(),
    private val selectedAccountReader: SelectedAccountReader? = null,
    private val perAccountEffectsPublisher: PerAccountQuotaEffectPublisher = NoopPerAccountQuotaEffectPublisher,
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

        // Capture the version token BEFORE any provider I/O. If the user renames, switches or
        // deletes the account while the network call is in flight, commitIfFresh will discard the
        // late write-back deterministically instead of clobbering the newer state (rule A8).
        val epoch = stateGuard.captureReadToken(account.localAccountId)
        try {
            val result = executeRefresh(account = account, trigger = trigger, epoch = epoch)
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
        epoch: SnapshotEpoch,
    ): RefreshResult {
        val startedAt = clock.instant()
        return when (val providerResult = provider.refresh(account = account, trigger = trigger)) {
            is ProviderRefreshResult.Success -> recordSuccess(
                account = account,
                trigger = trigger,
                startedAt = startedAt,
                snapshot = providerResult.snapshot,
                epoch = epoch,
            )

            is ProviderRefreshResult.Failure -> recordFailure(
                account = account,
                trigger = trigger,
                startedAt = startedAt,
                error = providerResult.error,
                epoch = epoch,
            )
        }
    }

    private suspend fun recordSuccess(
        account: ProviderAccount,
        trigger: RefreshTrigger,
        startedAt: Instant,
        snapshot: QuotaSnapshot,
        epoch: SnapshotEpoch,
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
            epoch = epoch,
        )
        return RefreshResult.Success(snapshot = snapshot)
    }

    private suspend fun recordFailure(
        account: ProviderAccount,
        trigger: RefreshTrigger,
        startedAt: Instant,
        error: QuotaError,
        epoch: SnapshotEpoch,
    ): RefreshResult.Failure {
        val lastKnownGood = snapshotStore.latestFor(account)
        // Read the previous attempt before saving this one, so the auth-failure streak can be seen.
        val previousAttempt = attemptStore.latestFor(account)
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
        if (error.userActionRequired && needsReauthAfterAuthFailure(previousAttempt, error)) {
            accountStatusStore.markNeedsReauth(account = account, updatedAt = finishedAt)
        }
        publishCurrentState(
            account = account,
            latestSnapshot = lastKnownGood,
            latestAttempt = attempt,
            epoch = epoch,
        )
        return RefreshResult.Failure(error = error, lastKnownGood = lastKnownGood)
    }

    private suspend fun publishCurrentState(
        account: ProviderAccount,
        latestSnapshot: QuotaSnapshot?,
        latestAttempt: RefreshAttempt,
        epoch: SnapshotEpoch,
    ) {
        val state = currentQuotaStateFactory.create(
            account = account,
            latestSnapshot = latestSnapshot,
            latestAttempt = latestAttempt,
            now = clock.instant(),
            primaryWindowId = primaryQuotaWindowPreferenceReader.primaryQuotaWindowId(),
        )

        // Per-account effects (quota alerts, widget state) belong to the refreshed account itself:
        // they fire for EVERY successful refresh regardless of which account is selected. Only the
        // "current account" presentation write-back below is epoch-guarded. Spec: any alert-enabled
        // account window can trigger threshold alerts (docs/SPEC.md alert rules).
        try {
            perAccountEffectsPublisher.publishAccountEffects(state)
        } catch (exception: CancellationException) {
            throw exception
        } catch (_: Exception) {
            // Presentation effects must not turn a successful refresh into a refresh failure.
        }

        // The durable snapshot/attempt writes above are keyed per account and always correct;
        // what must be defended is the "current state" write-back to the status notification. The
        // selection is re-read HERE, at commit time, so an account switch that happened while the
        // network call was in flight is observed even if it never touched the guard's generation.
        // A missing reader means the account dimension cannot be checked (tests that predate the
        // guard) — fall back to the epoch's own account so generation/edit protection still applies.
        // A reader that answers null means "no account selected": the commit correctly mismatches.
        // All preparatory reads (state construction above, selection here) are done BEFORE entering
        // commitIfFresh so its serialized critical section covers only validation + the write.
        val selectedAccountId = if (selectedAccountReader != null) {
            selectedAccountReader.selectedAccountId()
        } else {
            epoch.accountId
        }
        stateGuard.commitIfFresh(epoch = epoch, selectedAccountId = selectedAccountId) {
            currentQuotaStatePublisher.publish(state)
        }
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

/**
 * Reads the currently selected account fresh at commit time. Kept as a tiny refresh-local seam so
 * the coordinator does not depend on the data layer; AppContainer adapts CurrentAccountPreferences.
 * When null (tests that predate the guard), only generation/edit protection applies.
 */
fun interface SelectedAccountReader {
    suspend fun selectedAccountId(): LocalAccountId?
}

/**
 * Per-account effects of a completed refresh: threshold alerts and widget updates belong to the
 * account that was refreshed, not to whichever account is currently selected. Implementations must
 * never use this channel to overwrite current-account presentation state — that path is the
 * epoch-guarded [CurrentQuotaStatePublisher].
 */
fun interface PerAccountQuotaEffectPublisher {
    suspend fun publishAccountEffects(state: CurrentQuotaState)
}

private object NoopPerAccountQuotaEffectPublisher : PerAccountQuotaEffectPublisher {
    override suspend fun publishAccountEffects(state: CurrentQuotaState) = Unit
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

    /**
     * The account's most recent attempt, used to tell a repeated auth failure from a one-off. A
     * store with no history answers null, which counts as "no failure before this one".
     */
    suspend fun latestFor(account: ProviderAccount): RefreshAttempt? = null
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
