package com.kmnexus.codexmeter.app

import com.kmnexus.codexmeter.domain.settings.QuotaHistoryClearResult
import com.kmnexus.codexmeter.domain.settings.QuotaHistoryClearUseCase
import com.kmnexus.codexmeter.refresh.CurrentQuotaStatePublisher
import com.kmnexus.codexmeter.ui.home.HomeCurrentQuotaStateLoader
import kotlinx.coroutines.CancellationException

internal class RepublishingQuotaHistoryClearUseCase(
    private val delegate: QuotaHistoryClearUseCase,
    private val currentQuotaStateLoader: HomeCurrentQuotaStateLoader,
    private val currentQuotaStatePublisher: CurrentQuotaStatePublisher,
) : QuotaHistoryClearUseCase {
    override suspend fun clearCurrentAccountHistory(): QuotaHistoryClearResult =
        clearAndRepublish { delegate.clearCurrentAccountHistory() }

    override suspend fun clearAllHistory(): QuotaHistoryClearResult =
        clearAndRepublish { delegate.clearAllHistory() }

    private suspend fun clearAndRepublish(
        clear: suspend () -> QuotaHistoryClearResult,
    ): QuotaHistoryClearResult {
        val result = clear()
        try {
            currentQuotaStatePublisher.publish(currentQuotaStateLoader.loadCurrentState())
        } catch (exception: CancellationException) {
            throw exception
        } catch (_: Exception) {
            // Data deletion is durable; presentation refresh can recover on the next state update.
        }
        return result
    }
}
