package com.kmnexus.codexmeter.notification

import android.app.PendingIntent
import com.kmnexus.codexmeter.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceCodeLoginNotificationOrchestratorTest {
    private val orchestrator = DeviceCodeLoginNotificationOrchestrator()

    @Test
    fun `waiting notification uses account channel fixed id and copy action without embedding code in intent metadata`() {
        val request = orchestrator.waitingForAuthorization(
            attemptId = "attempt-new",
            userCode = "ABCD-EFGH",
            verificationUri = "https://auth.openai.com/codex/device",
        )

        assertEquals(NotificationOrchestrator.AUTH_LOGIN_NOTIFICATION_ID, request.notificationId)
        assertEquals(NotificationChannels.ACCOUNT_ERRORS_CHANNEL_ID, request.channelId)
        assertEquals(R.string.notification_auth_login_waiting_title, request.title.resourceId)
        assertEquals(R.string.notification_auth_login_waiting_body_format, request.body.resourceId)
        assertEquals(emptyList<Any>(), request.body.formatArgs)
        assertEquals(NotificationDestination.ExternalUrl, request.pendingIntent.destination)
        assertEquals("https://auth.openai.com/codex/device", request.pendingIntent.externalUrl)
        assertEquals(1, request.actions.size)
        val copyAction = request.actions.single()
        assertEquals(R.string.notification_auth_login_copy_code, copyAction.title.resourceId)
        assertEquals("attempt-new", copyAction.intent.attemptId)
        assertFalse(request.body.toString().contains("ABCD-EFGH"))
        assertFalse(copyAction.intent.toString().contains("ABCD-EFGH"))
        assertTrue(copyAction.intent.flags and PendingIntent.FLAG_IMMUTABLE != 0)
    }

    @Test
    fun `success and terminal notifications reuse same notification id`() {
        val success = orchestrator.connected(accountDisplayName = "Codex Main")
        val expired = orchestrator.expired()
        val failed = orchestrator.failed(safeMessageKey = "error_network")

        assertEquals(NotificationOrchestrator.AUTH_LOGIN_NOTIFICATION_ID, success.notificationId)
        assertEquals(success.notificationId, expired.notificationId)
        assertEquals(success.notificationId, failed.notificationId)
        assertFalse(success.ongoing)
        assertFalse(expired.ongoing)
        assertFalse(failed.ongoing)
    }

    @Test
    fun `copy registry returns code only for latest attempt`() {
        val registry = DeviceCodeLoginNotificationRegistry()
        registry.updateLatest(attemptId = "attempt-old", userCode = "OLD-CODE")
        registry.updateLatest(attemptId = "attempt-new", userCode = "NEW-CODE")

        assertEquals(null, registry.codeForAttempt("attempt-old"))
        assertEquals("NEW-CODE", registry.codeForAttempt("attempt-new"))
        registry.clear()
        assertEquals(null, registry.codeForAttempt("attempt-new"))
    }
}
