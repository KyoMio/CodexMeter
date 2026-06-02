package com.kmnexus.codexmeter.data.preferences

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.kmnexus.codexmeter.data.currency.ExchangeRateCache
import com.kmnexus.codexmeter.domain.currency.CurrencyPreferenceReader
import com.kmnexus.codexmeter.domain.currency.CurrencyPreferenceStore
import com.kmnexus.codexmeter.domain.currency.CurrencyPreferences
import com.kmnexus.codexmeter.domain.currency.ExchangeRates
import java.time.Instant
import kotlinx.coroutines.flow.first
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

class CurrencyPreferencesDataStore(
    private val dataStore: DataStore<Preferences>,
) : CurrencyPreferenceStore, CurrencyPreferenceReader, ExchangeRateCache {

    override suspend fun currencyPreferences(): CurrencyPreferences {
        val prefs = dataStore.data.first()
        return CurrencyPreferences(
            targetCurrency = prefs[Keys.TARGET_CURRENCY]?.takeIf { it.isNotBlank() }
                ?: CurrencyPreferences.DEFAULT_TARGET_CURRENCY,
        )
    }

    override suspend fun updateCurrencyPreferences(preferences: CurrencyPreferences) {
        dataStore.edit { stored ->
            stored[Keys.TARGET_CURRENCY] = preferences.targetCurrency
        }
    }

    override suspend fun read(): ExchangeRates? {
        val prefs = dataStore.data.first()
        val base = prefs[Keys.RATES_BASE]?.takeIf { it.isNotBlank() } ?: return null
        val ratesJson = prefs[Keys.RATES_JSON]?.takeIf { it.isNotBlank() } ?: return null
        val fetchedAt = prefs[Keys.RATES_FETCHED_AT] ?: return null
        val rates = runCatching {
            json.decodeFromString(MAP_SERIALIZER, ratesJson)
        }.getOrNull() ?: return null
        return ExchangeRates(base = base, rates = rates, fetchedAt = Instant.ofEpochMilli(fetchedAt))
    }

    override suspend fun write(rates: ExchangeRates) {
        dataStore.edit { stored ->
            stored[Keys.RATES_BASE] = rates.base
            stored[Keys.RATES_JSON] = json.encodeToString(MAP_SERIALIZER, rates.rates)
            stored[Keys.RATES_FETCHED_AT] = rates.fetchedAt.toEpochMilli()
        }
    }

    private object Keys {
        val TARGET_CURRENCY = stringPreferencesKey("currency_target")
        val RATES_BASE = stringPreferencesKey("exchange_rates_base")
        val RATES_JSON = stringPreferencesKey("exchange_rates_json")
        val RATES_FETCHED_AT = longPreferencesKey("exchange_rates_fetched_at")
    }

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
        val MAP_SERIALIZER = MapSerializer(String.serializer(), Double.serializer())
    }
}
