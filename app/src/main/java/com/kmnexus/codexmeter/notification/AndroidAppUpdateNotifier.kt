package com.kmnexus.codexmeter.notification

import com.kmnexus.codexmeter.domain.update.AppUpdateInfo
import com.kmnexus.codexmeter.domain.update.AppUpdateNotifier

class AndroidAppUpdateNotifier(
    private val notificationSink: NotificationSink,
    private val orchestrator: AppUpdateNotificationOrchestrator = AppUpdateNotificationOrchestrator(),
) : AppUpdateNotifier {
    override fun notifyUpdateAvailable(update: AppUpdateInfo) {
        notificationSink.post(orchestrator.updateAvailable(update))
    }
}
