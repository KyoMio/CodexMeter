package com.kmnexus.codexmeter.domain.quota

import com.kmnexus.codexmeter.domain.model.QuotaWindowId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    @Test
    fun `availability isPresentable is true only for Available and Depleted`() {
        assertTrue(QuotaWindowAvailability.Available.isPresentable)
        assertTrue(QuotaWindowAvailability.Depleted.isPresentable)
        assertFalse(QuotaWindowAvailability.Missing.isPresentable)
        assertFalse(QuotaWindowAvailability.DecodeFailed.isPresentable)
        assertFalse(QuotaWindowAvailability.Unsupported.isPresentable)
    }

    @Test
    fun `hasDisplayableValue checks presentability and kind-specific values`() {
        assertTrue(quotaWindow(usedPercent = 50, availability = QuotaWindowAvailability.Available).hasDisplayableValue())
        assertTrue(quotaWindow(usedPercent = 100, availability = QuotaWindowAvailability.Depleted).hasDisplayableValue())
        assertFalse(quotaWindow(usedPercent = null, availability = QuotaWindowAvailability.Depleted).hasDisplayableValue())
        assertFalse(quotaWindow(usedPercent = 50, availability = QuotaWindowAvailability.Missing).hasDisplayableValue())
        assertFalse(quotaWindow(usedPercent = 50, availability = QuotaWindowAvailability.DecodeFailed).hasDisplayableValue())
        assertFalse(quotaWindow(usedPercent = 50, availability = QuotaWindowAvailability.Unsupported).hasDisplayableValue())

        val depletedMultiModel = quotaWindow(usedPercent = 100, availability = QuotaWindowAvailability.Depleted)
            .copy(displayKind = QuotaWindowDisplayKind.MultiModelFraction)
        assertTrue(depletedMultiModel.hasDisplayableValue())
        assertFalse(depletedMultiModel.copy(usedPercent = null).hasDisplayableValue())

        val depletedBalance = QuotaWindow(
            windowId = QuotaWindowId("balance"),
            titleKey = "balance",
            usedPercent = null,
            resetAt = null,
            limitWindowSeconds = null,
            isPrimaryCandidate = true,
            availability = QuotaWindowAvailability.Depleted,
            displayKind = QuotaWindowDisplayKind.Balance,
            balanceAmount = "0.00",
        )
        assertTrue(depletedBalance.hasDisplayableValue())
        assertFalse(depletedBalance.copy(balanceAmount = null).hasDisplayableValue())
        assertFalse(depletedBalance.copy(balanceAmount = "   ").hasDisplayableValue())
        assertFalse(depletedBalance.copy(availability = QuotaWindowAvailability.Missing).hasDisplayableValue())

        val usageCount = QuotaWindow(
            windowId = QuotaWindowId("usage"),
            titleKey = "usage",
            usedPercent = null,
            resetAt = null,
            limitWindowSeconds = null,
            isPrimaryCandidate = true,
            availability = QuotaWindowAvailability.Depleted,
            displayKind = QuotaWindowDisplayKind.UsageCount,
            usedCount = 500,
            limitCount = 500,
        )
        assertTrue(usageCount.hasDisplayableValue())
        assertTrue(usageCount.copy(usedPercent = 100, usedCount = null, limitCount = null).hasDisplayableValue())
        assertFalse(usageCount.copy(usedCount = null).hasDisplayableValue())
        assertFalse(usageCount.copy(limitCount = null).hasDisplayableValue())
        assertFalse(usageCount.copy(availability = QuotaWindowAvailability.Missing).hasDisplayableValue())
    }

    @Test
    fun `canAlert allows depleted percent, multi-model, and usage count windows while preserving zero balance contract`() {
        val depletedPercent = quotaWindow(usedPercent = 100, availability = QuotaWindowAvailability.Depleted)
        assertTrue(depletedPercent.canAlert())
        assertFalse(depletedPercent.copy(resetAt = null).canAlert())

        val depletedMultiModel = depletedPercent.copy(displayKind = QuotaWindowDisplayKind.MultiModelFraction)
        assertTrue(depletedMultiModel.canAlert())

        val depletedUsageCount = QuotaWindow(
            windowId = QuotaWindowId("usage"),
            titleKey = "usage",
            usedPercent = null,
            resetAt = null,
            limitWindowSeconds = null,
            isPrimaryCandidate = true,
            availability = QuotaWindowAvailability.Depleted,
            displayKind = QuotaWindowDisplayKind.UsageCount,
            usedCount = 500,
            limitCount = 500,
        )
        assertTrue(depletedUsageCount.canAlert())
        assertFalse(depletedUsageCount.copy(usedCount = 0).canAlert())

        val zeroDepletedBalance = QuotaWindow(
            windowId = QuotaWindowId("balance"),
            titleKey = "balance",
            usedPercent = null,
            resetAt = null,
            limitWindowSeconds = null,
            isPrimaryCandidate = true,
            availability = QuotaWindowAvailability.Depleted,
            displayKind = QuotaWindowDisplayKind.Balance,
            balanceAmount = "0.00",
        )
        assertFalse(zeroDepletedBalance.canAlert())
        assertTrue(zeroDepletedBalance.copy(balanceAmount = "1.50").canAlert())
        assertFalse(depletedPercent.copy(availability = QuotaWindowAvailability.Missing).canAlert())
    }

    private fun quotaWindow(
        usedPercent: Int?,
        availability: QuotaWindowAvailability = QuotaWindowAvailability.Available,
    ): QuotaWindow =
        QuotaWindow(
            windowId = QuotaWindowId("five_hour"),
            titleKey = "quota_window_five_hour",
            usedPercent = usedPercent,
            resetAt = Instant.parse("2026-05-22T05:00:00Z"),
            limitWindowSeconds = 18_000,
            isPrimaryCandidate = true,
            availability = availability,
        )
}
