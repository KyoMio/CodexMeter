package com.kmnexus.codexmeter.domain.currency

import com.kmnexus.codexmeter.domain.quota.QuotaWindow
import com.kmnexus.codexmeter.domain.quota.QuotaWindowDisplayKind

/**
 * Returns a copy of this window with its balance converted to [targetCurrency] when rates are
 * present and the amount/currency are convertible. Otherwise returns this window unchanged.
 * Never mutates non-balance windows. Used at Home + notification boundaries only —
 * persisted snapshots always keep the provider's native currency.
 */
fun QuotaWindow.withConvertedBalance(
    targetCurrency: String,
    rates: ExchangeRates?,
): QuotaWindow {
    if (rates == null) return this
    if (displayKind != QuotaWindowDisplayKind.Balance) return this
    // No real conversion when the balance is already in the target currency: leave it untouched so
    // the UI doesn't render a redundant "original currency" annotation equal to the displayed value.
    if (balanceCurrency?.uppercase() == targetCurrency.uppercase()) return this
    val amount = balanceAmount?.toDoubleOrNull() ?: return this
    val converted = CurrencyConverter.convert(amount, balanceCurrency, targetCurrency, rates) ?: return this
    return copy(
        balanceAmount = String.format(java.util.Locale.US, "%.2f", converted),
        balanceCurrency = targetCurrency.uppercase(),
        originalBalanceAmount = balanceAmount,
        originalBalanceCurrency = balanceCurrency,
    )
}
