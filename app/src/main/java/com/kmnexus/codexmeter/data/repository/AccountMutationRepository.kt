package com.kmnexus.codexmeter.data.repository

import com.kmnexus.codexmeter.core.concurrency.VersionedStateGuard
import com.kmnexus.codexmeter.data.local.dao.ProviderAccountDao
import com.kmnexus.codexmeter.data.preferences.CurrentAccountSelection
import com.kmnexus.codexmeter.data.preferences.CurrentAccountStore
import com.kmnexus.codexmeter.domain.account.AccountRenameUseCase
import com.kmnexus.codexmeter.domain.account.AccountSwitchRefreshRequester
import com.kmnexus.codexmeter.domain.account.CurrentAccountStateRepublisher
import com.kmnexus.codexmeter.domain.account.DefaultAccountSelectUseCase
import com.kmnexus.codexmeter.domain.account.NoopAccountSwitchRefreshRequester
import com.kmnexus.codexmeter.domain.account.NoopCurrentAccountStateRepublisher
import com.kmnexus.codexmeter.domain.model.LocalAccountId
import com.kmnexus.codexmeter.domain.model.ProviderAccount
import com.kmnexus.codexmeter.domain.model.ProviderId
import java.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

class AccountMutationRepository(
    private val providerAccountDao: ProviderAccountDao,
    private val currentAccountStore: CurrentAccountStore,
    private val accountSelectRefreshRequester: AccountSwitchRefreshRequester = NoopAccountSwitchRefreshRequester,
    private val currentAccountStateRepublisher: CurrentAccountStateRepublisher = NoopCurrentAccountStateRepublisher,
    private val clock: Clock,
    private val refreshScope: CoroutineScope? = null,
    private val stateGuard: VersionedStateGuard,
    private val notificationTargetBoundary: com.kmnexus.codexmeter.notification.NotificationTargetBoundary =
        com.kmnexus.codexmeter.notification.NotificationTargetBoundary(),
) : DefaultAccountSelectUseCase, AccountRenameUseCase {
    /**
     * Completes once the chosen account is durably in the default-account slot — the single source
     * every consumer (Home, cold start, background-refresh status, notification fallback, widget
     * default) reads. This can take arbitrarily long (Room/DataStore may suspend), so callers that
     * want "selection is saved" must await this — not [selectDefaultAccount], which additionally
     * waits for the network refresh.
     */
    override suspend fun persistDefaultAccountSelection(
        providerId: ProviderId,
        localAccountId: LocalAccountId,
    ): Boolean {
        // beginEdit advances the generation BEFORE the durable write: any refresh epoch captured
        // earlier is stale the moment the switch starts, and a commit landing mid-switch is dropped
        // because inFlightEdits > 0. The epoch's account check (re-read at commit time) is the
        // second, independent layer that discards the write even after the edit completes.
        stateGuard.beginEdit()
        try {
            val account = providerAccountDao.getById(localAccountId.value)
                ?.takeIf { it.providerId == providerId.value }
                ?.toDomain()
                ?: return false

            // CM-10 R1: the target write is serialized against the notification posting boundary
            // (re-check + system post) — a post that already passed the re-check finishes BEFORE
            // this write proceeds, so a stale-target notification can never land after the
            // selection commits. Only the write itself is locked; the post-switch refresh runs
            // after this function returns and never nests commitMutex inside the boundary.
            notificationTargetBoundary.serialize {
                currentAccountStore.updateCurrentAccountSelection(
                    CurrentAccountSelection(
                        providerId = account.providerId,
                        localAccountId = account.localAccountId,
                    ),
                )
            }
            return true
        } finally {
            stateGuard.endEdit()
        }
    }

    /**
     * Post-selection refresh for the account. Runs on [refreshScope] (joined, so callers learn
     * when the refreshed snapshot is persisted); without a scope it runs inline.
     */
    override suspend fun refreshSelectedAccount(
        providerId: ProviderId,
        localAccountId: LocalAccountId,
    ) {
        val account = providerAccountDao.getById(localAccountId.value)
            ?.takeIf { it.providerId == providerId.value }
            ?.toDomain()
            ?: return
        val scope = refreshScope
        if (scope != null) {
            scope.launch {
                runPostMutationEffect {
                    accountSelectRefreshRequester.requestAccountSwitchRefresh(account)
                }
            }.join()
        } else {
            runPostMutationEffect {
                accountSelectRefreshRequester.requestAccountSwitchRefresh(account)
            }
        }
    }

    override suspend fun renameAccount(
        providerId: ProviderId,
        localAccountId: LocalAccountId,
        displayName: String,
    ): ProviderAccount? {
        // The generation advance invalidates every refresh epoch captured before the rename: a
        // provider response landing now would carry the pre-rename account object and must not be
        // published over the edited state. The republish below then re-reads the durable rows.
        stateGuard.beginEdit()
        val renamedAccount = try {
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

            providerAccountDao.getById(localAccountId.value)?.toDomain()
                ?: return null
        } finally {
            stateGuard.endEdit()
        }
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
