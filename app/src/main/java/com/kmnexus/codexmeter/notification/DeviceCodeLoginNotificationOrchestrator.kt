package com.kmnexus.codexmeter.notification

import android.app.PendingIntent
import com.kmnexus.codexmeter.R

class DeviceCodeLoginNotificationOrchestrator {
    fun waitingForAuthorization(
        attemptId: String,
        userCode: String,
        verificationUri: String,
    ): NotificationRequest =
        NotificationRequest(
            notificationId = NotificationOrchestrator.AUTH_LOGIN_NOTIFICATION_ID,
            channelId = NotificationChannels.ACCOUNT_ERRORS_CHANNEL_ID,
            title = NotificationText(R.string.notification_auth_login_waiting_title),
            body = NotificationText(R.string.notification_auth_login_waiting_body_format),
            pendingIntent = externalVerificationPendingIntent(
                requestCode = AUTH_LOGIN_CONTENT_REQUEST_CODE,
                verificationUri = verificationUri,
            ),
            ongoing = true,
            actions = listOf(
                NotificationAction(
                    title = NotificationText(R.string.notification_auth_login_copy_code),
                    intent = NotificationActionIntentMetadata(
                        requestCode = AUTH_LOGIN_COPY_REQUEST_CODE,
                        flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                        action = NotificationActionType.CopyDeviceCode,
                        attemptId = attemptId,
                    ),
                ),
            ),
        )

    fun connected(accountDisplayName: String?): NotificationRequest =
        NotificationRequest(
            notificationId = NotificationOrchestrator.AUTH_LOGIN_NOTIFICATION_ID,
            channelId = NotificationChannels.ACCOUNT_ERRORS_CHANNEL_ID,
            title = NotificationText(R.string.notification_auth_login_connected_title),
            body = NotificationText(
                resourceId = R.string.notification_auth_login_connected_body_format,
                formatArgs = listOf(accountDisplayName.orEmpty()),
            ),
            pendingIntent = addAccountPendingIntent(AUTH_LOGIN_CONTENT_REQUEST_CODE),
            ongoing = false,
            timeoutAfterMillis = CONNECTED_TIMEOUT_MILLIS,
        )

    fun expired(): NotificationRequest =
        terminalRequest(
            title = R.string.notification_auth_login_expired_title,
            body = R.string.notification_auth_login_expired_body,
        )

    fun failed(safeMessageKey: String): NotificationRequest =
        terminalRequest(
            title = R.string.notification_auth_login_failed_title,
            body = R.string.notification_auth_login_failed_body,
            bodyArgs = listOf(safeMessageKey),
        )

    fun validationFailed(safeMessageKey: String): NotificationRequest =
        terminalRequest(
            title = R.string.notification_auth_login_validation_failed_title,
            body = R.string.notification_auth_login_validation_failed_body,
            bodyArgs = listOf(safeMessageKey),
        )

    private fun terminalRequest(
        title: Int,
        body: Int,
        bodyArgs: List<Any> = emptyList(),
    ): NotificationRequest =
        NotificationRequest(
            notificationId = NotificationOrchestrator.AUTH_LOGIN_NOTIFICATION_ID,
            channelId = NotificationChannels.ACCOUNT_ERRORS_CHANNEL_ID,
            title = NotificationText(title),
            body = NotificationText(resourceId = body, formatArgs = bodyArgs),
            pendingIntent = addAccountPendingIntent(AUTH_LOGIN_CONTENT_REQUEST_CODE),
            ongoing = false,
        )

    private fun addAccountPendingIntent(requestCode: Int): NotificationPendingIntentMetadata =
        NotificationPendingIntentMetadata(
            requestCode = requestCode,
            flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            destination = NotificationDestination.AddAccount,
        )

    private fun externalVerificationPendingIntent(
        requestCode: Int,
        verificationUri: String,
    ): NotificationPendingIntentMetadata =
        NotificationPendingIntentMetadata(
            requestCode = requestCode,
            flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            destination = NotificationDestination.ExternalUrl,
            externalUrl = verificationUri,
        )

    private companion object {
        const val AUTH_LOGIN_CONTENT_REQUEST_CODE = 41
        const val AUTH_LOGIN_COPY_REQUEST_CODE = 42
        const val CONNECTED_TIMEOUT_MILLIS = 5_000L
    }
}

open class DeviceCodeLoginNotificationRegistry {
    private var latestAttemptId: String? = null
    private var latestUserCode: String? = null

    @Synchronized
    fun updateLatest(attemptId: String, userCode: String) {
        latestAttemptId = attemptId
        latestUserCode = userCode
    }

    @Synchronized
    fun codeForAttempt(attemptId: String?): String? =
        latestUserCode.takeIf { attemptId != null && attemptId == latestAttemptId }

    @Synchronized
    fun clear() {
        latestAttemptId = null
        latestUserCode = null
    }
}

object GlobalDeviceCodeLoginNotificationRegistry : DeviceCodeLoginNotificationRegistry()
