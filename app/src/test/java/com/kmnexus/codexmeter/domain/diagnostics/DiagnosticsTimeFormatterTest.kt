package com.kmnexus.codexmeter.domain.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DiagnosticsTimeFormatterTest {
    private val now = 1_700_000_000_000L

    @Test
    fun `render epoch text adds iso and relative age`() {
        val threeHoursAgo = (now - 3 * 3_600_000L).toString()
        val rendered = DiagnosticsTimeFormatter.render(threeHoursAgo, now)
        assertEquals("1699989200000 (2023-11-14T19:13:20Z, 3h ago)", rendered)
    }

    @Test
    fun `render without now omits age`() {
        val rendered = DiagnosticsTimeFormatter.render("1700000000000", null)
        assertEquals("1700000000000 (2023-11-14T22:13:20Z)", rendered)
    }

    @Test
    fun `render passes through non-epoch text`() {
        assertEquals("unavailable", DiagnosticsTimeFormatter.render("unavailable", now))
    }

    @Test
    fun `render null returns null`() {
        assertNull(DiagnosticsTimeFormatter.render(null, now))
    }

    @Test
    fun `age uses largest unit`() {
        assertEquals("5s ago", DiagnosticsTimeFormatter.formatAge(5_000L))
        assertEquals("2m ago", DiagnosticsTimeFormatter.formatAge(120_000L))
        assertEquals("3h ago", DiagnosticsTimeFormatter.formatAge(3 * 3_600_000L))
        assertEquals("2d ago", DiagnosticsTimeFormatter.formatAge(2 * 86_400_000L))
        assertEquals("in the future", DiagnosticsTimeFormatter.formatAge(-1L))
    }

    @Test
    fun `ageOnly renders age for epoch text`() {
        val twoDaysAgo = (now - 2 * 86_400_000L).toString()
        assertEquals("2d ago", DiagnosticsTimeFormatter.renderAgeOnly(twoDaysAgo, now))
        assertNull(DiagnosticsTimeFormatter.renderAgeOnly(null, now))
        assertEquals("unavailable", DiagnosticsTimeFormatter.renderAgeOnly("unavailable", now))
    }
}
