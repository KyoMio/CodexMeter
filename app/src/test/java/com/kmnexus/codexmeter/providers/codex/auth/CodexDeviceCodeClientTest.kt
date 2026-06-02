package com.kmnexus.codexmeter.providers.codex.auth

import com.kmnexus.codexmeter.core.network.ProviderHttpClient
import com.kmnexus.codexmeter.domain.refresh.QuotaError
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class CodexDeviceCodeClientTest {
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
    fun `request success maps challenge fields and posts json body`() = runTest {
        server.enqueue(successfulChallengeResponse())
        val client = newClient()

        val result = client.requestDeviceCode()

        assertTrue(result is CodexDeviceCodeClient.Result.Success)
        val challenge = (result as CodexDeviceCodeClient.Result.Success).value
        assertEquals("ABCD-EFGH", challenge.userCode)
        assertEquals(SAMPLE_DEVICE_AUTH_ID, challenge.deviceAuthId)
        assertEquals(CodexOAuthConfig.DEVICE_VERIFICATION_URL, challenge.verificationUri)
        assertEquals(7, challenge.intervalSeconds)
        assertEquals(900, challenge.expiresInSeconds)
        assertFalse(result.toString().contains(SAMPLE_DEVICE_AUTH_ID))

        val request = server.takeRequest()
        val requestBody = request.jsonBody()
        assertEquals("POST", request.method)
        assertEquals("/api/accounts/deviceauth/usercode", request.url.encodedPath)
        assertTrue(request.headers["Content-Type"].orEmpty().contains("application/json"))
        assertEquals(CodexOAuthConfig.CLIENT_ID, requestBody.stringField("client_id"))
        assertEquals(CodexOAuthConfig.SCOPE, requestBody.stringField("scope"))
        assertEquals(CodexOAuthConfig.DEVICE_REDIRECT_URI, requestBody.stringField("redirect_uri"))
    }

    @Test
    fun `request accepts current OpenAI challenge schema with string interval and expires at`() = runTest {
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .body(
                    """
                    {
                      "device_auth_id": "$SAMPLE_DEVICE_AUTH_ID",
                      "user_code": "ABCD-EFGH",
                      "interval": "5",
                      "expires_at": "2026-05-25T10:36:28Z"
                    }
                    """.trimIndent(),
                )
                .build(),
        )
        val client = newClient(
            clock = Clock.fixed(Instant.parse("2026-05-25T10:26:28Z"), ZoneOffset.UTC),
        )

        val result = client.requestDeviceCode()

        assertTrue(result is CodexDeviceCodeClient.Result.Success)
        val challenge = (result as CodexDeviceCodeClient.Result.Success).value
        assertEquals("ABCD-EFGH", challenge.userCode)
        assertEquals(SAMPLE_DEVICE_AUTH_ID, challenge.deviceAuthId)
        assertEquals(CodexOAuthConfig.DEVICE_VERIFICATION_URL, challenge.verificationUri)
        assertEquals(5, challenge.intervalSeconds)
        assertEquals(600, challenge.expiresInSeconds)
    }

    @Test
    fun `request rejects unsafe verification uri`() = runTest {
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .body(
                    """
                    {
                      "device_auth_id": "$SAMPLE_DEVICE_AUTH_ID",
                      "user_code": "ABCD-EFGH",
                      "verification_uri": "https://evil.example/codex/device",
                      "interval": 7,
                      "expires_in": 900
                    }
                    """.trimIndent(),
                )
                .build(),
        )
        val client = newClient()

        val result = client.requestDeviceCode()

        assertNetworkFailure(
            result = result,
            diagnosticsDigest = "codex_device_code_unsafe_verification_uri",
            forbiddenText = "evil.example",
        )
    }

    @Test
    fun `request missing user code fails safely`() = runTest {
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .body(
                    """
                    {
                      "device_auth_id": "$SAMPLE_DEVICE_AUTH_ID",
                      "interval": 7,
                      "expires_in": 900
                    }
                    """.trimIndent(),
                )
                .build(),
        )
        val client = newClient()

        val result = client.requestDeviceCode()

        assertNetworkFailure(
            result = result,
            diagnosticsDigest = "codex_device_code_decode_error",
            forbiddenText = SAMPLE_DEVICE_AUTH_ID,
        )
    }

    @Test
    fun `request non success fails safely`() = runTest {
        val responseBody = "synthetic unsafe device-code response"
        server.enqueue(
            MockResponse.Builder()
                .code(429)
                .body(responseBody)
                .build(),
        )
        val client = newClient()

        val result = client.requestDeviceCode()

        assertNetworkFailure(
            result = result,
            diagnosticsDigest = "codex_device_code_http_429",
            forbiddenText = responseBody,
        )
    }

    @Test
    fun `poll 403 and 404 mean pending not auth required`() = runTest {
        server.enqueue(MockResponse.Builder().code(403).body("synthetic pending body").build())
        server.enqueue(MockResponse.Builder().code(404).body("synthetic pending body").build())
        val client = newClient()

        val firstResult = client.pollAuthorization(sampleChallenge())
        val secondResult = client.pollAuthorization(sampleChallenge())

        assertTrue(firstResult is CodexDeviceCodeClient.Result.Pending)
        assertTrue(secondResult is CodexDeviceCodeClient.Result.Pending)
        assertFalse(firstResult.toString().contains(SAMPLE_DEVICE_AUTH_ID))
        assertFalse(secondResult.toString().contains(SAMPLE_DEVICE_AUTH_ID))

        val firstRequestBody = server.takeRequest().jsonBody()
        val secondRequestBody = server.takeRequest().jsonBody()
        assertEquals(SAMPLE_DEVICE_AUTH_ID, firstRequestBody.stringField("device_auth_id"))
        assertEquals(SAMPLE_DEVICE_AUTH_ID, secondRequestBody.stringField("device_auth_id"))
        assertEquals("ABCD-EFGH", firstRequestBody.stringField("user_code"))
        assertEquals("ABCD-EFGH", secondRequestBody.stringField("user_code"))
    }

    @Test
    fun `poll 400 deviceauth authorization pending maps to pending`() = runTest {
        server.enqueue(
            MockResponse.Builder()
                .code(400)
                .body(
                    """
                    {
                      "error": {
                        "type": "invalid_request_error",
                        "code": "deviceauth_authorization_pending",
                        "message": "Device authorization is pending. Please try again."
                      }
                    }
                    """.trimIndent(),
                )
                .build(),
        )
        val client = newClient()

        val result = client.pollAuthorization(sampleChallenge())

        assertTrue(result is CodexDeviceCodeClient.Result.Pending)
        val requestBody = server.takeRequest().jsonBody()
        assertEquals(SAMPLE_DEVICE_AUTH_ID, requestBody.stringField("device_auth_id"))
        assertEquals("ABCD-EFGH", requestBody.stringField("user_code"))
    }

    @Test
    fun `poll 200 maps authorization code and code verifier`() = runTest {
        server.enqueue(successfulAuthorizationResponse())
        val client = newClient()

        val result = client.pollAuthorization(sampleChallenge())

        assertTrue(result is CodexDeviceCodeClient.Result.Success)
        val authorization = (result as CodexDeviceCodeClient.Result.Success).value
        assertEquals(SAMPLE_AUTHORIZATION_CODE, authorization.authorizationCode)
        assertEquals(SAMPLE_CODE_VERIFIER, authorization.codeVerifier)
        assertFalse(result.toString().contains(SAMPLE_AUTHORIZATION_CODE))
        assertFalse(result.toString().contains(SAMPLE_CODE_VERIFIER))
    }

    @Test
    fun `poll missing fields fails safely`() = runTest {
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .body(
                    """
                    {
                      "authorization_code": "$SAMPLE_AUTHORIZATION_CODE"
                    }
                    """.trimIndent(),
                )
                .build(),
        )
        val client = newClient()

        val result = client.pollAuthorization(sampleChallenge())

        assertNetworkFailure(
            result = result,
            diagnosticsDigest = "codex_device_authorization_decode_error",
            forbiddenText = SAMPLE_AUTHORIZATION_CODE,
        )
    }

    private fun newClient(
        clock: Clock = Clock.systemUTC(),
    ): CodexDeviceCodeClient =
        CodexDeviceCodeClient(
            httpClient = ProviderHttpClient(),
            json = json,
            userCodeEndpointUrl = server.url("/api/accounts/deviceauth/usercode").toString(),
            tokenEndpointUrl = server.url("/api/accounts/deviceauth/token").toString(),
            clock = clock,
            allowInsecureHttpForTests = true,
        )

    private fun sampleChallenge(): DeviceCodeChallenge =
        DeviceCodeChallenge(
            userCode = "ABCD-EFGH",
            deviceAuthId = SAMPLE_DEVICE_AUTH_ID,
            verificationUri = CodexOAuthConfig.DEVICE_VERIFICATION_URL,
            intervalSeconds = 7,
            expiresInSeconds = 900,
        )

    private fun successfulChallengeResponse(): MockResponse =
        MockResponse.Builder()
            .code(200)
            .body(
                """
                {
                  "user_code": "ABCD-EFGH",
                  "device_auth_id": "$SAMPLE_DEVICE_AUTH_ID",
                  "verification_uri": "${CodexOAuthConfig.DEVICE_VERIFICATION_URL}",
                  "interval": 7,
                  "expires_in": 900,
                  "ignored": "value"
                }
                """.trimIndent(),
            )
            .build()

    private fun successfulAuthorizationResponse(): MockResponse =
        MockResponse.Builder()
            .code(200)
            .body(
                """
                {
                  "authorization_code": "$SAMPLE_AUTHORIZATION_CODE",
                  "code_verifier": "$SAMPLE_CODE_VERIFIER"
                }
                """.trimIndent(),
            )
            .build()

    private fun mockwebserver3.RecordedRequest.jsonBody(): JsonObject =
        json.parseToJsonElement(body?.utf8().orEmpty()) as JsonObject

    private fun JsonObject.stringField(name: String): String =
        (this[name] as JsonPrimitive).contentOrNull.orEmpty()

    private fun assertNetworkFailure(
        result: CodexDeviceCodeClient.Result<*>,
        diagnosticsDigest: String,
        forbiddenText: String,
    ) {
        assertTrue(result is CodexDeviceCodeClient.Result.Failure)
        val failure = result as CodexDeviceCodeClient.Result.Failure
        assertTrue(failure.error is QuotaError.Network)
        assertEquals(diagnosticsDigest, failure.error.diagnosticsDigest)
        assertFalse(failure.toString().contains(forbiddenText))
        assertFalse(failure.error.diagnosticsDigest.orEmpty().contains(forbiddenText))
    }

    private companion object {
        const val SAMPLE_DEVICE_AUTH_ID = "synthetic-device-auth-id"
        const val SAMPLE_AUTHORIZATION_CODE = "synthetic-authorization-code"
        const val SAMPLE_CODE_VERIFIER = "synthetic-code-verifier"
    }
}
