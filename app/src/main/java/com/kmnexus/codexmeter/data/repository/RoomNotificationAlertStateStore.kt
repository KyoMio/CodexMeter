package com.kmnexus.codexmeter.data.repository

import com.kmnexus.codexmeter.data.local.dao.AlertStateDao
import com.kmnexus.codexmeter.data.local.entity.AlertStateEntity
import com.kmnexus.codexmeter.notification.AlertDedupeKey
import com.kmnexus.codexmeter.notification.NotificationAlertStateStore
import com.kmnexus.codexmeter.notification.QuotaAlertEvent
import java.time.Instant

class RoomNotificationAlertStateStore(
    private val alertStateDao: AlertStateDao,
) : NotificationAlertStateStore {
    override suspend fun hasNotified(key: AlertDedupeKey): Boolean =
        alertStateDao.getByDedupeKey(
            providerId = key.providerId.value,
            localAccountId = key.localAccountId.value,
            windowId = key.windowId.value,
            resetAt = key.resetAt?.toString().orEmpty(),
            threshold = key.threshold,
        ) != null

    override suspend fun markNotified(event: QuotaAlertEvent, notifiedAt: Instant) {
        alertStateDao.upsert(
            AlertStateEntity(
                alertStateId = event.key.toAlertStateId(),
                providerId = event.key.providerId.value,
                localAccountId = event.key.localAccountId.value,
                windowId = event.key.windowId.value,
                threshold = event.key.threshold,
                resetAt = event.key.resetAt?.toString().orEmpty(),
                lastNotifiedAt = notifiedAt.toString(),
            ),
        )
    }

    private fun AlertDedupeKey.toAlertStateId(): String =
        listOf(
            providerId.value,
            localAccountId.value,
            windowId.value,
            (resetAt?.toEpochMilli()?.toString()).orEmpty(),
            threshold.toString(),
        ).joinToString(separator = "-")
}
