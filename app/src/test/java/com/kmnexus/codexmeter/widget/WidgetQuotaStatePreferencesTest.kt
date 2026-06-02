package com.kmnexus.codexmeter.widget

import androidx.datastore.preferences.core.mutablePreferencesOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class WidgetQuotaStatePreferencesTest {
    @Test
    fun `state round trips fields in order`() {
        val state = WidgetQuotaState(
            status = WidgetQuotaStatus.Fresh,
            providerName = "Codex",
            providerId = "codex",
            localAccountId = "acc-1",
            accountName = "个人号",
            tone = WidgetQuotaTone.Success,
            clickTarget = WidgetClickTarget.Home,
            fields = listOf(
                WidgetField("five_hour", false, 87, null, null, Instant.parse("2026-06-02T14:30:00Z"), WidgetQuotaTone.Success),
                WidgetField("balance", true, null, "8.50", "USD", null, WidgetQuotaTone.Neutral),
            ),
            isUnconfigured = false,
            hasAccounts = true,
            providerIconRes = null,
        )
        val prefs = mutablePreferencesOf().apply { writeWidgetQuotaState(state) }
        val restored = prefs.toWidgetQuotaState()

        assertEquals(2, restored.fields.size)
        assertEquals("five_hour", restored.fields[0].windowId)
        assertEquals(87, restored.fields[0].percent)
        assertEquals(Instant.parse("2026-06-02T14:30:00Z"), restored.fields[0].resetAt)
        assertTrue(restored.fields[1].isBalance)
        assertEquals("8.50", restored.fields[1].balanceAmount)
        assertEquals("USD", restored.fields[1].balanceCurrency)
    }

    @Test
    fun `unconfigured flag round trips`() {
        val state = WidgetQuotaState(
            status = WidgetQuotaStatus.NoAccount,
            providerName = "CodexMeter",
            providerId = null,
            localAccountId = null,
            accountName = null,
            tone = WidgetQuotaTone.Neutral,
            clickTarget = WidgetClickTarget.Home,
            fields = emptyList(),
            isUnconfigured = true,
            hasAccounts = false,
        )
        val prefs = mutablePreferencesOf().apply { writeWidgetQuotaState(state) }
        val restored = prefs.toWidgetQuotaState()
        assertTrue(restored.isUnconfigured)
        assertFalse(restored.hasAccounts)
        assertTrue(restored.fields.isEmpty())
    }

    @Test
    fun `configuration round trips selected window ids in order`() {
        val config = WidgetQuotaConfiguration(
            providerId = "codex",
            localAccountId = "acc-1",
            selectedWindowIds = listOf("five_hour", "weekly", "balance"),
        )
        val prefs = mutablePreferencesOf().apply { writeWidgetQuotaConfiguration(config) }
        val restored = prefs.toWidgetQuotaConfiguration()
        assertEquals(listOf("five_hour", "weekly", "balance"), restored.selectedWindowIds)
        assertEquals("acc-1", restored.localAccountId)
    }

    @Test
    fun `clearing matching account resets to unconfigured`() {
        val state = WidgetQuotaState(
            status = WidgetQuotaStatus.Fresh,
            providerName = "Codex",
            providerId = "codex",
            localAccountId = "acc-1",
            accountName = "个人号",
            tone = WidgetQuotaTone.Success,
            clickTarget = WidgetClickTarget.Home,
            fields = listOf(WidgetField("five_hour", false, 87, null, null, null, WidgetQuotaTone.Success)),
        )
        val prefs = mutablePreferencesOf().apply {
            writeWidgetQuotaState(state)
            writeWidgetQuotaConfiguration(WidgetQuotaConfiguration("codex", "acc-1", listOf("five_hour")))
        }
        val cleared = prefs.clearWidgetQuotaStateIfAccountMatches("codex", "acc-1")
        assertTrue(cleared)
        val restored = prefs.toWidgetQuotaState()
        assertTrue(restored.isUnconfigured)
        assertTrue(restored.fields.isEmpty())
        assertTrue(prefs.toWidgetQuotaConfiguration().selectedWindowIds.isEmpty())
    }
}
