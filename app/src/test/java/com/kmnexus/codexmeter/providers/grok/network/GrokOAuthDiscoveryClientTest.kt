package com.kmnexus.codexmeter.providers.grok.network

import com.kmnexus.codexmeter.core.network.ProviderHttpClient
import com.kmnexus.codexmeter.domain.refresh.QuotaError
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class GrokOAuthDiscoveryClientTest {
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
    fun `discovery success parses both endpoints`() = runTest {
        server.enqueue(successfulDiscoveryResponse())
        val client = newClient()

        val result = client.fetchEndpoints()

        assertTrue(result is GrokOAuthDiscoveryClient.Result.Success)
        val endpoints = (result as GrokOAuthDiscoveryClient.Result.Success).value
        assertEquals("https://auth.x.ai/oauth2/device/authorize", endpoints.deviceAuthorizationEndpoint)
        assertEquals("https://auth.x.ai/oauth2/token", endpoints.tokenEndpoint)

        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/.well-known/openid-configuration", request.url.encodedPath)
        assertTrue(request.headers["Accept"].orEmpty().contains("application/json"))
    }

    @Test
    fun `discovery missing endpoint fields fail safely`() = runTest {
        server.enqueue(
            jsonResponse(
                """
                {
                  "device_authorization_endpoint": "https://auth.x.ai/oauth2/device/authorize"
                }
                """.trimIndent(),
            ),
        )
        server.enqueue(
            jsonResponse(
                """
                {
                  "token_endpoint": "https://auth.x.ai/oauth2/token"
                }
                """.trimIndent(),
            ),
        )
        val client = newClient()

        val firstResult = client.fetchEndpoints()
        val secondResult = client.fetchEndpoints()

        assertNetworkFailure(
            result = firstResult,
            diagnosticsDigest = "grok_discovery_missing_token_endpoint",
        )
        assertNetworkFailure(
            result = secondResult,
            diagnosticsDigest = "grok_discovery_missing_device_authorization_endpoint",
        )
    }

    @Test
    fun `discovery endpoint on untrusted host fails safely`() = runTest {
        server.enqueue(
            jsonResponse(
                """
                {
                  "device_authorization_endpoint": "https://auth.x.ai/oauth2/device/authorize",
                  "token_endpoint": "https://evil.example/oauth2/token"
                }
                """.trimIndent(),
            ),
        )
        val client = newClient()

        val result = client.fetchEndpoints()

        assertNetworkFailure(
            result = result,
            diagnosticsDigest = "grok_discovery_untrusted_host",
            forbiddenText = "evil.example",
        )
    }

    @Test
    fun `discovery io exception maps to network failure`() = runTest {
        val deadServer = MockWebServer()
        deadServer.start()
        val deadServerUrl = deadServer.url("/.well-known/openid-configuration").toString()
        deadServer.close()
        val client = GrokOAuthDiscoveryClient(
            httpClient = ProviderHttpClient(),
            json = json,
            discoveryUrl = deadServerUrl,
            allowInsecureHttpForTests = true,
        )

        val result = client.fetchEndpoints()

        assertNetworkFailure(
            result = result,
            diagnosticsDigest = "grok_discovery_network_error",
        )
    }

    private fun newClient(): GrokOAuthDiscoveryClient =
        GrokOAuthDiscoveryClient(
            httpClient = ProviderHttpClient(),
            json = json,
            discoveryUrl = server.url("/.well-known/openid-configuration").toString(),
            allowInsecureHttpForTests = true,
        )

    private fun successfulDiscoveryResponse(): MockResponse =
        jsonResponse(
            """
            {
              "issuer": "https://auth.x.ai",
              "device_authorization_endpoint": "https://auth.x.ai/oauth2/device/authorize",
              "token_endpoint": "https://auth.x.ai/oauth2/token",
              "ignored": "value"
            }
            """.trimIndent(),
        )

    private fun jsonResponse(body: String): MockResponse =
        MockResponse.Builder()
            .code(200)
            .body(body)
            .build()

    private fun assertNetworkFailure(
        result: GrokOAuthDiscoveryClient.Result<*>,
        diagnosticsDigest: String,
        forbiddenText: String? = null,
    ) {
        assertTrue(result is GrokOAuthDiscoveryClient.Result.Failure)
        val failure = result as GrokOAuthDiscoveryClient.Result.Failure
        assertTrue(failure.error is QuotaError.Network)
        assertEquals(diagnosticsDigest, failure.error.diagnosticsDigest)
        if (forbiddenText != null) {
            assertFalse(failure.toString().contains(forbiddenText))
            assertFalse(failure.error.diagnosticsDigest.orEmpty().contains(forbiddenText))
        }
    }
}
