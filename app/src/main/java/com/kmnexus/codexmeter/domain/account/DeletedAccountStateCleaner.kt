package com.kmnexus.codexmeter.domain.account

import com.kmnexus.codexmeter.domain.model.LocalAccountId
import com.kmnexus.codexmeter.domain.model.ProviderAccount
import com.kmnexus.codexmeter.domain.model.ProviderId
import com.kmnexus.codexmeter.domain.quota.QuotaSnapshot

data class AccountListResult(
    val accounts: List<ProviderAccount>,
    /** The account the Home selector last viewed — the single "default account" source. */
    val defaultAccountId: LocalAccountId?,
    val latestQuotaSnapshots: Map<LocalAccountId, QuotaSnapshot> = emptyMap(),
)

fun interface AccountListUseCase {
    suspend fun loadAccounts(): AccountListResult
}

object NoopAccountListUseCase : AccountListUseCase {
    override suspend fun loadAccounts(): AccountListResult =
        AccountListResult(accounts = emptyList(), defaultAccountId = null)
}

fun interface AccountDeleteUseCase {
    suspend fun deleteAccount(providerId: ProviderId, localAccountId: LocalAccountId)
}

object NoopAccountDeleteUseCase : AccountDeleteUseCase {
    override suspend fun deleteAccount(providerId: ProviderId, localAccountId: LocalAccountId) = Unit
}

/**
 * The only user-facing writer of the default-account selection: picking an account in the Home
 * selector. It supersedes the removed per-account "set current" entry.
 */
fun interface DefaultAccountSelectUseCase {
    suspend fun selectDefaultAccount(providerId: ProviderId, localAccountId: LocalAccountId): Boolean
}

object NoopDefaultAccountSelectUseCase : DefaultAccountSelectUseCase {
    override suspend fun selectDefaultAccount(providerId: ProviderId, localAccountId: LocalAccountId): Boolean = false
}

fun interface AccountRenameUseCase {
    suspend fun renameAccount(
        providerId: ProviderId,
        localAccountId: LocalAccountId,
        displayName: String,
    ): ProviderAccount?
}

object NoopAccountRenameUseCase : AccountRenameUseCase {
    override suspend fun renameAccount(
        providerId: ProviderId,
        localAccountId: LocalAccountId,
        displayName: String,
    ): ProviderAccount? = null
}

fun interface AccountSwitchRefreshRequester {
    suspend fun requestAccountSwitchRefresh(account: ProviderAccount)
}

object NoopAccountSwitchRefreshRequester : AccountSwitchRefreshRequester {
    override suspend fun requestAccountSwitchRefresh(account: ProviderAccount) = Unit
}

fun interface CurrentAccountStateRepublisher {
    suspend fun republishCurrentAccountState(account: ProviderAccount)
}

object NoopCurrentAccountStateRepublisher : CurrentAccountStateRepublisher {
    override suspend fun republishCurrentAccountState(account: ProviderAccount) = Unit
}

interface DeletedAccountStateCleaner {
    suspend fun clearDeletedAccountState(
        providerId: ProviderId,
        localAccountId: LocalAccountId,
    ): DeletedAccountStateCleanup
}

fun interface DeletedAccountStateCleanup {
    suspend fun restore()
}

object NoopDeletedAccountStateCleaner : DeletedAccountStateCleaner {
    override suspend fun clearDeletedAccountState(
        providerId: ProviderId,
        localAccountId: LocalAccountId,
    ): DeletedAccountStateCleanup = DeletedAccountStateCleanup {}
}
