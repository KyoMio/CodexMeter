package com.kmnexus.codexmeter.providers.codex.network

import com.kmnexus.codexmeter.core.network.ProviderHttpClient
import com.kmnexus.codexmeter.domain.refresh.QuotaError
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class CodexUsageClientTest {
    private val server = MockWebServer()
    private val json = Json {
        ignoreUnknownKeys = true
    }

    @Before
    fun setUp() {
        server.start()
    }

    @After
    fun tearDown() {
        server.close()
    }

    @Test
    fun `sends required usage headers with account id`() = runTest {
        server.enqueue(successfulUsageResponse())
        val client = newClient()

        val result = client.fetchUsage(
            accessToken = "fake-access-token",
            accountId = "fake-account-id",
        )

        assertTrue(result is CodexUsageClient.Result.Success)
        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/backend-api/wham/usage", request.url.encodedPath)
        assertEquals("Bearer fake-access-token", request.headers["Authorization"])
        assertEquals("application/json", request.headers["Accept"])
        assertEquals("CodexMeter/0.1.0", request.headers["User-Agent"])
        assertEquals("fake-account-id", request.headers["ChatGPT-Account-Id"])
    }

    @Test
    fun `omits account id header when account id is absent`() = runTest {
        server.enqueue(successfulUsageResponse())
        val client = newClient()

        val result = client.fetchUsage(
            accessToken = "fake-access-token",
            accountId = null,
        )

        assertTrue(result is CodexUsageClient.Result.Success)
        val request = server.takeRequest()
        assertNull(request.headers["ChatGPT-Account-Id"])
    }

    @Test
    fun `decodes successful usage response`() = runTest {
        server.enqueue(successfulUsageResponse())
        val client = newClient()

        val result = client.fetchUsage(
            accessToken = "fake-access-token",
            accountId = "fake-account-id",
        )

        assertTrue(result is CodexUsageClient.Result.Success)
        val success = result as CodexUsageClient.Result.Success
        assertEquals("plus", success.dto.planType)
        assertEquals(62, success.dto.rateLimit?.primaryWindow?.usedPercent)
        assertEquals(1_770_000_000L, success.dto.rateLimit?.primaryWindow?.resetAt)
        assertEquals(18_000, success.dto.rateLimit?.primaryWindow?.limitWindowSeconds)
        assertEquals(41, success.dto.rateLimit?.secondaryWindow?.usedPercent)
    }

    @Test
    fun `rejects insecure endpoint by default without sending request`() = runTest {
        server.enqueue(successfulUsageResponse())
        val client = CodexUsageClient(
            httpClient = ProviderHttpClient(),
            json = json,
            endpointUrl = server.url("/backend-api/wham/usage").toString(),
        )

        val result = client.fetchUsage(
            accessToken = "fake-access-token",
            accountId = "fake-account-id",
        )

        assertTrue(result is CodexUsageClient.Result.Failure)
        val failure = result as CodexUsageClient.Result.Failure
        assertTrue(failure.error is QuotaError.Network)
        assertEquals("codex_usage_insecure_endpoint", failure.error.diagnosticsDigest)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `returns failure for malformed decoded quota flags without leaking body or token`() = runTest {
        val responseBody =
            """
            {
              "plan_type":"plus",
              "rate_limit":{
                "primary_window":"primary-window-as-string",
                "secondary_window":{
                  "used_percent":41,
                  "reset_at":1770500000,
                  "limit_window_seconds":604800
                }
              }
            }
            """.trimIndent()
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .body(responseBody)
                .build(),
        )
        val client = newClient()

        val result = client.fetchUsage(
            accessToken = "fake-access-token",
            accountId = "fake-account-id",
        )

        assertTrue(result is CodexUsageClient.Result.Failure)
        val failure = result as CodexUsageClient.Result.Failure
        assertTrue(failure.error is QuotaError.Network)
        assertEquals("codex_usage_decode_error", failure.error.diagnosticsDigest)
        assertFalse(failure.error.diagnosticsDigest.orEmpty().contains("fake-access-token"))
        assertFalse(failure.error.diagnosticsDigest.orEmpty().contains(responseBody))
    }

    @Test
    fun `maps unauthorized response to auth required without leaking body or token`() = runTest {
        val responseBody = """{"error":"fake unauthorized body"}"""
        server.enqueue(
            MockResponse.Builder()
                .code(401)
                .body(responseBody)
                .build(),
        )
        val client = newClient()

        val result = client.fetchUsage(
            accessToken = "fake-access-token",
            accountId = "fake-account-id",
        )

        assertTrue(result is CodexUsageClient.Result.Failure)
        val failure = result as CodexUsageClient.Result.Failure
        val error = failure.error
        assertTrue(error is QuotaError.AuthRequired)
        assertEquals(401, error.httpStatus)
        assertFalse(error.diagnosticsDigest.orEmpty().contains("fake-access-token"))
        assertFalse(error.diagnosticsDigest.orEmpty().contains(responseBody))
    }

    private fun newClient(): CodexUsageClient =
        CodexUsageClient(
            httpClient = ProviderHttpClient(),
            json = json,
            endpointUrl = server.url("/backend-api/wham/usage").toString(),
            allowInsecureHttpForTests = true,
        )

    private fun successfulUsageResponse(): MockResponse =
        MockResponse.Builder()
            .code(200)
            .body(
                """
                {
                  "plan_type":"plus",
                  "rate_limit":{
                    "primary_window":{
                      "used_percent":62,
                      "reset_at":1770000000,
                      "limit_window_seconds":18000
                    },
                    "secondary_window":{
                      "used_percent":41,
                      "reset_at":1770500000,
                      "limit_window_seconds":604800
                    }
                  }
                }
                """.trimIndent(),
            )
            .build()
}
