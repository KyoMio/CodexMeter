package com.kmnexus.codexmeter.providers.grok.network

import com.kmnexus.codexmeter.core.network.ProviderHttpClient
import com.kmnexus.codexmeter.domain.refresh.QuotaError
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class GrokBillingClientDecodeTest {
    private val server = MockWebServer()

    @Before fun setUp() = server.start()
    @After fun tearDown() = server.close()

    private fun client() = GrokBillingClient(ProviderHttpClient())
    private fun baseUrl() = server.url("/").toString()

    /** Golden sample from docs/research/2026-09-10-grok-provider-login-research.md section 2.3. */
    @Test
    fun parsesGoldenBillingSample() = runTest {
        server.enqueue(MockResponse.Builder().code(200).body(GOLDEN_SAMPLE_JSON).build())

        val result = client().fetchBilling(accessToken = "synthetic-access-token", baseUrl = baseUrl())

        assertTrue(result is GrokBillingClient.Result.Success)
        val dto = (result as GrokBillingClient.Result.Success).dto
        assertEquals("USAGE_PERIOD_TYPE_WEEKLY", dto.currentPeriod?.type)
        assertEquals("2026-08-17T17:33:48.278812+00:00", dto.currentPeriod?.start)
        assertEquals("2026-08-24T17:33:48.278812+00:00", dto.currentPeriod?.end)
        assertEquals(1.0, dto.creditUsagePercent!!, 0.0001)
        assertEquals(0.0, dto.prepaidBalance!!, 0.0001)
        val product = dto.productUsage.single()
        assertEquals("GrokBuild", product.product)
        assertEquals(1.0, product.usagePercent!!, 0.0001)
        assertEquals("2026-08-17T17:33:48.278812+00:00", dto.billingPeriodStart)
        assertEquals("2026-08-24T17:33:48.278812+00:00", dto.billingPeriodEnd)
    }

    /** Field types may drift between accounts (int / double / string) — never throw, always coerce. */
    @Test
    fun toleratesNumericTypeDrift() = runTest {
        val body = """
            {"config": {
              "currentPeriod": {"type": "USAGE_PERIOD_TYPE_WEEKLY", "end": "2026-08-24T17:33:48+00:00"},
              "creditUsagePercent": 5,
              "productUsage": [{"product": "GrokBuild", "usagePercent": "7.5"}],
              "prepaidBalance": {"val": "2.5"}
            }}
        """.trimIndent()
        server.enqueue(MockResponse.Builder().code(200).body(body).build())

        val result = client().fetchBilling(accessToken = "synthetic-access-token", baseUrl = baseUrl())

        assertTrue(result is GrokBillingClient.Result.Success)
        val dto = (result as GrokBillingClient.Result.Success).dto
        assertEquals(5.0, dto.creditUsagePercent!!, 0.0001)
        assertEquals(7.5, dto.productUsage.single().usagePercent!!, 0.0001)
        assertEquals(2.5, dto.prepaidBalance!!, 0.0001)
    }

    /** A body without `config` still decodes (empty projection); unavailability is the mapper's call. */
    @Test
    fun missingConfigObjectStillSucceedsWithEmptyProjection() = runTest {
        server.enqueue(MockResponse.Builder().code(200).body("""{"unexpected": true}""").build())

        val result = client().fetchBilling(accessToken = "synthetic-access-token", baseUrl = baseUrl())

        assertTrue(result is GrokBillingClient.Result.Success)
        val dto = (result as GrokBillingClient.Result.Success).dto
        assertNull(dto.currentPeriod)
        assertNull(dto.creditUsagePercent)
        assertNull(dto.prepaidBalance)
        assertTrue(dto.productUsage.isEmpty())
    }

    @Test
    fun http401MapsToAuthRequired() = runTest {
        server.enqueue(MockResponse.Builder().code(401).body("").build())

        val result = client().fetchBilling(accessToken = "synthetic-access-token", baseUrl = baseUrl())

        assertTrue((result as GrokBillingClient.Result.Failure).error is QuotaError.AuthRequired)
    }

    @Test
    fun http403MapsToAuthRequired() = runTest {
        server.enqueue(MockResponse.Builder().code(403).body("").build())

        val result = client().fetchBilling(accessToken = "synthetic-access-token", baseUrl = baseUrl())

        assertTrue((result as GrokBillingClient.Result.Failure).error is QuotaError.AuthRequired)
    }

    @Test
    fun http500MapsToNetwork() = runTest {
        server.enqueue(MockResponse.Builder().code(500).body("").build())

        val result = client().fetchBilling(accessToken = "synthetic-access-token", baseUrl = baseUrl())

        assertTrue((result as GrokBillingClient.Result.Failure).error is QuotaError.Network)
    }

    @Test
    fun invalidJsonBodyMapsToNetworkDecodeError() = runTest {
        server.enqueue(MockResponse.Builder().code(200).body("not-json").build())

        val result = client().fetchBilling(accessToken = "synthetic-access-token", baseUrl = baseUrl())

        val failure = result as GrokBillingClient.Result.Failure
        assertTrue(failure.error is QuotaError.Network)
        assertEquals("grok_billing_decode_error", failure.error.diagnosticsDigest)
    }

    /** Redaction: the access token must never appear in a diagnostics digest. */
    @Test
    fun digestNeverContainsAccessToken() = runTest {
        server.enqueue(MockResponse.Builder().code(500).body("").build())
        val token = "synthetic-access-token"

        val result = client().fetchBilling(accessToken = token, baseUrl = baseUrl())

        val digest = (result as GrokBillingClient.Result.Failure).error.diagnosticsDigest ?: ""
        assertFalse(digest.contains(token))
    }

    @Test
    fun sendsBearerTokenAndGrokClientVersionHeaders() = runTest {
        server.enqueue(MockResponse.Builder().code(200).body(GOLDEN_SAMPLE_JSON).build())

        client().fetchBilling(accessToken = "synthetic-access-token", baseUrl = baseUrl())

        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/v1/billing", request.url.encodedPath)
        assertEquals("credits", request.url.queryParameter("format"))
        assertEquals("Bearer synthetic-access-token", request.headers["Authorization"])
        assertEquals("0.1.202", request.headers["x-grok-client-version"])
    }

    private companion object {
        val GOLDEN_SAMPLE_JSON = """
            {
              "config": {
                "currentPeriod": {
                  "type": "USAGE_PERIOD_TYPE_WEEKLY",
                  "start": "2026-08-17T17:33:48.278812+00:00",
                  "end": "2026-08-24T17:33:48.278812+00:00"
                },
                "creditUsagePercent": 1.0,
                "onDemandCap": {"val": 0},
                "onDemandUsed": {"val": 0},
                "productUsage": [{"product": "GrokBuild", "usagePercent": 1.0}],
                "isUnifiedBillingUser": true,
                "prepaidBalance": {"val": 0},
                "topUpMethod": "TOP_UP_METHOD_SAVED_PAYMENT_METHOD",
                "billingPeriodStart": "2026-08-17T17:33:48.278812+00:00",
                "billingPeriodEnd": "2026-08-24T17:33:48.278812+00:00"
              }
            }
        """.trimIndent()
    }
}
