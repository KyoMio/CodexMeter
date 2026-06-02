package com.kmnexus.codexmeter.data.repository

import androidx.room.withTransaction
import com.kmnexus.codexmeter.data.local.db.CodexMeterDatabase
import com.kmnexus.codexmeter.data.preferences.CurrentAccountReader
import com.kmnexus.codexmeter.domain.settings.QuotaHistoryClearResult
import com.kmnexus.codexmeter.domain.settings.QuotaHistoryClearUseCase

class QuotaHistoryClearRepository(
    private val database: CodexMeterDatabase,
    private val currentAccountReader: CurrentAccountReader,
) : QuotaHistoryClearUseCase {
    override suspend fun clearCurrentAccountHistory(): QuotaHistoryClearResult {
        val selection = currentAccountReader.currentAccountSelection()
            ?: return QuotaHistoryClearResult(deletedQuotaSnapshots = 0, deletedRefreshAttempts = 0)

        return database.withTransaction {
            val providerId = selection.providerId.value
            val localAccountId = selection.localAccountId.value
            val deletedSnapshots = database.quotaSnapshotDao().deleteForAccount(
                providerId = providerId,
                localAccountId = localAccountId,
            )
            val deletedAttempts = database.refreshAttemptDao().deleteForAccount(
                providerId = providerId,
                localAccountId = localAccountId,
            )
            database.alertStateDao().deleteForAccount(
                providerId = providerId,
                localAccountId = localAccountId,
            )
            QuotaHistoryClearResult(
                deletedQuotaSnapshots = deletedSnapshots,
                deletedRefreshAttempts = deletedAttempts,
            )
        }
    }

    override suspend fun clearAllHistory(): QuotaHistoryClearResult =
        database.withTransaction {
            val deletedSnapshots = database.quotaSnapshotDao().deleteAll()
            val deletedAttempts = database.refreshAttemptDao().deleteAll()
            database.alertStateDao().deleteAll()
            QuotaHistoryClearResult(
                deletedQuotaSnapshots = deletedSnapshots,
                deletedRefreshAttempts = deletedAttempts,
            )
        }
}
