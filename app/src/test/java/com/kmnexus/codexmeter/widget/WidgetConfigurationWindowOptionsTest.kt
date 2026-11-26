package com.kmnexus.codexmeter.widget

import com.kmnexus.codexmeter.domain.model.QuotaWindowId
import com.kmnexus.codexmeter.domain.quota.QuotaWindow
import com.kmnexus.codexmeter.domain.quota.QuotaWindowAvailability
import com.kmnexus.codexmeter.domain.quota.QuotaWindowDisplayKind
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetConfigurationWindowOptionsTest {
    private val now: Instant = Instant.parse("2026-11-24T00:00:00Z")

    private fun window(
        id: String,
        availability: QuotaWindowAvailability,
        limitWindowSeconds: Int? = 18_000,
    ) = QuotaWindow(
        windowId = QuotaWindowId(id),
        titleKey = id,
        usedPercent = if (availability == QuotaWindowAvailability.Available) 50 else 100,
        resetAt = now,
        limitWindowSeconds = limitWindowSeconds,
        isPrimaryCandidate = false,
        availability = availability,
    )

    @Test
    fun `depleted window stays visible and selectable in options`() {
        val options = buildWidgetWindowOptions(
            listOf(
                window("antigravity_gemini_5h_window", QuotaWindowAvailability.Depleted),
            ),
        )
        assertEquals(listOf("antigravity_gemini_5h_window"), options.map { it.windowId })
        assertTrue(options.single().depleted)
    }

    @Test
    fun `available window is not flagged depleted`() {
        val options = buildWidgetWindowOptions(
            listOf(
                window("antigravity_gemini_5h_window", QuotaWindowAvailability.Available),
                window("antigravity_gemini_weekly_window", QuotaWindowAvailability.Depleted),
            ),
        )
        assertEquals(
            listOf("antigravity_gemini_5h_window", "antigravity_gemini_weekly_window"),
            options.map { it.windowId },
        )
        assertFalse(options[0].depleted)
        assertTrue(options[1].depleted)
    }

    @Test
    fun `missing and unsupported windows stay hidden`() {
        val options = buildWidgetWindowOptions(
            listOf(
                window("ghost_missing", QuotaWindowAvailability.Missing),
                window("ghost_unsupported", QuotaWindowAvailability.Unsupported),
                window("ghost_decode", QuotaWindowAvailability.DecodeFailed),
            ),
        )
        assertTrue(options.isEmpty())
    }

    @Test
    fun `duplicate window ids collapse to the first entry`() {
        val options = buildWidgetWindowOptions(
            listOf(
                window("antigravity_gemini_5h_window", QuotaWindowAvailability.Depleted),
                window("antigravity_gemini_5h_window", QuotaWindowAvailability.Available),
            ),
        )
        assertEquals(1, options.size)
        assertTrue(options.single().depleted)
    }

    @Test
    fun `depleted percent window without usedPercent is filtered out`() {
        val options = buildWidgetWindowOptions(
            listOf(
                window("five_hour", QuotaWindowAvailability.Depleted).copy(usedPercent = null),
                window("weekly", QuotaWindowAvailability.Available).copy(usedPercent = null),
            ),
        )
        assertTrue(options.isEmpty())
    }

    @Test
    fun `depleted balance window without balanceAmount is filtered out while valid balance stays visible`() {
        val options = buildWidgetWindowOptions(
            listOf(
                QuotaWindow(
                    windowId = QuotaWindowId("balance_null"),
                    titleKey = "balance",
                    usedPercent = null,
                    resetAt = null,
                    limitWindowSeconds = null,
                    isPrimaryCandidate = true,
                    availability = QuotaWindowAvailability.Depleted,
                    displayKind = QuotaWindowDisplayKind.Balance,
                    balanceAmount = null,
                ),
                QuotaWindow(
                    windowId = QuotaWindowId("balance_blank"),
                    titleKey = "balance",
                    usedPercent = null,
                    resetAt = null,
                    limitWindowSeconds = null,
                    isPrimaryCandidate = true,
                    availability = QuotaWindowAvailability.Depleted,
                    displayKind = QuotaWindowDisplayKind.Balance,
                    balanceAmount = "   ",
                ),
                QuotaWindow(
                    windowId = QuotaWindowId("balance_zero"),
                    titleKey = "balance",
                    usedPercent = null,
                    resetAt = null,
                    limitWindowSeconds = null,
                    isPrimaryCandidate = true,
                    availability = QuotaWindowAvailability.Depleted,
                    displayKind = QuotaWindowDisplayKind.Balance,
                    balanceAmount = "0.00",
                    balanceCurrency = "USD",
                ),
            ),
        )
        assertEquals(listOf("balance_zero"), options.map { it.windowId })
        assertTrue(options.single().depleted)
    }

    @Test
    fun `usage count window without displayPercent is filtered out from widget options`() {
        val options = buildWidgetWindowOptions(
            listOf(
                QuotaWindow(
                    windowId = QuotaWindowId("usage_only_counts"),
                    titleKey = "usage",
                    usedPercent = null,
                    resetAt = null,
                    limitWindowSeconds = null,
                    isPrimaryCandidate = true,
                    availability = QuotaWindowAvailability.Depleted,
                    displayKind = QuotaWindowDisplayKind.UsageCount,
                    usedCount = 100,
                    limitCount = 100,
                ),
            ),
        )
        assertTrue(options.isEmpty())
    }
}
