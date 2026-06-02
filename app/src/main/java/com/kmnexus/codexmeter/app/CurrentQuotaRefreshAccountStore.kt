package com.kmnexus.codexmeter.app

import com.kmnexus.codexmeter.data.local.dao.ProviderAccountDao
import com.kmnexus.codexmeter.data.preferences.CurrentAccountReader
import com.kmnexus.codexmeter.data.repository.toDomain
import com.kmnexus.codexmeter.domain.model.AccountStatus
import com.kmnexus.codexmeter.domain.model.ProviderAccount

internal class CurrentQuotaRefreshAccountStore(
    private val currentAccountReader: CurrentAccountReader,
    private val providerAccountDao: ProviderAccountDao,
) {
    suspend fun currentAccount(): ProviderAccount? {
        val selection = currentAccountReader.currentAccountSelection() ?: return null
        val account = providerAccountDao.getById(selection.localAccountId.value)?.toDomain() ?: return null
        return account.takeIf {
            it.providerId == selection.providerId && it.status == AccountStatus.Active
        }
    }

    /**
     * Every Active account across ALL providers — background refresh must keep each connected
     * provider fresh, not only Codex. (Previously filtered to a single hard-coded Codex id, which is
     * why non-Codex accounts only refreshed when manually selected as current.)
     */
    suspend fun activeAccounts(): List<ProviderAccount> =
        providerAccountDao.listAll()
            .map { it.toDomain() }
            .filter { it.status == AccountStatus.Active }
}
