package com.kmnexus.codexmeter.refresh

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.kmnexus.codexmeter.domain.model.AccountStatus
import com.kmnexus.codexmeter.domain.model.ProviderAccount
import com.kmnexus.codexmeter.domain.refresh.RefreshTrigger
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

fun interface ExchangeRateRefresher {
    suspend fun refreshExchangeRates()
}

class QuotaRefreshWorker(
    appContext: Context,
    workerParameters: WorkerParameters,
) : CoroutineWorker(appContext, workerParameters) {
    override suspend fun doWork(): Result {
        return when (QuotaRefreshWorkerOutcome.fromDependencies(applicationContext as? QuotaRefreshDependenciesProvider)) {
            QuotaRefreshWorkerOutcome.Success -> Result.success()
            QuotaRefreshWorkerOutcome.Retry -> Result.retry()
            QuotaRefreshWorkerOutcome.Failure -> Result.failure()
        }
    }
}

interface QuotaRefreshDependenciesProvider {
    val refreshCoordinator: RefreshCoordinator

    val exchangeRateRefresher: ExchangeRateRefresher
        get() = ExchangeRateRefresher { }

    suspend fun activeQuotaRefreshAccounts(): List<ProviderAccount>
}

enum class QuotaRefreshWorkerOutcome {
    Success,
    Retry,
    Failure;

    companion object {
        suspend fun fromDependencies(provider: QuotaRefreshDependenciesProvider?): QuotaRefreshWorkerOutcome {
            val dependencies = provider ?: return Failure
            val results = MultiAccountRefreshRunner(
                refreshCoordinator = dependencies.refreshCoordinator,
                exchangeRateRefresher = dependencies.exchangeRateRefresher,
            ).refresh(
                accounts = dependencies.activeQuotaRefreshAccounts(),
                trigger = RefreshTrigger.Periodic,
            )
            return when {
                results.any { it.isRetryableFailure() } -> Retry
                results.any { it is RefreshResult.Failure } -> Failure
                else -> Success
            }
        }
    }
}

class MultiAccountRefreshRunner(
    private val refreshCoordinator: RefreshCoordinator,
    private val parallelism: Int = DEFAULT_PARALLELISM,
    private val exchangeRateRefresher: ExchangeRateRefresher = ExchangeRateRefresher { },
) {
    init {
        require(parallelism > 0) { "parallelism must be positive" }
    }

    suspend fun refresh(
        accounts: List<ProviderAccount>,
        trigger: RefreshTrigger,
    ): List<RefreshResult> = coroutineScope {
        runCatching { exchangeRateRefresher.refreshExchangeRates() }
        val semaphore = Semaphore(parallelism)
        accounts
            .filter { it.status == AccountStatus.Active }
            .map { account ->
                async {
                    semaphore.withPermit {
                        refreshCoordinator.refresh(account = account, trigger = trigger)
                    }
                }
            }
            .awaitAll()
    }

    companion object {
        // Refresh up to 4 accounts at once (bounded concurrency) so a multi-provider setup completes
        // a periodic cycle quickly without hammering every provider endpoint simultaneously.
        const val DEFAULT_PARALLELISM = 4
    }
}

private fun RefreshResult.isRetryableFailure(): Boolean =
    this is RefreshResult.Failure && error.retryable
