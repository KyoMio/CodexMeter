package com.kmnexus.codexmeter.domain.currency

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

class CurrencyConverterTest {
    private val rates = ExchangeRates(
        base = "USD",
        rates = mapOf("USD" to 1.0, "CNY" to 7.2, "EUR" to 0.9),
        fetchedAt = Instant.parse("2026-06-01T00:00:00Z"),
    )

    @Test
    fun `same currency returns same amount`() {
        assertEquals(12.5, CurrencyConverter.convert(12.5, "USD", "USD", rates)!!, 1e-9)
    }

    @Test
    fun `converts CNY to USD using base rates`() {
        assertEquals(10.0, CurrencyConverter.convert(72.0, "CNY", "USD", rates)!!, 1e-9)
    }

    @Test
    fun `converts across two non-base currencies`() {
        assertEquals(0.9, CurrencyConverter.convert(7.2, "CNY", "EUR", rates)!!, 1e-9)
    }

    @Test
    fun `unknown currency returns null`() {
        assertNull(CurrencyConverter.convert(10.0, "JPY", "USD", rates))
    }

    @Test
    fun `currency code is case-insensitive`() {
        assertEquals(10.0, CurrencyConverter.convert(72.0, "cny", "usd", rates)!!, 1e-9)
    }

    @Test
    fun `null or blank currency code returns null`() {
        assertNull(CurrencyConverter.convert(10.0, null, "USD", rates))
        assertNull(CurrencyConverter.convert(10.0, "USD", null, rates))
        assertNull(CurrencyConverter.convert(10.0, "  ", "USD", rates))
        assertNull(CurrencyConverter.convert(10.0, "", "USD", rates))
    }
}
