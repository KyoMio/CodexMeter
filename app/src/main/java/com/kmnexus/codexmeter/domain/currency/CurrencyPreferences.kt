package com.kmnexus.codexmeter.domain.currency

/** User currency-conversion settings. Conversion is always on; target defaults to USD. */
data class CurrencyPreferences(
    val targetCurrency: String = DEFAULT_TARGET_CURRENCY,
) {
    companion object {
        const val DEFAULT_TARGET_CURRENCY = "USD"

        /** Currencies offered in Settings. Keep small and ISO-4217. */
        val SUPPORTED_TARGET_CURRENCIES = listOf("USD", "CNY", "EUR", "GBP", "JPY")
    }
}

interface CurrencyPreferenceReader {
    suspend fun currencyPreferences(): CurrencyPreferences
}

interface CurrencyPreferenceStore : CurrencyPreferenceReader {
    suspend fun updateCurrencyPreferences(preferences: CurrencyPreferences)
}
