package com.kmnexus.codexmeter.notification

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
// Robolectric SDK 36 currently requires Java 21, while this project is pinned to Java 17.
@Config(sdk = [35])
class DeviceCodeLoginCopyCodeReceiverTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val receiver = DeviceCodeLoginCopyCodeReceiver()

    @After
    fun tearDown() {
        GlobalDeviceCodeLoginNotificationRegistry.clear()
        clipboard().clearPrimaryClip()
    }

    @Test
    fun `copy action writes only matching latest attempt code to clipboard`() {
        GlobalDeviceCodeLoginNotificationRegistry.updateLatest(
            attemptId = "attempt-new",
            userCode = "ABCD-EFGH",
        )

        receiver.onReceive(context, copyIntent("attempt-new"))

        assertEquals("ABCD-EFGH", clipboard().primaryClip?.getItemAt(0)?.text?.toString())
    }

    @Test
    fun `copy action ignores stale attempts`() {
        GlobalDeviceCodeLoginNotificationRegistry.updateLatest(
            attemptId = "attempt-new",
            userCode = "ABCD-EFGH",
        )

        receiver.onReceive(context, copyIntent("attempt-old"))

        assertNull(clipboard().primaryClip)
    }

    private fun copyIntent(attemptId: String): Intent =
        Intent(context, DeviceCodeLoginCopyCodeReceiver::class.java).apply {
            action = DeviceCodeLoginCopyCodeReceiver.ACTION_COPY_DEVICE_CODE
            putExtra(DeviceCodeLoginCopyCodeReceiver.EXTRA_ATTEMPT_ID, attemptId)
        }

    private fun clipboard(): ClipboardManager =
        context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
}
