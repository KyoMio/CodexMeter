package com.kmnexus.codexmeter.notification

import com.kmnexus.codexmeter.R
import com.kmnexus.codexmeter.domain.update.AppUpdateInfo
import org.junit.Assert.assertEquals
import org.junit.Test

class AppUpdateNotificationOrchestratorTest {
    @Test fun `builds a deep-linking update notification on the updates channel`() {
        val request = AppUpdateNotificationOrchestrator().updateAvailable(
            AppUpdateInfo("1.4.0", "https://r/page", "https://r/app.apk", "app.apk"),
        )
        assertEquals(NotificationOrchestrator.APP_UPDATE_NOTIFICATION_ID, request.notificationId)
        assertEquals(NotificationChannels.APP_UPDATES_CHANNEL_ID, request.channelId)
        assertEquals(NotificationDestination.AppUpdate, request.pendingIntent.destination)
        assertEquals(listOf<Any>("1.4.0"), request.body.formatArgs)
        assertEquals(R.string.notification_app_update_title, request.title.resourceId)
        assertEquals(false, request.ongoing)
    }
}
