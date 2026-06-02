package com.kmnexus.codexmeter.domain.quota

import com.kmnexus.codexmeter.domain.model.QuotaWindowId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class QuotaModelsTest {
    @Test
    fun `display percent shows remaining quota and clamps low usage values`() {
        assertEquals(100, quotaWindow(usedPercent = -1).displayPercent)
    }

    @Test
    fun `display percent shows remaining quota for in range usage values`() {
        assertEquals(38, quotaWindow(usedPercent = 62).displayPercent)
    }

    @Test
    fun `display percent clamps high usage values to zero remaining`() {
        assertEquals(0, quotaWindow(usedPercent = 120).displayPercent)
    }

    @Test
    fun `display percent stays null when used percent is null`() {
        assertNull(quotaWindow(usedPercent = null).displayPercent)
    }

    @Test
    fun `raw used percent is not clamped`() {
        val window = quotaWindow(usedPercent = 120)

        assertEquals(120, window.usedPercent)
        assertEquals(0, window.displayPercent)
    }

    @Test
    fun quotaWindow_displayKind_defaultsToPercent() {
        val window = QuotaWindow(
            windowId = QuotaWindowId("test"), titleKey = "test_window",
            usedPercent = 50, resetAt = null, limitWindowSeconds = null,
            isPrimaryCandidate = true, availability = QuotaWindowAvailability.Available)
        assertEquals(QuotaWindowDisplayKind.Percent, window.displayKind)
    }

    @Test
    fun quotaWindow_balanceDisplayKind_returnsBalanceAmount() {
        val window = QuotaWindow(windowId = QuotaWindowId("balance"), titleKey = "balance",
            usedPercent = null, resetAt = null, limitWindowSeconds = null,
            isPrimaryCandidate = true, availability = QuotaWindowAvailability.Available,
            displayKind = QuotaWindowDisplayKind.Balance, balanceAmount = "9.49", balanceCurrency = "CNY")
        assertEquals(QuotaWindowDisplayKind.Balance, window.displayKind)
        assertEquals("9.49", window.balanceAmount)
        assertEquals("CNY", window.balanceCurrency)
    }

    @Test
    fun quotaWindow_usageCountDisplayKind_returnsCounts() {
        val window = QuotaWindow(windowId = QuotaWindowId("usage"), titleKey = "usage",
            usedPercent = null, resetAt = null, limitWindowSeconds = null,
            isPrimaryCandidate = true, availability = QuotaWindowAvailability.Available,
            displayKind = QuotaWindowDisplayKind.UsageCount, usedCount = 50, limitCount = 500)
        assertEquals(QuotaWindowDisplayKind.UsageCount, window.displayKind)
        assertEquals(50, window.usedCount)
        assertEquals(500, window.limitCount)
    }

    @Test
    fun quotaWindow_subLabel_returnsSubLabel() {
        val window = QuotaWindow(windowId = QuotaWindowId("test"), titleKey = "test",
            usedPercent = null, resetAt = null, limitWindowSeconds = null,
            isPrimaryCandidate = true, availability = QuotaWindowAvailability.Available,
            displayKind = QuotaWindowDisplayKind.Balance, balanceAmount = "9.49",
            balanceCurrency = "CNY", subLabel = "Granted ¥0.00 · Topped up ¥9.49")
        assertEquals("Granted ¥0.00 · Topped up ¥9.49", window.subLabel)
    }

    @Test
    fun quotaWindow_modelBuckets_returnsBuckets() {
        val bucket = QuotaModelBucket(modelId = "claude-4-sonnet", displayName = "Claude 4 Sonnet",
            remainingFraction = 0.85, resetAt = Instant.parse("2026-06-01T00:00:00Z"))
        val window = QuotaWindow(windowId = QuotaWindowId("models"), titleKey = "models",
            usedPercent = null, resetAt = null, limitWindowSeconds = null,
            isPrimaryCandidate = true, availability = QuotaWindowAvailability.Available,
            displayKind = QuotaWindowDisplayKind.MultiModelFraction, modelBuckets = listOf(bucket))
        assertEquals(1, window.modelBuckets.size)
        assertEquals("claude-4-sonnet", window.modelBuckets[0].modelId)
    }

    @Test
    fun quotaSnapshotSource_newValuesExist() {
        // Verify new enum values compile and exist
        val apiKey = QuotaSnapshotSource.ApiKeyImport
        val cookie = QuotaSnapshotSource.CookieAuth
        val oauth = QuotaSnapshotSource.OAuthPkceLogin
        assertEquals("ApiKeyImport", apiKey.name)
        assertEquals("CookieAuth", cookie.name)
        assertEquals("OAuthPkceLogin", oauth.name)
    }

    private fun quotaWindow(usedPercent: Int?): QuotaWindow =
        QuotaWindow(
            windowId = QuotaWindowId("five_hour"),
            titleKey = "quota_window_five_hour",
            usedPercent = usedPercent,
            resetAt = Instant.parse("2026-05-22T05:00:00Z"),
            limitWindowSeconds = 18_000,
            isPrimaryCandidate = true,
            availability = QuotaWindowAvailability.Available,
        )
}
