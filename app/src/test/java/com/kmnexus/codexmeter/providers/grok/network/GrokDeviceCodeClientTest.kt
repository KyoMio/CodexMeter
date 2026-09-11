package com.kmnexus.codexmeter.providers.grok.network

import com.kmnexus.codexmeter.core.network.ProviderHttpClient
import com.kmnexus.codexmeter.domain.refresh.QuotaError
import com.kmnexus.codexmeter.providers.grok.auth.GrokOAuthConfig
import com.kmnexus.codexmeter.providers.grok.auth.GrokJwtClaims
import java.net.URLDecoder
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class GrokDeviceCodeClientTest {
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
    fun `request device code posts client id and scope and maps challenge`() = runTest {
        server.enqueue(successfulDeviceAuthorizationResponse())
        val client = newClient()

        val result = client.requestDeviceCode(deviceAuthorizationEndpoint())

        assertTrue(result is GrokDeviceCodeClient.Result.Success)
        val challenge = (result as GrokDeviceCodeClient.Result.Success).value
        assertEquals(SAMPLE_DEVICE_CODE, challenge.deviceCode)
        assertEquals("ABCD-EFGH", challenge.userCode)
        assertEquals("https://auth.x.ai/device", challenge.verificationUri)
        assertEquals("https://auth.x.ai/device?user_code=ABCD-EFGH", challenge.verificationUriComplete)
        assertEquals(5, challenge.intervalSeconds)
        assertEquals(600, challenge.expiresInSeconds)
        assertFalse(challenge.toString().contains(SAMPLE_DEVICE_CODE))
        assertFalse(challenge.toString().contains("ABCD-EFGH"))

        val request = server.takeRequest()
        val form = request.formFields()
        assertEquals("POST", request.method)
        assertEquals("/device_authorize", request.url.encodedPath)
        assertTrue(request.headers["Content-Type"].orEmpty().contains("application/x-www-form-urlencoded"))
        assertEquals(GrokOAuthConfig.CLIENT_ID, form["client_id"])
        assertEquals(GrokOAuthConfig.SCOPE, form["scope"])
    }

    @Test
    fun `request device code defaults interval and expiry`() = runTest {
        server.enqueue(
            jsonResponse(
                """
                {
                  "device_code": "$SAMPLE_DEVICE_CODE",
                  "user_code": "ABCD-EFGH",
                  "verification_uri": "https://auth.x.ai/device"
                }
                """.trimIndent(),
            ),
        )
        val client = newClient()

        val result = client.requestDeviceCode(deviceAuthorizationEndpoint())

        val challenge = (result as GrokDeviceCodeClient.Result.Success).value
        assertEquals(5, challenge.intervalSeconds)
        assertEquals(300, challenge.expiresInSeconds)
    }

    /** Live xAI returns verification URIs on accounts.x.ai (not auth.x.ai) — must stay trusted. */
    @Test
    fun `request device code accepts accounts xai verification uri`() = runTest {
        server.enqueue(
            jsonResponse(
                """
                {
                  "device_code": "$SAMPLE_DEVICE_CODE",
                  "user_code": "84Y6-GZW7",
                  "verification_uri": "https://accounts.x.ai/oauth2/device",
                  "verification_uri_complete": "https://accounts.x.ai/oauth2/device?user_code=84Y6-GZW7",
                  "interval": 5,
                  "expires_in": 1800
                }
                """.trimIndent(),
            ),
        )
        val client = newClient()

        val result = client.requestDeviceCode(deviceAuthorizationEndpoint())

        val challenge = (result as GrokDeviceCodeClient.Result.Success).value
        assertEquals("https://accounts.x.ai/oauth2/device", challenge.verificationUri)
        assertEquals(
            "https://accounts.x.ai/oauth2/device?user_code=84Y6-GZW7",
            challenge.verificationUriComplete,
        )
    }

    @Test
    fun `request device code missing fields fails safely`() = runTest {
        server.enqueue(
            jsonResponse(
                """
                {
                  "user_code": "ABCD-EFGH",
                  "verification_uri": "https://auth.x.ai/device",
                  "interval": 5,
                  "expires_in": 600
                }
                """.trimIndent(),
            ),
        )
        val client = newClient()

        val result = client.requestDeviceCode(deviceAuthorizationEndpoint())

        assertNetworkFailure(
            result = result,
            diagnosticsDigest = "grok_device_code_decode_error",
            forbiddenText = "ABCD-EFGH",
        )
    }

    @Test
    fun `request device code rejects untrusted verification uri`() = runTest {
        server.enqueue(
            jsonResponse(
                """
                {
                  "device_code": "$SAMPLE_DEVICE_CODE",
                  "user_code": "ABCD-EFGH",
                  "verification_uri": "https://evil.example/device",
                  "interval": 5,
                  "expires_in": 600
                }
                """.trimIndent(),
            ),
        )
        val client = newClient()

        val result = client.requestDeviceCode(deviceAuthorizationEndpoint())

        assertNetworkFailure(
            result = result,
            diagnosticsDigest = "grok_device_code_unsafe_verification_uri",
            forbiddenText = "evil.example",
        )
    }

    @Test
    fun `pending poll then token success honors interval`() = runTest {
        server.enqueue(errorResponse(400, "authorization_pending"))
        server.enqueue(tokenSuccessResponse(expiresIn = 3600))
        val client = newClient(clock = Clock.fixed(NOW, ZoneOffset.UTC))

        val start = currentTime
        val result = client.awaitAuthorization(sampleChallenge(), tokenEndpoint())

        assertEquals(5_000L, currentTime - start)
        assertTrue(result is GrokDeviceCodeClient.Result.Success)
        val token = (result as GrokDeviceCodeClient.Result.Success).value
        assertEquals(SAMPLE_ACCESS_TOKEN, token.accessToken)
        assertEquals(SAMPLE_REFRESH_TOKEN, token.refreshToken)
        assertEquals(SAMPLE_ID_TOKEN, token.idToken)
        assertEquals(NOW.epochSecond + 3600, token.expiresAtEpochSeconds)
        assertFalse(token.toString().contains(SAMPLE_ACCESS_TOKEN))
        assertFalse(token.toString().contains(SAMPLE_REFRESH_TOKEN))

        val pollForm = server.takeRequest().formFields()
        assertEquals(GrokOAuthConfig.DEVICE_CODE_GRANT_TYPE, pollForm["grant_type"])
        assertEquals(GrokOAuthConfig.CLIENT_ID, pollForm["client_id"])
        assertEquals(SAMPLE_DEVICE_CODE, pollForm["device_code"])
    }

    @Test
    fun `slow down increases poll interval by five seconds`() = runTest {
        server.enqueue(errorResponse(400, "slow_down"))
        server.enqueue(tokenSuccessResponse())
        val client = newClient()

        val start = currentTime
        val result = client.awaitAuthorization(sampleChallenge(), tokenEndpoint())

        assertEquals(10_000L, currentTime - start)
        assertTrue(result is GrokDeviceCodeClient.Result.Success)
    }

    @Test
    fun `access denied and expired token are terminal states`() = runTest {
        server.enqueue(errorResponse(400, "access_denied"))
        server.enqueue(errorResponse(400, "authorization_denied"))
        server.enqueue(errorResponse(400, "expired_token"))
        val client = newClient()

        val denied = client.awaitAuthorization(sampleChallenge(), tokenEndpoint())
        val deniedAlias = client.awaitAuthorization(sampleChallenge(), tokenEndpoint())
        val expired = client.awaitAuthorization(sampleChallenge(), tokenEndpoint())

        assertTrue(denied is GrokDeviceCodeClient.Result.Denied)
        assertTrue(deniedAlias is GrokDeviceCodeClient.Result.Denied)
        assertTrue(expired is GrokDeviceCodeClient.Result.Expired)
        assertEquals(3, server.requestCount)
    }

    @Test
    fun `deadline expiry stops polling as expired`() = runTest {
        repeat(3) { server.enqueue(errorResponse(400, "authorization_pending")) }
        val client = newClient(clock = VirtualClock { currentTime })

        val start = currentTime
        val result = client.awaitAuthorization(sampleChallenge(expiresInSeconds = 12), tokenEndpoint())

        assertTrue(result is GrokDeviceCodeClient.Result.Expired)
        assertEquals(12_000L, currentTime - start)
        assertEquals(3, server.requestCount)
    }

    @Test
    fun `cancelling poll stops further polls`() = runTest {
        server.enqueue(errorResponse(400, "authorization_pending"))
        server.enqueue(errorResponse(400, "authorization_pending"))
        val client = newClient()

        val job = launch { client.awaitAuthorization(sampleChallenge(expiresInSeconds = 300), tokenEndpoint()) }
        // Let the coroutine run up to its first IO dispatch, then block until that first poll really
        // reaches the server — so a regression that never starts polling fails here instead of hiding
        // behind a forgiving request-count bound.
        testScheduler.runCurrent()
        assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))
        job.cancelAndJoin()
        val countAfterCancel = server.requestCount

        advanceUntilIdle()

        assertEquals(countAfterCancel, server.requestCount)
        assertEquals(1, countAfterCancel)
        assertTrue(job.isCancelled)
    }

    @Test
    fun `missing expires_in falls back to access token jwt exp`() = runTest {
        val jwtWithExp = jwt("""{"sub":"user-123","exp":1893456000}""")
        server.enqueue(tokenSuccessResponse(expiresIn = null, accessToken = jwtWithExp))
        server.enqueue(tokenSuccessResponse(expiresIn = null))
        server.enqueue(tokenSuccessResponse(expiresIn = 3600, accessToken = jwtWithExp))
        val client = newClient(clock = Clock.fixed(NOW, ZoneOffset.UTC))

        val fallback = client.awaitAuthorization(sampleChallenge(), tokenEndpoint())
        val withoutAnyExpiry = client.awaitAuthorization(sampleChallenge(), tokenEndpoint())
        val preferringExpiresIn = client.awaitAuthorization(sampleChallenge(), tokenEndpoint())

        val fallbackToken = (fallback as GrokDeviceCodeClient.Result.Success).value
        val noExpiryToken = (withoutAnyExpiry as GrokDeviceCodeClient.Result.Success).value
        val preferredToken = (preferringExpiresIn as GrokDeviceCodeClient.Result.Success).value
        assertEquals(1893456000L, fallbackToken.expiresAtEpochSeconds)
        assertNull(noExpiryToken.expiresAtEpochSeconds)
        assertEquals(NOW.epochSecond + 3600, preferredToken.expiresAtEpochSeconds)
    }

    @Test
    fun `token response missing refresh token fails safely`() = runTest {
        server.enqueue(
            jsonResponse(
                """
                {
                  "access_token": "$SAMPLE_ACCESS_TOKEN",
                  "id_token": "$SAMPLE_ID_TOKEN",
                  "expires_in": 3600
                }
                """.trimIndent(),
            ),
        )
        val client = newClient()

        val result = client.awaitAuthorization(sampleChallenge(), tokenEndpoint())

        assertTrue(result is GrokDeviceCodeClient.Result.Failure)
        val failure = result as GrokDeviceCodeClient.Result.Failure
        assertTrue(failure.error is QuotaError.AuthRequired)
        assertEquals("grok_refresh_token_missing", failure.error.diagnosticsDigest)
        assertFalse(failure.toString().contains(SAMPLE_ACCESS_TOKEN))
    }

    @Test
    fun `jwt claims parse email name sub and exp`() {
        val claims = jwt(
            """
            {
              "sub": "user-123",
              "email": "grok@example.com",
              "name": "Grok User",
              "exp": 1893456000
            }
            """.trimIndent(),
        )

        assertEquals("grok@example.com", GrokJwtClaims.email(claims))
        assertEquals("Grok User", GrokJwtClaims.name(claims))
        assertEquals("user-123", GrokJwtClaims.sub(claims))
        assertEquals(1893456000L, GrokJwtClaims.expirationEpochSeconds(claims))
        assertNull(GrokJwtClaims.email("not-a-jwt"))
        assertNull(GrokJwtClaims.email(null))
        assertNull(GrokJwtClaims.expirationEpochSeconds("not-a-jwt"))
    }

    private fun newClient(
        clock: Clock = Clock.systemUTC(),
    ): GrokDeviceCodeClient =
        GrokDeviceCodeClient(
            httpClient = ProviderHttpClient(),
            json = json,
            userAgent = GrokDeviceCodeClient.DEFAULT_USER_AGENT,
            clock = clock,
            allowInsecureHttpForTests = true,
        )

    /** Mirrors the scheduler's virtual time so deadline math tracks runTest's accelerated clock. */
    private class VirtualClock(
        private val nowMillis: () -> Long,
    ) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = this
        override fun instant(): Instant = Instant.ofEpochMilli(nowMillis())
    }

    private fun deviceAuthorizationEndpoint(): String = server.url("/device_authorize").toString()

    private fun tokenEndpoint(): String = server.url("/token").toString()

    private fun sampleChallenge(expiresInSeconds: Int = 600): GrokDeviceCodeChallenge =
        GrokDeviceCodeChallenge(
            deviceCode = SAMPLE_DEVICE_CODE,
            userCode = "ABCD-EFGH",
            verificationUri = "https://auth.x.ai/device",
            verificationUriComplete = "https://auth.x.ai/device?user_code=ABCD-EFGH",
            intervalSeconds = 5,
            expiresInSeconds = expiresInSeconds,
        )

    private fun successfulDeviceAuthorizationResponse(): MockResponse =
        jsonResponse(
            """
            {
              "device_code": "$SAMPLE_DEVICE_CODE",
              "user_code": "ABCD-EFGH",
              "verification_uri": "https://auth.x.ai/device",
              "verification_uri_complete": "https://auth.x.ai/device?user_code=ABCD-EFGH",
              "interval": 5,
              "expires_in": 600,
              "ignored": "value"
            }
            """.trimIndent(),
        )

    private fun tokenSuccessResponse(
        expiresIn: Long? = 3600,
        accessToken: String = SAMPLE_ACCESS_TOKEN,
    ): MockResponse {
        val expiresInField = expiresIn?.let { ""","expires_in":$it""" }.orEmpty()
        return jsonResponse(
            """
            {"access_token":"$accessToken","refresh_token":"$SAMPLE_REFRESH_TOKEN","id_token":"$SAMPLE_ID_TOKEN"$expiresInField}
            """.trimIndent(),
        )
    }

    private fun errorResponse(statusCode: Int, error: String): MockResponse =
        MockResponse.Builder()
            .code(statusCode)
            .body("""{"error":"$error"}""")
            .build()

    private fun jsonResponse(body: String): MockResponse =
        MockResponse.Builder()
            .code(200)
            .body(body)
            .build()

    private fun jwt(payload: String): String {
        val header = Base64.getUrlEncoder().withoutPadding()
            .encodeToString("""{"alg":"RS256","typ":"JWT"}""".toByteArray())
        val payloadSegment = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(payload.toByteArray())
        return "$header.$payloadSegment.signature"
    }

    private fun mockwebserver3.RecordedRequest.formFields(): Map<String, String> =
        body?.utf8().orEmpty()
            .split('&')
            .filter { it.isNotBlank() }
            .associate { entry ->
                val separatorIndex = entry.indexOf('=')
                val name = entry.substring(0, separatorIndex).urlDecoded()
                val value = entry.substring(separatorIndex + 1).urlDecoded()
                name to value
            }

    private fun String.urlDecoded(): String = URLDecoder.decode(this, "UTF-8")

    private fun assertNetworkFailure(
        result: GrokDeviceCodeClient.Result<*>,
        diagnosticsDigest: String,
        forbiddenText: String,
    ) {
        assertTrue(result is GrokDeviceCodeClient.Result.Failure)
        val failure = result as GrokDeviceCodeClient.Result.Failure
        assertTrue(failure.error is QuotaError.Network)
        assertEquals(diagnosticsDigest, failure.error.diagnosticsDigest)
        assertFalse(failure.toString().contains(forbiddenText))
        assertFalse(failure.error.diagnosticsDigest.orEmpty().contains(forbiddenText))
    }

    private companion object {
        val NOW: Instant = Instant.parse("2026-09-10T06:00:00Z")
        const val SAMPLE_DEVICE_CODE = "synthetic-device-code"
        const val SAMPLE_ACCESS_TOKEN = "synthetic-access-token"
        const val SAMPLE_REFRESH_TOKEN = "synthetic-refresh-token"
        const val SAMPLE_ID_TOKEN = "synthetic-id-token"
    }
}
