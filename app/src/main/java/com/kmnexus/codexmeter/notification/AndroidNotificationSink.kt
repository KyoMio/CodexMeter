package com.kmnexus.codexmeter.notification

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.kmnexus.codexmeter.MainActivity
import com.kmnexus.codexmeter.R
import com.kmnexus.codexmeter.domain.settings.NotificationPreferenceReader
import com.kmnexus.codexmeter.ui.navigation.CodexMeterLaunchDestination
import com.kmnexus.codexmeter.ui.navigation.CodexMeterRoute

class AndroidNotificationSink(
    context: Context,
    private val renderer: AndroidNotificationRenderer = AndroidNotificationRenderer(context),
) : NotificationSink {
    private val appContext = context.applicationContext

    @SuppressLint("MissingPermission")
    override fun post(request: NotificationRequest) {
        NotificationChannels.createAll(appContext)
        NotificationManagerCompat.from(appContext).notify(
            request.notificationId,
            renderer.render(request),
        )
    }

    override fun cancel(notificationId: Int) {
        NotificationManagerCompat.from(appContext).cancel(notificationId)
    }
}

class AndroidNotificationRequestOptionsReader(
    context: Context,
    private val notificationPreferenceReader: NotificationPreferenceReader,
) : NotificationRequestOptionsReader {
    private val appContext = context.applicationContext

    override suspend fun currentOptions(): NotificationRequestOptions {
        val preferences = notificationPreferenceReader.notificationPreferences()
        return NotificationRequestOptions(
            notificationPermissionAvailable = hasNotificationPermission(),
            statusNotificationEnabled = preferences.statusNotificationEnabled,
            quotaAlertsEnabled = preferences.quotaAlertsEnabled,
            accountErrorsEnabled = preferences.accountErrorsEnabled,
        )
    }

    private fun hasNotificationPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                appContext,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
}

class NotificationPreferenceAlertThresholdsReader(
    private val notificationPreferenceReader: NotificationPreferenceReader,
) : AlertThresholdsReader {
    override suspend fun currentThresholds(): AlertThresholds {
        val preferences = notificationPreferenceReader.notificationPreferences()
        return AlertThresholds(
            caution = preferences.cautionThreshold,
            warning = preferences.warningThreshold,
            limit = preferences.limitThreshold,
            balanceCaution = preferences.balanceCautionThreshold,
            balanceWarning = preferences.balanceWarningThreshold,
        )
    }
}

class AndroidNotificationRenderer(
    context: Context,
) {
    private val appContext = context.applicationContext

    fun render(request: NotificationRequest): Notification {
        val title = request.title.resolve(appContext)
        val body = request.body.resolve(appContext)

        val builder = NotificationCompat.Builder(appContext, request.channelId)
            .setSmallIcon(R.drawable.ic_notification_status)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(contentPendingIntent(request.pendingIntent))
            .setOngoing(request.ongoing)
            .setOnlyAlertOnce(request.ongoing)
            .setAutoCancel(!request.ongoing)
            .setPriority(request.priority())

        request.timeoutAfterMillis?.let(builder::setTimeoutAfter)
        request.actions.forEach { action ->
            builder.addAction(
                R.drawable.ic_notification_status,
                action.title.resolve(appContext),
                actionPendingIntent(action.intent),
            )
        }

        return builder
            .build()
    }

    private fun contentPendingIntent(metadata: NotificationPendingIntentMetadata): PendingIntent {
        val intent = when (metadata.destination) {
            NotificationDestination.Home,
            NotificationDestination.AddAccount,
            NotificationDestination.AppUpdate,
            -> {
                val launchDestination = when (metadata.destination) {
                    NotificationDestination.Home -> CodexMeterLaunchDestination.Home
                    NotificationDestination.AddAccount -> CodexMeterLaunchDestination.AddAccount
                    NotificationDestination.AppUpdate -> CodexMeterLaunchDestination.SettingsUpdate
                    NotificationDestination.ExternalUrl -> CodexMeterLaunchDestination.Home
                }
                Intent(appContext, MainActivity::class.java).apply {
                    putExtra(CodexMeterRoute.EXTRA_LAUNCH_DESTINATION, launchDestination.value)
                }
            }
            NotificationDestination.ExternalUrl -> Intent(
                Intent.ACTION_VIEW,
                Uri.parse(metadata.externalUrl.orEmpty()),
            ).apply {
                addCategory(Intent.CATEGORY_BROWSABLE)
            }
        }
        return PendingIntent.getActivity(
            appContext,
            metadata.requestCode,
            intent,
            metadata.flags,
        )
    }

    private fun actionPendingIntent(metadata: NotificationActionIntentMetadata): PendingIntent {
        val intent = when (metadata.action) {
            NotificationActionType.CopyDeviceCode -> Intent(
                appContext,
                DeviceCodeLoginCopyCodeReceiver::class.java,
            ).apply {
                action = DeviceCodeLoginCopyCodeReceiver.ACTION_COPY_DEVICE_CODE
                putExtra(DeviceCodeLoginCopyCodeReceiver.EXTRA_ATTEMPT_ID, metadata.attemptId)
            }
        }
        return PendingIntent.getBroadcast(
            appContext,
            metadata.requestCode,
            intent,
            metadata.flags,
        )
    }
}

// Format args may themselves be NotificationText (e.g. status body = "<quota> · <status>"), so
// resolve them recursively before handing plain strings to getString.
private fun NotificationText.resolve(context: Context): String =
    context.getString(
        resourceId,
        *formatArgs.map { arg -> if (arg is NotificationText) arg.resolve(context) else arg }.toTypedArray(),
    )

private fun NotificationRequest.priority(): Int =
    if (channelId == NotificationChannels.STATUS_CHANNEL_ID) {
        NotificationCompat.PRIORITY_LOW
    } else {
        NotificationCompat.PRIORITY_DEFAULT
    }
