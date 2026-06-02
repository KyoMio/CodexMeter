package com.kmnexus.codexmeter.domain.settings

data class QuotaHistoryClearResult(
    val deletedQuotaSnapshots: Int,
    val deletedRefreshAttempts: Int,
)

interface QuotaHistoryClearUseCase {
    suspend fun clearCurrentAccountHistory(): QuotaHistoryClearResult

    suspend fun clearAllHistory(): QuotaHistoryClearResult
}

object NoopQuotaHistoryClearUseCase : QuotaHistoryClearUseCase {
    override suspend fun clearCurrentAccountHistory(): QuotaHistoryClearResult =
        QuotaHistoryClearResult(deletedQuotaSnapshots = 0, deletedRefreshAttempts = 0)

    override suspend fun clearAllHistory(): QuotaHistoryClearResult =
        QuotaHistoryClearResult(deletedQuotaSnapshots = 0, deletedRefreshAttempts = 0)
}
