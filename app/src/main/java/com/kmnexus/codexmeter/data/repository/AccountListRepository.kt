package com.kmnexus.codexmeter.data.repository

import com.kmnexus.codexmeter.data.local.dao.ProviderAccountDao
import com.kmnexus.codexmeter.data.local.dao.QuotaSnapshotDao
import com.kmnexus.codexmeter.data.preferences.CurrentAccountReader
import com.kmnexus.codexmeter.domain.account.AccountListResult
import com.kmnexus.codexmeter.domain.account.AccountListUseCase
import com.kmnexus.codexmeter.domain.model.ProviderId

class AccountListRepository(
    private val providerAccountDao: ProviderAccountDao,
    private val quotaSnapshotDao: QuotaSnapshotDao,
    private val currentAccountReader: CurrentAccountReader,
) : AccountListUseCase {
    override suspend fun loadAccounts(): AccountListResult {
        val rawAccounts = providerAccountDao.listAll().map { it.toDomain() }
        val currentSelection = currentAccountReader.currentAccountSelection()
            ?.takeIf { selection -> rawAccounts.any { it.localAccountId == selection.localAccountId } }

        val latestQuotaSnapshots = rawAccounts.mapNotNull { account ->
            quotaSnapshotDao.getLatestForAccount(
                providerId = account.providerId.value,
                localAccountId = account.localAccountId.value,
            )?.toDomain()?.let { account.localAccountId to it }
        }.toMap()

        // The refresh pipeline persists snapshots, not the account's last-refresh timestamp, so derive
        // it from the latest snapshot's `fetchedAt` when the account row hasn't recorded one. Mirrors
        // the COALESCE fallback in ProviderAccountDao.listByProvider so newly-added providers don't
        // show "No successful refresh yet" despite having fresh quota.
        val accounts = rawAccounts.map { account ->
            if (account.lastSuccessfulRefreshAt != null) {
                account
            } else {
                latestQuotaSnapshots[account.localAccountId]
                    ?.let { account.copy(lastSuccessfulRefreshAt = it.fetchedAt) }
                    ?: account
            }
        }

        return AccountListResult(
            accounts = accounts,
            currentAccountId = currentSelection?.localAccountId,
            latestQuotaSnapshots = latestQuotaSnapshots,
        )
    }
}
