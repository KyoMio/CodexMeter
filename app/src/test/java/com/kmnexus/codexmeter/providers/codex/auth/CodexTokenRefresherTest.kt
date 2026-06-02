package com.kmnexus.codexmeter.providers.codex.auth

import com.kmnexus.codexmeter.core.network.ProviderHttpClient
import com.kmnexus.codexmeter.domain.refresh.QuotaError
import com.kmnexus.codexmeter.providers.codex.session.CodexSessionPayload
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

class CodexTokenRefresherTest {
    private val server = MockWebServer()
    private val json = Json {
        ignoreUnknownKeys = true
    }
    private val refreshedAt = Instant.parse("2026-05-23T12:00:00Z")
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
    fun `posts refresh token grant body`() = runTest {
        server.enqueue(successfulRefreshResponse())
        val refresher = newRefresher()

        val result = refresher.refresh(existingSession())

        assertTrue(result is CodexTokenRefresher.Result.Success)
        val request = server.takeRequest()
        val body = request.formBodyParameters()
        assertEquals("POST", request.method)
        assertEquals("/oauth/token", request.url.encodedPath)
        assertEquals("application/x-www-form-urlencoded", request.headers["Content-Type"])
        assertEquals("app_EMoamEEZ73f0CkXaXp7hrann", body["client_id"])
        assertEquals("refresh_token", body["grant_type"])
        assertEquals("synthetic-refresh-token", body["refresh_token"])
        assertEquals("openid profile email", body["scope"])
    }

    @Test
    fun `successful response replaces access refresh and id tokens`() = runTest {
        server.enqueue(successfulRefreshResponse())
        val refresher = newRefresher()

        val result = refresher.refresh(existingSession())

        assertTrue(result is CodexTokenRefresher.Result.Success)
        val success = result as CodexTokenRefresher.Result.Success
        assertEquals("new-synthetic-access-token", success.session.accessToken)
        assertEquals("new-synthetic-refresh-token", success.session.refreshToken)
        assertEquals("new-synthetic-id-token", success.session.idToken)
        assertEquals("synthetic-account-id", success.session.accountId)
        assertEquals(refreshedAt, success.session.lastRefresh)
        assertFalse(result.toString().contains("new-synthetic-access-token"))
        assertFalse(result.toString().contains("new-synthetic-refresh-token"))
        assertFalse(result.toString().contains("new-synthetic-id-token"))
    }

    @Test
    fun `successful response without id token preserves existing id token`() = runTest {
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .body(
                    """
                    {
                      "access_token": "new-synthetic-access-token",
                      "refresh_token": "new-synthetic-refresh-token"
                    }
                    """.trimIndent(),
                )
                .build(),
        )
        val refresher = newRefresher()

        val result = refresher.refresh(existingSession())

        assertTrue(result is CodexTokenRefresher.Result.Success)
        val success = result as CodexTokenRefresher.Result.Success
        assertEquals("new-synthetic-access-token", success.session.accessToken)
        assertEquals("new-synthetic-refresh-token", success.session.refreshToken)
        assertEquals("synthetic-id-token", success.session.idToken)
    }

    @Test
    fun `successful response fills missing account id from refreshed id token claims`() = runTest {
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .body(
                    """
                    {
                      "access_token": "new-synthetic-access-token",
                      "refresh_token": "new-synthetic-refresh-token",
                      "id_token": "${idTokenWithAuthClaims(accountId = "workspace-456", email = "refresh@example.test")}"
                    }
                    """.trimIndent(),
                )
                .build(),
        )
        val refresher = newRefresher()

        val result = refresher.refresh(existingSession(accountId = null, idToken = null, accountEmail = null))

        assertTrue(result is CodexTokenRefresher.Result.Success)
        val success = result as CodexTokenRefresher.Result.Success
        assertEquals("workspace-456", success.session.accountId)
        assertEquals("refresh@example.test", success.session.accountEmail)
    }

    @Test
    fun refreshKeepsExistingRefreshTokenWhenResponseOmitsRotation() = runTest {
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .body(
                    """
                    {
                      "access_token": "new-synthetic-access-token",
                      "id_token": "new-synthetic-id-token"
                    }
                    """.trimIndent(),
                )
                .build(),
        )
        val refresher = newRefresher()

        val result = refresher.refresh(existingSession())

        assertTrue(result is CodexTokenRefresher.Result.Success)
        val success = result as CodexTokenRefresher.Result.Success
        assertEquals("new-synthetic-access-token", success.session.accessToken)
        assertEquals("synthetic-refresh-token", success.session.refreshToken)
        assertEquals("new-synthetic-id-token", success.session.idToken)
        assertEquals(refreshedAt, success.session.lastRefresh)
    }

    @Test
    fun `unauthorized response maps to auth required even with plain body`() = runTest {
        val responseBody = "synthetic unauthorized refresh failure"
        server.enqueue(refreshFailureResponse(code = 401, responseBody = responseBody))
        val refresher = newRefresher()

        val result = refresher.refresh(existingSession())

        assertAuthRequired(
            result = result,
            httpStatus = 401,
            diagnosticsDigest = "codex_refresh_auth_required_401",
            responseBody = responseBody,
        )
    }

    @Test
    fun `forbidden response maps to auth required even with plain body`() = runTest {
        val responseBody = "synthetic forbidden refresh failure"
        server.enqueue(refreshFailureResponse(code = 403, responseBody = responseBody))
        val refresher = newRefresher()

        val result = refresher.refresh(existingSession())

        assertAuthRequired(
            result = result,
            httpStatus = 403,
            diagnosticsDigest = "codex_refresh_auth_required_403",
            responseBody = responseBody,
        )
    }

    @Test
    fun `refresh token expired maps to auth required`() = runTest {
        val responseBody = terminalRefreshFailureBody("refresh_token_expired")
        server.enqueue(refreshFailureResponse(responseBody = responseBody))
        val refresher = newRefresher()

        val result = refresher.refresh(existingSession())

        assertAuthRequired(
            result = result,
            httpStatus = 400,
            diagnosticsDigest = "codex_refresh_auth_required_refresh_token_expired",
            responseBody = responseBody,
        )
    }

    @Test
    fun `refresh token reused maps to auth required`() = runTest {
        val responseBody = terminalRefreshFailureBody("refresh_token_reused")
        server.enqueue(refreshFailureResponse(responseBody = responseBody))
        val refresher = newRefresher()

        val result = refresher.refresh(existingSession())

        assertAuthRequired(
            result = result,
            httpStatus = 400,
            diagnosticsDigest = "codex_refresh_auth_required_refresh_token_reused",
            responseBody = responseBody,
        )
    }

    @Test
    fun nestedRefreshTokenReusedErrorMapsToAuthRequired() = runTest {
        val rawMessage = "synthetic nested refresh token reuse message"
        val responseBody = """
            {
              "error": {
                "code": "refresh_token_reused",
                "message": "$rawMessage"
              }
            }
        """.trimIndent()
        server.enqueue(refreshFailureResponse(responseBody = responseBody))
        val refresher = newRefresher()

        val result = refresher.refresh(existingSession())

        assertAuthRequired(
            result = result,
            httpStatus = 400,
            diagnosticsDigest = "codex_refresh_auth_required_refresh_token_reused",
            responseBody = responseBody,
        )
        val failure = result as CodexTokenRefresher.Result.Failure
        assertTrue(failure.error.diagnosticsDigest.orEmpty().contains("refresh_token_reused"))
        assertFalse(failure.error.diagnosticsDigest.orEmpty().contains(rawMessage))
    }

    @Test
    fun `invalid grant maps to auth required`() = runTest {
        val responseBody = terminalRefreshFailureBody("invalid_grant")
        server.enqueue(refreshFailureResponse(responseBody = responseBody))
        val refresher = newRefresher()

        val result = refresher.refresh(existingSession())

        assertAuthRequired(
            result = result,
            httpStatus = 400,
            diagnosticsDigest = "codex_refresh_auth_required_invalid_grant",
            responseBody = responseBody,
        )
    }

    private fun newRefresher(): CodexTokenRefresher =
        CodexTokenRefresher(
            httpClient = ProviderHttpClient(),
            json = json,
            endpointUrl = server.url("/oauth/token").toString(),
            clock = clock,
            allowInsecureHttpForTests = true,
        )

    private fun existingSession(
        accountId: String? = "synthetic-account-id",
        idToken: String? = "synthetic-id-token",
        accountEmail: String? = null,
    ): CodexSessionPayload =
        CodexSessionPayload(
            accessToken = "synthetic-access-token",
            refreshToken = "synthetic-refresh-token",
            idToken = idToken,
            accountId = accountId,
            accountEmail = accountEmail,
            lastRefresh = Instant.parse("2026-05-23T10:00:00Z"),
        )

    private fun successfulRefreshResponse(): MockResponse =
        MockResponse.Builder()
            .code(200)
            .body(
                """
                {
                  "access_token": "new-synthetic-access-token",
                  "refresh_token": "new-synthetic-refresh-token",
                  "id_token": "new-synthetic-id-token"
                }
                """.trimIndent(),
            )
            .build()

    private fun refreshFailureResponse(
        code: Int = 400,
        responseBody: String,
    ): MockResponse =
        MockResponse.Builder()
            .code(code)
            .body(responseBody)
            .build()

    private fun terminalRefreshFailureBody(errorCode: String): String =
        """
        {
          "error": "$errorCode",
          "error_description": "synthetic refresh failure"
        }
        """.trimIndent()

    private fun assertAuthRequired(
        result: CodexTokenRefresher.Result,
        httpStatus: Int,
        diagnosticsDigest: String,
        responseBody: String,
    ) {
        assertTrue(result is CodexTokenRefresher.Result.Failure)
        val failure = result as CodexTokenRefresher.Result.Failure
        val error = failure.error
        assertTrue(error is QuotaError.AuthRequired)
        assertEquals(httpStatus, error.httpStatus)
        assertEquals(diagnosticsDigest, error.diagnosticsDigest)
        assertFalse(error.diagnosticsDigest.orEmpty().contains("synthetic-refresh-token"))
        assertFalse(error.diagnosticsDigest.orEmpty().contains(responseBody))
    }

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
}
