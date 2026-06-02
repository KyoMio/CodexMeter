package com.kmnexus.codexmeter.data.repository

import com.kmnexus.codexmeter.data.local.dao.ProviderAccountDao
import com.kmnexus.codexmeter.data.preferences.CurrentAccountSelection
import com.kmnexus.codexmeter.data.preferences.CurrentAccountStore
import com.kmnexus.codexmeter.domain.account.AccountRenameUseCase
import com.kmnexus.codexmeter.domain.account.AccountSwitchUseCase
import com.kmnexus.codexmeter.domain.account.AccountSwitchRefreshRequester
import com.kmnexus.codexmeter.domain.account.CurrentAccountStateRepublisher
import com.kmnexus.codexmeter.domain.account.NoopAccountSwitchRefreshRequester
import com.kmnexus.codexmeter.domain.account.NoopCurrentAccountStateRepublisher
import com.kmnexus.codexmeter.domain.model.LocalAccountId
import com.kmnexus.codexmeter.domain.model.ProviderAccount
import com.kmnexus.codexmeter.domain.model.ProviderId
import java.time.Clock
import kotlinx.coroutines.CancellationException

class AccountMutationRepository(
    private val providerAccountDao: ProviderAccountDao,
    private val currentAccountStore: CurrentAccountStore,
    private val accountSwitchRefreshRequester: AccountSwitchRefreshRequester = NoopAccountSwitchRefreshRequester,
    private val currentAccountStateRepublisher: CurrentAccountStateRepublisher = NoopCurrentAccountStateRepublisher,
    private val clock: Clock,
) : AccountSwitchUseCase, AccountRenameUseCase {
    override suspend fun switchCurrentAccount(
        providerId: ProviderId,
        localAccountId: LocalAccountId,
    ): Boolean {
        val account = providerAccountDao.getById(localAccountId.value)
            ?.takeIf { it.providerId == providerId.value }
            ?.toDomain()
            ?: return false

        currentAccountStore.updateCurrentAccountSelection(
            CurrentAccountSelection(
                providerId = account.providerId,
                localAccountId = account.localAccountId,
            ),
        )
        runPostMutationEffect {
            accountSwitchRefreshRequester.requestAccountSwitchRefresh(account)
        }
        return true
    }

    override suspend fun renameAccount(
        providerId: ProviderId,
        localAccountId: LocalAccountId,
        displayName: String,
    ): ProviderAccount? {
        val existing = providerAccountDao.getById(localAccountId.value)
            ?.takeIf { it.providerId == providerId.value }
            ?: return null
        val renamed = existing.toDomain().renamedTo(displayName = displayName, updatedAt = clock.instant())
        val updatedRows = providerAccountDao.updateDisplayName(
            providerId = renamed.providerId.value,
            localAccountId = renamed.localAccountId.value,
            displayName = renamed.displayName,
            avatarInitial = renamed.avatarInitial,
            updatedAt = renamed.updatedAt.toEpochMilli(),
        )
        if (updatedRows == 0) return null

        val renamedAccount = providerAccountDao.getById(localAccountId.value)?.toDomain()
            ?: return null
        val currentSelection = currentAccountStore.currentAccountSelection()
        if (currentSelection?.providerId == providerId && currentSelection.localAccountId == localAccountId) {
            runPostMutationEffect {
                currentAccountStateRepublisher.republishCurrentAccountState(renamedAccount)
            }
        }

        return renamedAccount
    }

    private suspend fun runPostMutationEffect(effect: suspend () -> Unit) {
        try {
            effect()
        } catch (exception: CancellationException) {
            throw exception
        } catch (_: Exception) {
            // Persistence already succeeded; widget/refresh republish failures are recovered by
            // the next app open or refresh and must not roll back the user-visible mutation.
        }
    }
}
