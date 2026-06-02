package com.kmnexus.codexmeter.app

import com.kmnexus.codexmeter.data.local.dao.QuotaSnapshotDao
import com.kmnexus.codexmeter.data.preferences.CurrentAccountReader
import com.kmnexus.codexmeter.data.repository.toDomain
import com.kmnexus.codexmeter.domain.model.LocalAccountId
import com.kmnexus.codexmeter.domain.model.ProviderId
import com.kmnexus.codexmeter.domain.model.QuotaWindowId

/** A selectable notification window identified by its stable windowId. */
data class NotificationWindowChoice(
    val windowId: QuotaWindowId,
)

fun interface NotificationWindowChoicesLoader {
    /** Latest snapshot windows for [providerId]/[localAccountId]; null args -> current account. */
    suspend fun windowChoices(providerId: ProviderId?, localAccountId: LocalAccountId?): List<NotificationWindowChoice>
}

class DefaultNotificationWindowChoicesLoader(
    private val currentAccountReader: CurrentAccountReader,
    private val quotaSnapshotDao: QuotaSnapshotDao,
) : NotificationWindowChoicesLoader {
    override suspend fun windowChoices(
        providerId: ProviderId?,
        localAccountId: LocalAccountId?,
    ): List<NotificationWindowChoice> {
        val resolvedProvider: ProviderId
        val resolvedLocal: LocalAccountId
        if (providerId != null && localAccountId != null) {
            resolvedProvider = providerId
            resolvedLocal = localAccountId
        } else {
            val selection = currentAccountReader.currentAccountSelection() ?: return emptyList()
            resolvedProvider = selection.providerId
            resolvedLocal = selection.localAccountId
        }
        val snapshot = quotaSnapshotDao.getLatestForAccount(
            providerId = resolvedProvider.value,
            localAccountId = resolvedLocal.value,
        )?.toDomain() ?: return emptyList()
        return snapshot.windows.map { NotificationWindowChoice(it.windowId) }
    }
}
