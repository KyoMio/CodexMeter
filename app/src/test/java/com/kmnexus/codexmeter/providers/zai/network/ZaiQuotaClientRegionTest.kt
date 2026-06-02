package com.kmnexus.codexmeter.providers.zai.network

import com.kmnexus.codexmeter.core.network.ProviderHttpClient
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ZaiQuotaClientRegionTest {
    private val server = MockWebServer()

    @Before
    fun setUp() {
        server.start()
    }

    @After
    fun tearDown() {
        server.close()
    }

    @Test
    fun `fetchQuota targets the supplied region base url`() = runTest {
        server.enqueue(MockResponse.Builder().code(200).body("""{"code":0,"data":{"limits":[]}}""").build())
        val client = ZaiQuotaClient(ProviderHttpClient())

        val result = client.fetchQuota(
            apiKey = "sk-region-test",
            baseUrl = server.url("/").toString(),
        )

        assertTrue(result is ZaiQuotaClient.Result.Success)
        val request = server.takeRequest()
        assertEquals("/api/monitor/usage/quota/limit", request.url.encodedPath)
        assertEquals("Bearer sk-region-test", request.headers["Authorization"])
    }
}
