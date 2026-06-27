package com.kmnexus.codexmeter.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.annotation.StringRes
import com.kmnexus.codexmeter.R

data class NotificationChannelDefinition(
    val id: String,
    @field:StringRes val nameResId: Int,
    @field:StringRes val descriptionResId: Int,
    val importance: Int,
    val showBadge: Boolean,
)

object NotificationChannels {
    const val STATUS_CHANNEL_ID = "quota_status"
    const val QUOTA_ALERTS_CHANNEL_ID = "quota_alerts"
    const val ACCOUNT_ERRORS_CHANNEL_ID = "account_errors"
    const val APP_UPDATES_CHANNEL_ID = "app_updates"

    val definitions: List<NotificationChannelDefinition> = listOf(
        NotificationChannelDefinition(
            id = STATUS_CHANNEL_ID,
            nameResId = R.string.notification_channel_status_name,
            descriptionResId = R.string.notification_channel_status_description,
            importance = NotificationManager.IMPORTANCE_LOW,
            showBadge = false,
        ),
        NotificationChannelDefinition(
            id = QUOTA_ALERTS_CHANNEL_ID,
            nameResId = R.string.notification_channel_quota_alerts_name,
            descriptionResId = R.string.notification_channel_quota_alerts_description,
            importance = NotificationManager.IMPORTANCE_DEFAULT,
            showBadge = true,
        ),
        NotificationChannelDefinition(
            id = ACCOUNT_ERRORS_CHANNEL_ID,
            nameResId = R.string.notification_channel_account_errors_name,
            descriptionResId = R.string.notification_channel_account_errors_description,
            importance = NotificationManager.IMPORTANCE_DEFAULT,
            showBadge = true,
        ),
        NotificationChannelDefinition(
            id = APP_UPDATES_CHANNEL_ID,
            nameResId = R.string.notification_channel_app_updates_name,
            descriptionResId = R.string.notification_channel_app_updates_description,
            importance = NotificationManager.IMPORTANCE_DEFAULT,
            showBadge = true,
        ),
    )

    fun createAll(context: Context) {
        val notificationManager = context.getSystemService(NotificationManager::class.java) ?: return
        notificationManager.createNotificationChannels(
            definitions.map { it.toAndroidChannel(context) },
        )
    }
}

private fun NotificationChannelDefinition.toAndroidChannel(context: Context): NotificationChannel =
    NotificationChannel(
        id,
        context.getString(nameResId),
        importance,
    ).apply {
        description = context.getString(descriptionResId)
        setShowBadge(showBadge)
        if (id == NotificationChannels.STATUS_CHANNEL_ID) {
            setSound(null, null)
            enableVibration(false)
        }
    }
