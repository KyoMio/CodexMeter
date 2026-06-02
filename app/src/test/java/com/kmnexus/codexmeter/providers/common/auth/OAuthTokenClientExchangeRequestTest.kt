package com.kmnexus.codexmeter.providers.common.auth

import com.kmnexus.codexmeter.core.network.ProviderHttpClient
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Locks the Anthropic-aligned exchange wire format: JSON body (form-encoded → HTTP 400),
 * including `state`, with the claude-cli User-Agent. Mirrors Hermes' working flow.
 */
class OAuthTokenClientExchangeRequestTest {
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.close()
    }

    @Test
    fun jsonExchange_sendsJsonBodyWithStateAndUserAgent() = runTest {
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .body("""{"access_token":"at","refresh_token":"rt","expires_in":3600}""")
                .build(),
        )
        val client = OAuthTokenClient(
            httpClient = ProviderHttpClient(),
            tokenEndpoint = server.url("/v1/oauth/token").toString(),
            clientId = "client-id",
            diagnosticsPrefix = "claude_oauth",
            useJsonBody = true,
            userAgent = "claude-cli/2.1.0 (external, cli)",
        )

        val result = client.exchangeAuthorizationCode(
            code = "the-code",
            codeVerifier = "the-verifier",
            redirectUri = "https://console.anthropic.com/oauth/code/callback",
            state = "the-state",
        )

        assertTrue(result is OAuthTokenClient.Result.Success)

        val recorded = server.takeRequest()
        assertEquals("claude-cli/2.1.0 (external, cli)", recorded.headers["User-Agent"])
        assertTrue(recorded.headers["Content-Type"].orEmpty().startsWith("application/json"))

        val obj = Json.parseToJsonElement(recorded.body?.utf8().orEmpty()) as JsonObject
        assertEquals("authorization_code", obj["grant_type"]!!.jsonPrimitive.content)
        assertEquals("client-id", obj["client_id"]!!.jsonPrimitive.content)
        assertEquals("the-code", obj["code"]!!.jsonPrimitive.content)
        assertEquals("the-state", obj["state"]!!.jsonPrimitive.content)
        assertEquals("the-verifier", obj["code_verifier"]!!.jsonPrimitive.content)
        assertEquals(
            "https://console.anthropic.com/oauth/code/callback",
            obj["redirect_uri"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun formExchange_omitsStateWhenAbsent() = runTest {
        server.enqueue(MockResponse.Builder().code(200).body("""{"access_token":"at"}""").build())
        val client = OAuthTokenClient(
            httpClient = ProviderHttpClient(),
            tokenEndpoint = server.url("/token").toString(),
            clientId = "client-id",
            diagnosticsPrefix = "antigravity_oauth",
        )

        client.exchangeAuthorizationCode(
            code = "c",
            codeVerifier = "v",
            redirectUri = "http://127.0.0.1:8089/callback",
        )

        val recorded = server.takeRequest()
        assertTrue(recorded.headers["Content-Type"].orEmpty().startsWith("application/x-www-form-urlencoded"))
        val formBody = recorded.body?.utf8().orEmpty()
        assertTrue(formBody.contains("grant_type=authorization_code"))
        assertTrue(formBody.contains("code_verifier=v"))
        // No state field when none is supplied (Google / Antigravity path).
        assertFalse(formBody.contains("state="))
    }
}
