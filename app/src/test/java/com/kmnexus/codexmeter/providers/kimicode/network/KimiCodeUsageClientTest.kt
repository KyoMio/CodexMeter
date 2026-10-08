package com.kmnexus.codexmeter.providers.kimicode.network

import com.kmnexus.codexmeter.core.network.ProviderHttpClient
import com.kmnexus.codexmeter.domain.refresh.QuotaError
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class KimiCodeUsageClientTest {
    private val server = MockWebServer()

    @Before fun setUp() = server.start()
    @After fun tearDown() = server.close()

    private fun client() = KimiCodeUsageClient(ProviderHttpClient())
    private fun baseUrl() = server.url("/").toString()

    private fun okBody() =
        """{"usages":{"limit_5h":{"used_ratio":0.5,"reset_time":"2026-09-16T20:15:44Z"}}}"""

    @Test
    fun sendsGetToCodingUsagesWithBearerKey() = runTest {
        server.enqueue(MockResponse.Builder().code(200).body(okBody()).build())

        val result = client().fetchUsage(apiKey = "test-key", baseUrl = baseUrl())

        assertTrue(result is KimiCodeUsageClient.Result.Success)
        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/coding/v1/usages", request.url.encodedPath)
        assertEquals("Bearer test-key", request.headers["Authorization"])
        assertEquals("application/json", request.headers["Accept"])
    }

    @Test
    fun trailingSlashAndCodingPathSuffixAreNormalized() = runTest {
        server.enqueue(MockResponse.Builder().code(200).body(okBody()).build())
        val baseWithCodingPath = baseUrl().trimEnd('/').removeSuffix("/coding/v1") + "/coding/v1/"

        val result = client().fetchUsage(apiKey = "test-key", baseUrl = baseWithCodingPath)

        assertTrue(result is KimiCodeUsageClient.Result.Success)
        assertEquals("/coding/v1/usages", server.takeRequest().url.encodedPath)
    }

    @Test
    fun http401MapsToAuthRequiredWithStatusDigest() = runTest {
        server.enqueue(MockResponse.Builder().code(401).body("""{"error":"unauthorized"}""").build())

        val result = client().fetchUsage(apiKey = "test-key", baseUrl = baseUrl())

        val error = (result as KimiCodeUsageClient.Result.Failure).error
        assertTrue(error is QuotaError.AuthRequired)
        assertEquals(401, error.httpStatus)
        assertEquals("kimi_code_auth_required_401", error.diagnosticsDigest)
    }

    @Test
    fun http403MapsToAuthRequiredWithStatusDigest() = runTest {
        server.enqueue(MockResponse.Builder().code(403).body("").build())

        val result = client().fetchUsage(apiKey = "test-key", baseUrl = baseUrl())

        val error = (result as KimiCodeUsageClient.Result.Failure).error
        assertTrue(error is QuotaError.AuthRequired)
        assertEquals("kimi_code_auth_required_403", error.diagnosticsDigest)
    }

    @Test
    fun http500MapsToNetworkWithStatusDigest() = runTest {
        server.enqueue(MockResponse.Builder().code(500).body("boom").build())

        val result = client().fetchUsage(apiKey = "test-key", baseUrl = baseUrl())

        val error = (result as KimiCodeUsageClient.Result.Failure).error
        assertTrue(error is QuotaError.Network)
        assertEquals("kimi_code_http_500", error.diagnosticsDigest)
    }

    @Test
    fun connectionFailureMapsToNetworkError() = runTest {
        // Port 1 on localhost refuses connections: exercises the IOException path.
        val result = client().fetchUsage(apiKey = "test-key", baseUrl = "http://127.0.0.1:1")

        val error = (result as KimiCodeUsageClient.Result.Failure).error
        assertTrue(error is QuotaError.Network)
        assertEquals("kimi_code_network_error", error.diagnosticsDigest)
    }

    @Test
    fun malformedBodyMapsToDecodeError() = runTest {
        server.enqueue(MockResponse.Builder().code(200).body("not-json").build())

        val result = client().fetchUsage(apiKey = "test-key", baseUrl = baseUrl())

        val error = (result as KimiCodeUsageClient.Result.Failure).error
        assertTrue(error is QuotaError.Network)
        assertEquals("kimi_code_decode_error", error.diagnosticsDigest)
    }

    /** Payloads without a single usable window must fail rather than persist an empty snapshot. */
    @Test
    fun zeroWindowPayloadsMapToNoQuotaWindows() = runTest {
        listOf("{}", """{"usages":{}}""", """{"usages":{"limit_5h":{}}}""").forEach { body ->
            server.enqueue(MockResponse.Builder().code(200).body(body).build())
            val result = client().fetchUsage(apiKey = "test-key", baseUrl = baseUrl())

            val error = (result as KimiCodeUsageClient.Result.Failure).error
            assertTrue(error is QuotaError.Network)
            assertEquals("kimi_code_no_quota_windows", error.diagnosticsDigest)
        }
    }

    /** Redaction: the API key must never appear in a diagnostics digest. */
    @Test
    fun digestNeverContainsApiKey() = runTest {
        server.enqueue(MockResponse.Builder().code(401).body("").build())
        val secret = "sk-secret-test-key-value"

        val result = client().fetchUsage(apiKey = secret, baseUrl = baseUrl())

        val digest = (result as KimiCodeUsageClient.Result.Failure).error.diagnosticsDigest ?: ""
        assertFalse(digest.contains(secret))
    }
}
