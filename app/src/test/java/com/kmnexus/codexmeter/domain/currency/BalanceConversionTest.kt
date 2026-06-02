package com.kmnexus.codexmeter.domain.currency

import com.kmnexus.codexmeter.domain.model.QuotaWindowId
import com.kmnexus.codexmeter.domain.quota.QuotaWindow
import com.kmnexus.codexmeter.domain.quota.QuotaWindowAvailability
import com.kmnexus.codexmeter.domain.quota.QuotaWindowDisplayKind
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

class BalanceConversionTest {
    private val rates = ExchangeRates("USD", mapOf("USD" to 1.0, "CNY" to 7.2), Instant.EPOCH)

    private fun balanceWindow(amount: String, currency: String) = QuotaWindow(
        windowId = QuotaWindowId("balance"),
        titleKey = "balance",
        usedPercent = null,
        resetAt = null,
        limitWindowSeconds = null,
        isPrimaryCandidate = true,
        availability = QuotaWindowAvailability.Available,
        displayKind = QuotaWindowDisplayKind.Balance,
        balanceAmount = amount,
        balanceCurrency = currency,
    )

    @Test
    fun `converts balance to target currency`() {
        val converted = balanceWindow("72.0", "CNY")
            .withConvertedBalance(targetCurrency = "USD", rates = rates)
        assertEquals("USD", converted.balanceCurrency)
        assertEquals(10.0, converted.balanceAmount!!.toDouble(), 1e-9)
        assertEquals("CNY", converted.originalBalanceCurrency)
        assertEquals(72.0, converted.originalBalanceAmount!!.toDouble(), 1e-9)
    }

    @Test
    fun `returns unchanged when rates null`() {
        val window = balanceWindow("72.0", "CNY")
        assertEquals(window, window.withConvertedBalance("USD", rates = null))
    }

    @Test
    fun `returns unchanged for non-balance window`() {
        val percent = balanceWindow("1", "CNY").copy(
            displayKind = QuotaWindowDisplayKind.Percent,
            balanceAmount = null,
            balanceCurrency = null,
            usedPercent = 40,
        )
        assertEquals(percent, percent.withConvertedBalance("USD", rates))
    }

    @Test
    fun `returns unchanged when currency unknown`() {
        val window = balanceWindow("10", "JPY")
        assertEquals(window, window.withConvertedBalance("USD", rates))
    }

    @Test
    fun `returns unchanged when already in target currency`() {
        val window = balanceWindow("9.49", "USD")
        val result = window.withConvertedBalance("USD", rates)
        assertEquals(window, result)
        // No redundant original annotation when the currency did not change.
        assertEquals(null, result.originalBalanceAmount)
        assertEquals(null, result.originalBalanceCurrency)
    }

    @Test
    fun `target currency match is case-insensitive`() {
        val window = balanceWindow("9.49", "usd")
        assertEquals(window, window.withConvertedBalance("USD", rates))
    }
}
