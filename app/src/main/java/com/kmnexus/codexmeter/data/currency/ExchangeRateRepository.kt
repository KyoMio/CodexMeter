package com.kmnexus.codexmeter.data.currency

import com.kmnexus.codexmeter.domain.currency.ExchangeRates
import java.time.Duration
import java.time.Instant

interface ExchangeRateCache {
    suspend fun read(): ExchangeRates?
    suspend fun write(rates: ExchangeRates)
}

interface ExchangeRateReader {
    suspend fun currentRates(): ExchangeRates?
}

class ExchangeRateRepository(
    private val cache: ExchangeRateCache,
    private val fetch: suspend () -> ExchangeRates?,
    private val staleAfter: Duration = Duration.ofHours(12),
) : ExchangeRateReader {
    override suspend fun currentRates(): ExchangeRates? = cache.read()

    suspend fun refreshIfStale(now: Instant) {
        val cached = cache.read()
        if (cached != null) {
            val age = Duration.between(cached.fetchedAt, now)
            if (!age.isNegative && age < staleAfter) return
        }
        val fresh = runCatching { fetch() }.getOrNull() ?: return
        cache.write(fresh)
    }
}
