package com.kmnexus.codexmeter.widget

import org.junit.Assert.assertEquals
import org.junit.Test

class WidgetConfigurationSectionsTest {
    private val available = listOf("five_hour", "weekly", "monthly", "balance", "extra")

    @Test
    fun `toggle adds in natural order regardless of click order`() {
        var sel = listOf<String>()
        sel = toggleWindowSelection(sel, "weekly", available)
        sel = toggleWindowSelection(sel, "five_hour", available)
        assertEquals(listOf("five_hour", "weekly"), sel)
    }

    @Test
    fun `toggle removes when already selected`() {
        val sel = toggleWindowSelection(listOf("five_hour", "weekly"), "five_hour", available)
        assertEquals(listOf("weekly"), sel)
    }

    @Test
    fun `cannot exceed max fields`() {
        val full = listOf("five_hour", "weekly", "monthly", "balance")
        val sel = toggleWindowSelection(full, "extra", available)
        assertEquals(full, sel) // 已满 4 个，忽略新增
    }

    @Test
    fun `account change keeps only available selections in order`() {
        val sel = retainAvailableWindowIds(listOf("monthly", "five_hour", "ghost"), listOf("five_hour", "monthly"))
        assertEquals(listOf("five_hour", "monthly"), sel)
    }
}
