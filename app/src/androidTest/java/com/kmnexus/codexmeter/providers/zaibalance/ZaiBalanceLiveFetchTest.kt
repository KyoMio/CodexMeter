package com.kmnexus.codexmeter.providers.zaibalance

import androidx.test.platform.app.InstrumentationRegistry
import com.kmnexus.codexmeter.core.network.ProviderHttpClient
import com.kmnexus.codexmeter.domain.model.LocalAccountId
import com.kmnexus.codexmeter.domain.quota.QuotaSnapshotSource
import com.kmnexus.codexmeter.domain.quota.QuotaWindowDisplayKind
import com.kmnexus.codexmeter.providers.zaibalance.mapper.ZaiBalanceMapper
import com.kmnexus.codexmeter.providers.zaibalance.network.ZaiBalanceClient
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Live, manual-only check: hits the real bigmodel endpoint with an API key passed at runtime. Skips
 * automatically when no key is supplied, so it never runs in CI. The key is never committed.
 * Run: ./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.zai_api_key="<id.secret>"
 */
class ZaiBalanceLiveFetchTest {
    @Test
    fun fetchesAndMapsRealBalance() = runBlocking {
        val apiKey = InstrumentationRegistry.getArguments().getString("zai_api_key")
        assumeTrue("no zai_api_key arg supplied; skipping live test", !apiKey.isNullOrBlank())

        val result = ZaiBalanceClient(ProviderHttpClient()).fetchBalance(apiKey!!)
        assertTrue("expected Success, got $result", result is ZaiBalanceClient.Result.Success)

        val snapshot = ZaiBalanceMapper.map(
            dto = (result as ZaiBalanceClient.Result.Success).dto,
            localAccountId = LocalAccountId("zaibal-live"),
            providerAccountId = null,
            fetchedAt = Instant.now(),
            source = QuotaSnapshotSource.ApiKeyImport,
        )
        assertEquals(QuotaWindowDisplayKind.Balance, snapshot.windows.single().displayKind)
    }
}
