package com.kmnexus.codexmeter.providers.grok.auth

import com.kmnexus.codexmeter.core.network.ProviderHttpClient
import com.kmnexus.codexmeter.domain.refresh.QuotaError
import com.kmnexus.codexmeter.providers.grok.auth.GrokOAuthConfig
import com.kmnexus.codexmeter.providers.grok.session.GrokSessionPayload
import java.net.URLDecoder
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.SocketEffect
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class GrokTokenRefresherTest {
    private val server = MockWebServer()
    private val json = Json {
        ignoreUnknownKeys = true
    }
    private val refreshedAt = Instant.parse("2026-09-10T12:00:00Z")
    private val clock = Clock.fixed(refreshedAt, ZoneOffset.UTC)

    @Before
    fun setUp() {
        server.start()
    }

    @After
    fun tearDown() {
        server.close()
    }

    @Test
    fun `successful refresh rotates tokens and writes back expiry`() = runTest {
        server.enqueue(successfulRefreshResponse())
        val refresher = newRefresher()

        val result = refresher.refresh(session())

        assertTrue(result is GrokTokenRefresher.Result.Success)
        val session = (result as GrokTokenRefresher.Result.Success).session
        assertEquals("new-synthetic-access-token", session.accessToken)
        assertEquals("new-synthetic-refresh-token", session.refreshToken)
        assertEquals("new-synthetic-id-token", session.idToken)
        assertEquals(refreshedAt.epochSecond, session.lastRefreshEpochSeconds)
        assertEquals(refreshedAt.epochSecond + 3600, session.tokenExpiresAtEpochSeconds)
        assertEquals("synthetic-account-id", session.accountId)
        assertEquals("grok@example.test", session.email)
        assertFalse(result.toString().contains("new-synthetic-access-token"))
        assertFalse(result.toString().contains("new-synthetic-refresh-token"))

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/oauth2/token", request.url.encodedPath)
        assertEquals("application/x-www-form-urlencoded", request.headers["Content-Type"])
        val body = request.formBodyParameters()
        assertEquals("refresh_token", body["grant_type"])
        assertEquals(GrokOAuthConfig.CLIENT_ID, body["client_id"])
        assertEquals("synthetic-refresh-token", body["refresh_token"])
    }

    @Test
    fun `cached endpoint is used without discovery`() = runTest {
        server.enqueue(successfulRefreshResponse())
        val discoveryCalls = AtomicInteger()
        val refresher = newRefresher(discoveryCalls)

        refresher.refresh(session())

        assertEquals(0, discoveryCalls.get())
    }

    @Test
    fun `response without refresh token keeps existing refresh token`() = runTest {
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .body("""{"access_token":"new-synthetic-access-token"}""")
                .build(),
        )
        val refresher = newRefresher()

        val result = refresher.refresh(session())

        assertTrue(result is GrokTokenRefresher.Result.Success)
        val session = (result as GrokTokenRefresher.Result.Success).session
        assertEquals("new-synthetic-access-token", session.accessToken)
        assertEquals("synthetic-refresh-token", session.refreshToken)
    }

    @Test
    fun `invalid grant maps to auth required`() = runTest {
        val responseBody = """{"error":"invalid_grant","error_description":"synthetic refresh failure"}"""
        server.enqueue(MockResponse.Builder().code(400).body(responseBody).build())
        val refresher = newRefresher()

        val result = refresher.refresh(session())

        assertTrue(result is GrokTokenRefresher.Result.Failure)
        val failure = result as GrokTokenRefresher.Result.Failure
        val error = failure.error
        assertTrue(error is QuotaError.AuthRequired)
        assertEquals(400, error.httpStatus)
        assertEquals("grok_refresh_auth_required_invalid_grant", error.diagnosticsDigest)
        assertFalse(error.diagnosticsDigest.orEmpty().contains("synthetic-refresh-token"))
        assertFalse(error.diagnosticsDigest.orEmpty().contains(responseBody))
    }

    /**
     * xAI rotates the refresh token on every grant: if the response is lost in transit the old token
     * is already consumed, so a transport retry could burn the replacement too. One attempt only.
     */
    @Test
    fun `transport failure is not retried and sends exactly one request`() = runTest {
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .onResponseStart(SocketEffect.Stall)
                .build(),
        )
        val noRetryClient = ProviderHttpClient(
            okHttpClient = OkHttpClient.Builder()
                .callTimeout(250, TimeUnit.MILLISECONDS)
                .retryOnConnectionFailure(false)
                .build(),
        )
        val refresher = newRefresher(httpClient = noRetryClient)

        val result = refresher.refresh(session())

        assertTrue(result is GrokTokenRefresher.Result.Failure)
        val failure = result as GrokTokenRefresher.Result.Failure
        assertTrue(failure.error is QuotaError.Network)
        assertEquals("grok_refresh_network_error", failure.error.diagnosticsDigest)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `legacy or missing endpoint triggers discovery before refresh`() = runTest {
        server.enqueue(successfulRefreshResponse())
        server.enqueue(successfulRefreshResponse())
        val discoveryCalls = AtomicInteger()
        val refresher = newRefresher(discoveryCalls)

        val legacy = refresher.refresh(session(tokenEndpoint = GrokOAuthConfig.LEGACY_TOKEN_ENDPOINT_URL))
        val missing = refresher.refresh(session(tokenEndpoint = null))

        assertTrue(legacy is GrokTokenRefresher.Result.Success)
        assertTrue(missing is GrokTokenRefresher.Result.Success)
        assertEquals(2, discoveryCalls.get())
        assertEquals(
            tokenEndpoint(),
            (legacy as GrokTokenRefresher.Result.Success).session.tokenEndpoint,
        )
        assertEquals(
            tokenEndpoint(),
            (missing as GrokTokenRefresher.Result.Success).session.tokenEndpoint,
        )
        assertEquals("/oauth2/token", server.takeRequest().url.encodedPath)
        assertEquals("/oauth2/token", server.takeRequest().url.encodedPath)
    }

    @Test
    fun `discovery failure maps to network error without touching token endpoint`() = runTest {
        val refresher = GrokTokenRefresher(
            httpClient = ProviderHttpClient(),
            json = json,
            clock = clock,
            allowInsecureHttpForTests = true,
            tokenEndpointDiscovery = { null },
        )

        val result = refresher.refresh(session(tokenEndpoint = null))

        assertTrue(result is GrokTokenRefresher.Result.Failure)
        val failure = result as GrokTokenRefresher.Result.Failure
        assertTrue(failure.error is QuotaError.Network)
        assertEquals("grok_refresh_discovery_failed", failure.error.diagnosticsDigest)
        assertEquals(0, server.requestCount)
    }

    private fun newRefresher(
        discoveryCalls: AtomicInteger? = null,
        httpClient: ProviderHttpClient = ProviderHttpClient(),
    ): GrokTokenRefresher =
        GrokTokenRefresher(
            httpClient = httpClient,
            json = json,
            clock = clock,
            allowInsecureHttpForTests = true,
            tokenEndpointDiscovery = {
                discoveryCalls?.incrementAndGet()
                tokenEndpoint()
            },
        )

    private fun tokenEndpoint(): String = server.url("/oauth2/token").toString()

    private fun session(tokenEndpoint: String? = tokenEndpoint()): GrokSessionPayload =
        GrokSessionPayload(
            accessToken = "synthetic-access-token",
            refreshToken = "synthetic-refresh-token",
            idToken = "synthetic-id-token",
            accountId = "synthetic-account-id",
            email = "grok@example.test",
            tokenEndpoint = tokenEndpoint,
        )

    private fun successfulRefreshResponse(): MockResponse =
        MockResponse.Builder()
            .code(200)
            .body(
                """
                {
                  "access_token": "new-synthetic-access-token",
                  "refresh_token": "new-synthetic-refresh-token",
                  "id_token": "new-synthetic-id-token",
                  "expires_in": 3600
                }
                """.trimIndent(),
            )
            .build()

    private fun mockwebserver3.RecordedRequest.formBodyParameters(): Map<String, String> =
        body?.utf8().orEmpty()
            .split("&")
            .filter { it.isNotBlank() }
            .associate { pair ->
                val parts = pair.split("=", limit = 2)
                urlDecode(parts[0]) to urlDecode(parts.getOrElse(1) { "" })
            }

    private fun urlDecode(value: String): String =
        URLDecoder.decode(value, Charsets.UTF_8.name())
}
