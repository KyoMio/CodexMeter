package com.kmnexus.codexmeter.domain.currency

import java.time.Instant

/** Exchange rates with a single [base] currency; every value in [rates] is "1 base = value <code>". */
data class ExchangeRates(
    val base: String,
    val rates: Map<String, Double>,
    val fetchedAt: Instant,
)

/** Pure currency conversion. No IO; safe to unit-test and call from any layer. */
object CurrencyConverter {
    fun convert(amount: Double, from: String?, to: String?, rates: ExchangeRates): Double? {
        val fromCode = from?.uppercase()?.takeIf { it.isNotBlank() } ?: return null
        val toCode = to?.uppercase()?.takeIf { it.isNotBlank() } ?: return null
        if (fromCode == toCode) return amount
        val fromRate = rates.rates[fromCode] ?: return null
        val toRate = rates.rates[toCode] ?: return null
        if (fromRate <= 0.0) return null
        return amount * (toRate / fromRate)
    }
}
