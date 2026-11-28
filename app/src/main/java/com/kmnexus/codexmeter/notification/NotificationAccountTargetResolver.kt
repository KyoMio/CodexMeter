package com.kmnexus.codexmeter.notification

import com.kmnexus.codexmeter.data.local.dao.ProviderAccountDao
import com.kmnexus.codexmeter.data.preferences.CurrentAccountSelection
import com.kmnexus.codexmeter.data.preferences.CurrentAccountReader
import com.kmnexus.codexmeter.domain.model.AccountStatus
import com.kmnexus.codexmeter.domain.settings.NotificationPreferenceReader

/**
 * Resolves which account the persistent status notification targets, right now.
 *
 * CM-10: the resolution must be a single, shared implementation — the state loader builds
 * notification content from it, and the publisher re-validates against it immediately before
 * posting. If the two ever drift apart, a stale account's title can be posted after the user
 * already switched away. Wiring both sides to this one resolver keeps them in lockstep.
 *
 * Resolution mirrors the epoch/notification wiring in AppContainer: a pinned account wins when
 * it still exists and is not deleted; otherwise the current default selection applies.
 */
class NotificationAccountTargetResolver(
    private val notificationPreferenceReader: NotificationPreferenceReader,
    private val providerAccountDao: ProviderAccountDao,
    private val currentAccountReader: CurrentAccountReader,
) {
    suspend fun resolveTargetSelection(): CurrentAccountSelection? {
        val pinned = notificationPreferenceReader.notificationPreferences().persistentNotificationAccount
        if (pinned != null) {
            val pinnedLive = providerAccountDao.getById(pinned.localAccountId.value)
                ?.takeIf {
                    it.providerId == pinned.providerId.value &&
                        !it.status.equals(AccountStatus.Deleted.name, ignoreCase = true)
                }
            if (pinnedLive != null) {
                return CurrentAccountSelection(
                    providerId = pinned.providerId,
                    localAccountId = pinned.localAccountId,
                )
            }
        }
        return currentAccountReader.currentAccountSelection()
    }
}
