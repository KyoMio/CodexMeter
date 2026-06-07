package com.kmnexus.codexmeter.providers.common.auth

import com.kmnexus.codexmeter.core.network.ProviderHttpClient
import com.kmnexus.codexmeter.domain.refresh.QuotaError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OAuthTokenClientTest {
    private val client = OAuthTokenClient(
        httpClient = ProviderHttpClient(),
        tokenEndpoint = "https://example.test/token",
        clientId = "client-id",
        diagnosticsPrefix = "test",
    )

    @Test
    fun parse_success_mapsAllTokenFields() {
        val body = """{"access_token":"at-1","refresh_token":"rt-1","expires_in":3600,"id_token":"id-1"}"""

        val result = client.parse(body, "exchange")

        assertTrue(result is OAuthTokenClient.Result.Success)
        val tokens = (result as OAuthTokenClient.Result.Success).tokens
        assertEquals("at-1", tokens.accessToken)
        assertEquals("rt-1", tokens.refreshToken)
        assertEquals(3600L, tokens.expiresInSeconds)
        assertEquals("id-1", tokens.idToken)
    }

    @Test
    fun parse_missingAccessToken_mapsToTransientNetworkErrorNotReauth() {
        val result = client.parse("""{"refresh_token":"rt-1"}""", "exchange")

        assertTrue(result is OAuthTokenClient.Result.Failure)
        // A 2xx response lacking a usable access token is a malformed/transient server response, not
        // proof the credential is invalid (that is 401/403). It must not force a re-login.
        val error = (result as OAuthTokenClient.Result.Failure).error
        assertTrue(error is QuotaError.Network)
        assertEquals(false, error.userActionRequired)
        assertTrue(error.retryable)
    }

    @Test
    fun parse_blankAccessToken_mapsToTransientNetworkError() {
        val result = client.parse("""{"access_token":"  "}""", "refresh")

        assertTrue(result is OAuthTokenClient.Result.Failure)
        val error = (result as OAuthTokenClient.Result.Failure).error
        assertTrue(error is QuotaError.Network)
    }

    @Test
    fun parse_minimalResponse_keepsOptionalFieldsNull() {
        val result = client.parse("""{"access_token":"at-only"}""", "refresh")

        assertTrue(result is OAuthTokenClient.Result.Success)
        val tokens = (result as OAuthTokenClient.Result.Success).tokens
        assertEquals("at-only", tokens.accessToken)
        assertNull(tokens.refreshToken)
        assertNull(tokens.expiresInSeconds)
    }

    @Test
    fun parse_malformedBody_returnsFailure() {
        val result = client.parse("not json", "exchange")
        assertTrue(result is OAuthTokenClient.Result.Failure)
    }
}
