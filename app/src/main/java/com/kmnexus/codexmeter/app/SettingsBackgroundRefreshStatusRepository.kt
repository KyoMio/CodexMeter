package com.kmnexus.codexmeter.app

import com.kmnexus.codexmeter.data.local.dao.ProviderAccountDao
import com.kmnexus.codexmeter.data.local.dao.RefreshAttemptDao
import com.kmnexus.codexmeter.data.preferences.CurrentAccountReader
import com.kmnexus.codexmeter.ui.settings.SettingsBackgroundRefreshStatus
import com.kmnexus.codexmeter.ui.settings.SettingsBackgroundRefreshStatusReader

internal class SettingsBackgroundRefreshStatusRepository(
    private val currentAccountReader: CurrentAccountReader,
    private val providerAccountDao: ProviderAccountDao,
    private val refreshAttemptDao: RefreshAttemptDao,
) : SettingsBackgroundRefreshStatusReader {
    override suspend fun latestBackgroundRefreshStatus(): SettingsBackgroundRefreshStatus {
        val selection = currentAccountReader.currentAccountSelection()
            ?: return SettingsBackgroundRefreshStatus.NoCurrentAccount
        val account = providerAccountDao.getById(selection.localAccountId.value)
            ?.takeIf { it.providerId == selection.providerId.value }
            ?: return SettingsBackgroundRefreshStatus.NoCurrentAccount
        val attempt = refreshAttemptDao.getLatestForAccountByTrigger(
            providerId = account.providerId,
            localAccountId = account.localAccountId,
            trigger = PERIODIC_TRIGGER,
        ) ?: return SettingsBackgroundRefreshStatus.NoAttempts

        return when (attempt.status) {
            "success" -> SettingsBackgroundRefreshStatus.Success
            "failed" -> if (attempt.retryable == true && attempt.userActionRequired != true) {
                SettingsBackgroundRefreshStatus.Retrying
            } else {
                SettingsBackgroundRefreshStatus.Failed
            }
            "skipped" -> SettingsBackgroundRefreshStatus.Skipped
            "cancelled" -> SettingsBackgroundRefreshStatus.Cancelled
            else -> SettingsBackgroundRefreshStatus.Failed
        }
    }

    private companion object {
        const val PERIODIC_TRIGGER = "periodic"
    }
}
