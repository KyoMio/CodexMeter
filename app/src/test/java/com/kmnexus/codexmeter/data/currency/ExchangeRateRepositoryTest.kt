package com.kmnexus.codexmeter.data.currency

import com.kmnexus.codexmeter.domain.currency.ExchangeRates
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

class ExchangeRateRepositoryTest {
    private class FakeCache(var stored: ExchangeRates? = null) : ExchangeRateCache {
        override suspend fun read(): ExchangeRates? = stored
        override suspend fun write(rates: ExchangeRates) { stored = rates }
    }

    private fun rates(at: Instant) = ExchangeRates("USD", mapOf("USD" to 1.0, "CNY" to 7.2), at)

    @Test
    fun `fetches when cache empty`() = runTest {
        val cache = FakeCache(stored = null)
        val now = Instant.parse("2026-06-01T00:00:00Z")
        val fetched = rates(now)
        val repo = ExchangeRateRepository(cache = cache, fetch = { fetched })
        repo.refreshIfStale(now)
        assertEquals(fetched, cache.stored)
    }

    @Test
    fun `does not fetch when cache fresh within 12h`() = runTest {
        val cachedAt = Instant.parse("2026-06-01T00:00:00Z")
        val cache = FakeCache(stored = rates(cachedAt))
        var fetchCount = 0
        val repo = ExchangeRateRepository(cache = cache, fetch = { fetchCount++; rates(cachedAt) })
        repo.refreshIfStale(cachedAt.plusSeconds(11 * 3600))
        assertEquals(0, fetchCount)
    }

    @Test
    fun `fetches when cache older than 12h`() = runTest {
        val cachedAt = Instant.parse("2026-06-01T00:00:00Z")
        val cache = FakeCache(stored = rates(cachedAt))
        val now = cachedAt.plusSeconds(13 * 3600)
        val fresh = rates(now)
        val repo = ExchangeRateRepository(cache = cache, fetch = { fresh })
        repo.refreshIfStale(now)
        assertEquals(fresh, cache.stored)
    }

    @Test
    fun `keeps old cache when fetch fails`() = runTest {
        val cachedAt = Instant.parse("2026-06-01T00:00:00Z")
        val original = rates(cachedAt)
        val cache = FakeCache(stored = original)
        val repo = ExchangeRateRepository(cache = cache, fetch = { null })
        repo.refreshIfStale(cachedAt.plusSeconds(24 * 3600))
        assertEquals(original, cache.stored)
    }

    @Test
    fun `currentRates reads cache`() = runTest {
        val cachedAt = Instant.parse("2026-06-01T00:00:00Z")
        val repo = ExchangeRateRepository(cache = FakeCache(rates(cachedAt)), fetch = { null })
        assertEquals(cachedAt, repo.currentRates()!!.fetchedAt)
    }

    @Test
    fun `currentRates null when empty`() = runTest {
        val repo = ExchangeRateRepository(cache = FakeCache(null), fetch = { null })
        assertNull(repo.currentRates())
    }
}
