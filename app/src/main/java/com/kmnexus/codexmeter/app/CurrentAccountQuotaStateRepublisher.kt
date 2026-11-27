package com.kmnexus.codexmeter.app

import com.kmnexus.codexmeter.core.concurrency.VersionedStateGuard
import com.kmnexus.codexmeter.data.local.dao.ProviderAccountDao
import com.kmnexus.codexmeter.data.local.dao.QuotaSnapshotDao
import com.kmnexus.codexmeter.data.local.dao.RefreshAttemptDao
import com.kmnexus.codexmeter.data.preferences.CurrentAccountReader
import com.kmnexus.codexmeter.data.repository.toDomain
import com.kmnexus.codexmeter.domain.account.CurrentAccountStateRepublisher
import com.kmnexus.codexmeter.domain.model.ProviderAccount
import com.kmnexus.codexmeter.domain.quota.CurrentQuotaStateFactory
import com.kmnexus.codexmeter.domain.settings.DefaultPrimaryQuotaWindowPreferenceReader
import com.kmnexus.codexmeter.domain.settings.PrimaryQuotaWindowPreferenceReader
import com.kmnexus.codexmeter.refresh.CurrentQuotaStatePublisher
import java.time.Clock

internal class CurrentAccountQuotaStateRepublisher(
    private val currentAccountReader: CurrentAccountReader,
    private val providerAccountDao: ProviderAccountDao,
    private val quotaSnapshotDao: QuotaSnapshotDao,
    private val refreshAttemptDao: RefreshAttemptDao,
    private val currentQuotaStatePublisher: CurrentQuotaStatePublisher,
    private val currentQuotaStateFactory: CurrentQuotaStateFactory = CurrentQuotaStateFactory(),
    private val primaryQuotaWindowPreferenceReader: PrimaryQuotaWindowPreferenceReader =
        DefaultPrimaryQuotaWindowPreferenceReader,
    private val clock: Clock = Clock.systemUTC(),
    private val stateGuard: VersionedStateGuard,
) : CurrentAccountStateRepublisher {
    override suspend fun republishCurrentAccountState(account: ProviderAccount) {
        // Capture the epoch before the durable reads below. If another local edit or an account
        // switch interleaves with those suspending reads, the rebuilt state is stale before it is
        // even assembled, and commitIfFresh drops it instead of flickering old data onto surfaces.
        val epoch = stateGuard.captureReadToken(account.localAccountId)
        val currentSelection = currentAccountReader.currentAccountSelection() ?: return
        if (currentSelection.providerId != account.providerId ||
            currentSelection.localAccountId != account.localAccountId
        ) {
            return
        }

        val persistedAccount = providerAccountDao.getById(account.localAccountId.value)
            ?.toDomain()
            ?.takeIf { it.providerId == account.providerId }
            ?: return
        val latestSnapshot = quotaSnapshotDao.getLatestForAccount(
            providerId = persistedAccount.providerId.value,
            localAccountId = persistedAccount.localAccountId.value,
        )?.toDomain()
        val latestAttempt = refreshAttemptDao.getLatestForAccount(
            providerId = persistedAccount.providerId.value,
            localAccountId = persistedAccount.localAccountId.value,
        )?.toDomain()

        // Re-read the active selection inside commitIfFresh's mutex so a concurrent switch cannot
        // race between the read and the publish.
        stateGuard.commitIfFresh(
            epoch = epoch,
            selectedAccountIdProvider = {
                currentAccountReader.currentAccountSelection()?.localAccountId
            },
        ) {
            currentQuotaStatePublisher.publish(
                currentQuotaStateFactory.create(
                    account = persistedAccount,
                    latestSnapshot = latestSnapshot,
                    latestAttempt = latestAttempt,
                    now = clock.instant(),
                    primaryWindowId = primaryQuotaWindowPreferenceReader.primaryQuotaWindowId(),
                ),
            )
        }
    }
}
