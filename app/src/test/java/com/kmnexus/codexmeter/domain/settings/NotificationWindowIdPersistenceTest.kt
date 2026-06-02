package com.kmnexus.codexmeter.domain.settings

import com.kmnexus.codexmeter.domain.model.QuotaWindowId
import org.junit.Assert.assertEquals
import org.junit.Test

class NotificationWindowIdPersistenceTest {
    @Test
    fun `accepts a non-supported window id without throwing`() {
        val prefs = NotificationPreferences(persistentNotificationWindowId = QuotaWindowId("balance"))
        assertEquals(QuotaWindowId("balance"), prefs.persistentNotificationWindowId)
    }
}
