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
    /**
     * The currently-selected account when it is eligible for a user-initiated refresh (Active or
     * NeedsReauth). Returning a NeedsReauth account lets manual pull-to-refresh on Home retry it; a
     * successful retry then clears the flag. Disabled/Deleted return null. Only the manual Home
     * refresh path uses this; background refresh enumerates [activeAccounts] instead.
     */
    suspend fun currentAccount(): ProviderAccount? {
        val selection = currentAccountReader.currentAccountSelection() ?: return null
        val account = providerAccountDao.getById(selection.localAccountId.value)?.toDomain() ?: return null
        return account.takeIf {
            it.providerId == selection.providerId && it.status.isManuallyRefreshable()
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

    /**
     * Accounts a user-initiated refresh may attempt: Active plus NeedsReauth. Including NeedsReauth
     * lets a manual pull-to-refresh retry a flagged account; a successful retry then clears the flag
     * (see [com.kmnexus.codexmeter.refresh.RefreshCoordinator]). Disabled/Deleted stay excluded.
     * Background (Periodic) refresh keeps using [activeAccounts] so it never re-hits failed accounts.
     */
    suspend fun manuallyRefreshableAccounts(): List<ProviderAccount> =
        providerAccountDao.listAll()
            .map { it.toDomain() }
            .filter { it.status.isManuallyRefreshable() }
}

private fun AccountStatus.isManuallyRefreshable(): Boolean =
    this == AccountStatus.Active || this == AccountStatus.NeedsReauth
