package com.kmnexus.codexmeter.data.currency

import com.kmnexus.codexmeter.core.network.ProviderHttpClient
import com.kmnexus.codexmeter.domain.currency.ExchangeRates
import java.time.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Fetches public FX rates from open.er-api.com (no API key, USD-based, no attribution required).
 * INVARIANT: this is the app's only non-provider network call; it transmits no account/quota/PII —
 * only a base-currency GET.
 */
class ExchangeRateClient(
    private val httpClient: ProviderHttpClient,
    private val endpoint: String = DEFAULT_ENDPOINT,
) {
    suspend fun fetchRates(now: Instant): ExchangeRates? {
        val response = httpClient.get(endpoint)
        if (response.statusCode !in 200..299) return null
        val dto = runCatching { json.decodeFromString<ExchangeRateResponseDto>(response.body) }
            .getOrNull() ?: return null
        if (dto.result != "success" || dto.baseCode.isNullOrBlank() || dto.rates.isNullOrEmpty()) return null
        return ExchangeRates(
            base = dto.baseCode.uppercase(),
            rates = dto.rates.mapKeys { it.key.uppercase() },
            fetchedAt = now,
        )
    }

    @Serializable
    private data class ExchangeRateResponseDto(
        val result: String? = null,
        @SerialName("base_code") val baseCode: String? = null,
        val rates: Map<String, Double>? = null,
    )

    private companion object {
        const val DEFAULT_ENDPOINT = "https://open.er-api.com/v6/latest/USD"
        val json = Json { ignoreUnknownKeys = true }
    }
}
