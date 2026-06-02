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

class ZaiQuotaClientDecodeTest {
    private val server = MockWebServer()

    @Before fun setUp() = server.start()

    @After fun tearDown() = server.close()

    /**
     * Real z.ai payloads vary by plan: numbers can arrive as strings, `level` can be an object, and
     * extra keys appear. The defensive parser must not throw `zai_decode_error`.
     */
    @Test
    fun toleratesVariantFieldTypes() = runTest {
        val body = """
            {
              "code": 200,
              "success": true,
              "msg": "success",
              "data": {
                "level": {"name": "pro"},
                "limits": [
                  {"type":"TOKENS_LIMIT","unit":3,"number":5,"usage":"1000","currentValue":"620","remaining":"380","percentage":"62","nextResetTime":"1772000000000","usageDetails":[{"modelCode":"glm-4","usage":10}]}
                ]
              }
            }
        """.trimIndent()
        server.enqueue(MockResponse.Builder().code(200).body(body).build())

        val result = ZaiQuotaClient(ProviderHttpClient())
            .fetchQuota(apiKey = "sk-x", baseUrl = server.url("/").toString())

        assertTrue(result is ZaiQuotaClient.Result.Success)
        val limit = (result as ZaiQuotaClient.Result.Success).dto.data?.limits?.first()
        assertEquals(1000, limit?.usage)
        assertEquals(62.0, limit?.percentage)
    }
}
