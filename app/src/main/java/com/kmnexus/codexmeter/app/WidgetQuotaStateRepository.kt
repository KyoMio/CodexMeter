package com.kmnexus.codexmeter.app

import com.kmnexus.codexmeter.data.local.dao.ProviderAccountDao
import com.kmnexus.codexmeter.data.local.dao.QuotaSnapshotDao
import com.kmnexus.codexmeter.data.local.dao.RefreshAttemptDao
import com.kmnexus.codexmeter.data.repository.toDomain
import com.kmnexus.codexmeter.domain.model.AccountStatus
import com.kmnexus.codexmeter.domain.model.QuotaWindowId
import com.kmnexus.codexmeter.domain.quota.CurrentQuotaStateFactory
import com.kmnexus.codexmeter.domain.settings.NotificationPreferenceReader
import com.kmnexus.codexmeter.domain.settings.NotificationPreferences
import com.kmnexus.codexmeter.widget.WidgetQuotaConfiguration
import com.kmnexus.codexmeter.widget.WidgetQuotaState
import com.kmnexus.codexmeter.widget.WidgetQuotaStateFactory
import com.kmnexus.codexmeter.widget.WidgetQuotaStateLoader
import java.time.Clock

internal class WidgetQuotaStateRepository(
    private val providerAccountDao: ProviderAccountDao,
    private val quotaSnapshotDao: QuotaSnapshotDao,
    private val refreshAttemptDao: RefreshAttemptDao,
    private val currentQuotaStateFactory: CurrentQuotaStateFactory = CurrentQuotaStateFactory(),
    private val widgetQuotaStateFactory: WidgetQuotaStateFactory = WidgetQuotaStateFactory(),
    private val notificationPreferenceReader: NotificationPreferenceReader = DefaultWidgetNotificationPreferenceReader,
    private val clock: Clock,
) : WidgetQuotaStateLoader {
    override suspend fun loadWidgetQuotaState(configuration: WidgetQuotaConfiguration): WidgetQuotaState {
        val providerId = configuration.providerId?.takeIf { it.isNotBlank() }
        val localAccountId = configuration.localAccountId?.takeIf { it.isNotBlank() }
        if (providerId == null || localAccountId == null) {
            return widgetQuotaStateFactory.unconfigured(hasAccounts = hasSelectableAccount())
        }
        val account = providerAccountDao.getById(localAccountId)
            ?.toDomain()
            ?.takeIf { it.providerId.value == providerId }
            ?: return widgetQuotaStateFactory.unconfigured(hasAccounts = hasSelectableAccount())

        val latestSnapshot = quotaSnapshotDao.getLatestForAccount(
            providerId = account.providerId.value,
            localAccountId = account.localAccountId.value,
        )?.toDomain()
        val latestAttempt = refreshAttemptDao.getLatestForAccount(
            providerId = account.providerId.value,
            localAccountId = account.localAccountId.value,
        )?.toDomain()
        // primaryWindow 用于 CurrentQuotaState 内部计算；取第一个已选窗口或默认。
        val primaryWindowId = configuration.selectedWindowIds.firstOrNull()
            ?.let(::QuotaWindowId)
            ?: DEFAULT_WIDGET_PRIMARY_WINDOW_ID
        val currentQuotaState = currentQuotaStateFactory.create(
            account = account,
            latestSnapshot = latestSnapshot,
            latestAttempt = latestAttempt,
            now = clock.instant(),
            primaryWindowId = primaryWindowId,
        )
        return widgetQuotaStateFactory.create(
            state = currentQuotaState,
            notificationPreferences = notificationPreferenceReader.notificationPreferences(),
            selectedWindowIds = configuration.selectedWindowIds,
        )
    }

    private suspend fun hasSelectableAccount(): Boolean =
        providerAccountDao.listAll().any { it.toDomain().status != AccountStatus.Deleted }
}

private object DefaultWidgetNotificationPreferenceReader : NotificationPreferenceReader {
    override suspend fun notificationPreferences(): NotificationPreferences = NotificationPreferences()
}

private val DEFAULT_WIDGET_PRIMARY_WINDOW_ID = QuotaWindowId("five_hour")
