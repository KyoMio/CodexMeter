package com.kmnexus.codexmeter.providers.codex.auth

import com.kmnexus.codexmeter.core.network.ProviderHttpClient
import com.kmnexus.codexmeter.domain.refresh.QuotaError
import java.net.URLDecoder
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64
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

class CodexOAuthTokenExchangerTest {
    private val server = MockWebServer()
    private val json = Json {
        ignoreUnknownKeys = true
    }
    private val exchangedAt = Instant.parse("2026-05-24T06:00:00Z")
    private val clock = Clock.fixed(exchangedAt, ZoneOffset.UTC)

    @Before
    fun setUp() {
        server.start()
    }

    @After
    fun tearDown() {
        server.close()
    }

    @Test
    fun `posts authorization code grant body without leaking code in result text`() = runTest {
        server.enqueue(successfulExchangeResponse())
        val exchanger = newExchanger()

        val result = exchanger.exchange(exchangeRequest())

        assertTrue(result is CodexOAuthTokenExchanger.Result.Success)
        assertFalse(result.toString().contains(SAMPLE_CODE))
        assertFalse(result.toString().contains(SAMPLE_VERIFIER))
        val request = server.takeRequest()
        val body = request.formBodyParameters()
        assertEquals("POST", request.method)
        assertEquals("/oauth/token", request.url.encodedPath)
        assertEquals("application/x-www-form-urlencoded", request.headers["Content-Type"])
        assertEquals("app_EMoamEEZ73f0CkXaXp7hrann", body["client_id"])
        assertEquals("authorization_code", body["grant_type"])
        assertEquals(SAMPLE_CODE, body["code"])
        assertEquals(SAMPLE_VERIFIER, body["code_verifier"])
        assertEquals(CodexOAuthConfig.DEVICE_REDIRECT_URI, body["redirect_uri"])
    }

    @Test
    fun `successful response returns provider session payload`() = runTest {
        server.enqueue(successfulExchangeResponse())
        val exchanger = newExchanger()

        val result = exchanger.exchange(exchangeRequest())

        assertTrue(result is CodexOAuthTokenExchanger.Result.Success)
        val success = result as CodexOAuthTokenExchanger.Result.Success
        assertEquals("new-access-token", success.session.accessToken)
        assertEquals("new-refresh-token", success.session.refreshToken)
        assertEquals("new-id-token", success.session.idToken)
        assertEquals("synthetic-account-id", success.session.accountId)
        assertEquals(exchangedAt, success.session.lastRefresh)
    }

    @Test
    fun `successful device code exchange derives ChatGPT account id from id token claims`() = runTest {
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .body(
                    """
                    {
                      "access_token": "new-access-token",
                      "refresh_token": "new-refresh-token",
                      "id_token": "${idTokenWithAuthClaims(accountId = "workspace-123", email = "codex@example.test")}"
                    }
                    """.trimIndent(),
                )
                .build(),
        )
        val exchanger = newExchanger()

        val result = exchanger.exchange(exchangeRequest(redirectUri = CodexOAuthConfig.DEVICE_REDIRECT_URI))

        assertTrue(result is CodexOAuthTokenExchanger.Result.Success)
        val success = result as CodexOAuthTokenExchanger.Result.Success
        assertEquals("workspace-123", success.session.accountId)
        assertEquals("codex@example.test", success.session.accountEmail)
        assertFalse(result.toString().contains("workspace-123"))
    }

    @Test
    fun `successful device code exchange derives account email from top-level id token claim`() = runTest {
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .body(
                    """
                    {
                      "access_token": "new-access-token",
                      "refresh_token": "new-refresh-token",
                      "id_token": "${idTokenWithTopLevelEmail(accountId = "workspace-789", email = "top-level@example.test")}"
                    }
                    """.trimIndent(),
                )
                .build(),
        )
        val exchanger = newExchanger()

        val result = exchanger.exchange(exchangeRequest(redirectUri = CodexOAuthConfig.DEVICE_REDIRECT_URI))

        assertTrue(result is CodexOAuthTokenExchanger.Result.Success)
        val success = result as CodexOAuthTokenExchanger.Result.Success
        assertEquals("workspace-789", success.session.accountId)
        assertEquals("top-level@example.test", success.session.accountEmail)
        assertFalse(result.toString().contains("top-level@example.test"))
    }

    @Test
    fun `unauthorized exchange maps to auth required without leaking response or code`() = runTest {
        val responseBody = """{"error":"invalid_grant","detail":"unsafe exchange body"}"""
        server.enqueue(
            MockResponse.Builder()
                .code(400)
                .body(responseBody)
                .build(),
        )
        val exchanger = newExchanger()

        val result = exchanger.exchange(exchangeRequest())

        assertTrue(result is CodexOAuthTokenExchanger.Result.Failure)
        val failure = result as CodexOAuthTokenExchanger.Result.Failure
        assertTrue(failure.error is QuotaError.AuthRequired)
        assertEquals("codex_oauth_auth_required_invalid_grant", failure.error.diagnosticsDigest)
        assertFalse(failure.toString().contains(SAMPLE_CODE))
        assertFalse(failure.toString().contains(responseBody))
    }

    @Test
    fun `nested OpenAI token error maps to auth required instead of network`() = runTest {
        val responseBody = """
            {
              "error": {
                "type": "invalid_request_error",
                "code": "invalid_grant",
                "message": "Synthetic token exchange failure"
              }
            }
        """.trimIndent()
        server.enqueue(
            MockResponse.Builder()
                .code(400)
                .body(responseBody)
                .build(),
        )
        val exchanger = newExchanger()

        val result = exchanger.exchange(exchangeRequest())

        assertTrue(result is CodexOAuthTokenExchanger.Result.Failure)
        val failure = result as CodexOAuthTokenExchanger.Result.Failure
        assertTrue(failure.error is QuotaError.AuthRequired)
        assertEquals("codex_oauth_auth_required_invalid_grant", failure.error.diagnosticsDigest)
        assertFalse(failure.toString().contains("Synthetic token exchange failure"))
    }

    @Test
    fun `device code token exchange can override redirect uri`() = runTest {
        server.enqueue(successfulExchangeResponse())
        val exchanger = newExchanger()

        val result = exchanger.exchange(
            exchangeRequest(redirectUri = CodexOAuthConfig.DEVICE_REDIRECT_URI),
        )

        assertTrue(result is CodexOAuthTokenExchanger.Result.Success)
        val request = server.takeRequest()
        val body = request.formBodyParameters()
        assertEquals(CodexOAuthConfig.DEVICE_REDIRECT_URI, body["redirect_uri"])
    }

    private fun newExchanger(): CodexOAuthTokenExchanger =
        CodexOAuthTokenExchanger(
            httpClient = ProviderHttpClient(),
            json = json,
            endpointUrl = server.url("/oauth/token").toString(),
            clock = clock,
            allowInsecureHttpForTests = true,
        )

    private fun exchangeRequest(
        redirectUri: String = CodexOAuthConfig.DEVICE_REDIRECT_URI,
    ): CodexOAuthTokenExchangeRequest =
        CodexOAuthTokenExchangeRequest(
            authorizationCode = OAuthAuthorizationCode(SAMPLE_CODE),
            pkceVerifier = PkceVerifier(SAMPLE_VERIFIER),
            redirectUri = redirectUri,
        )

    private fun successfulExchangeResponse(): MockResponse =
        MockResponse.Builder()
            .code(200)
            .body(
                """
                {
                  "access_token": "new-access-token",
                  "refresh_token": "new-refresh-token",
                  "id_token": "new-id-token",
                  "account_id": "synthetic-account-id"
                }
                """.trimIndent(),
            )
            .build()

    private fun idTokenWithAuthClaims(
        accountId: String,
        email: String,
    ): String {
        val header = base64Url("{}")
        val payload = base64Url(
            """
            {
              "https://api.openai.com/auth": {
                "chatgpt_account_id": "$accountId",
                "email": "$email"
              }
            }
            """.trimIndent(),
        )
        return "$header.$payload.signature"
    }

    private fun idTokenWithTopLevelEmail(
        accountId: String,
        email: String,
    ): String {
        val header = base64Url("{}")
        val payload = base64Url(
            """
            {
              "email": "$email",
              "https://api.openai.com/auth": {
                "chatgpt_account_id": "$accountId"
              }
            }
            """.trimIndent(),
        )
        return "$header.$payload.signature"
    }

    private fun base64Url(value: String): String =
        Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(value.toByteArray(Charsets.UTF_8))

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

    private companion object {
        const val SAMPLE_CODE = "sample-authorization-code"
        const val SAMPLE_VERIFIER = "sample-pkce-verifier"
    }
}
