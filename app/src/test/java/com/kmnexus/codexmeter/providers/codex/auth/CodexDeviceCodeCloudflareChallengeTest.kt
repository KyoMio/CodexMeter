package com.kmnexus.codexmeter.providers.codex.auth

import com.kmnexus.codexmeter.core.network.ProviderHttpClient
import com.kmnexus.codexmeter.domain.refresh.QuotaError
import java.time.Clock
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

class CodexDeviceCodeCloudflareChallengeTest {
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
    fun `poll 403 cloudflare challenge body fails blocked instead of pending`() = runTest {
        server.enqueue(MockResponse.Builder().code(403).body(cloudflareChallengeBody()).build())
        val client = newClient()

        val result = client.pollAuthorization(sampleChallenge())

        assertBlockedFailure(
            result = result,
            diagnosticsDigest = "codex_device_authorization_blocked",
        )
    }

    @Test
    fun `poll 503 cloudflare challenge body fails blocked not http code`() = runTest {
        server.enqueue(MockResponse.Builder().code(503).body(cloudflareChallengeBody()).build())
        val client = newClient()

        val result = client.pollAuthorization(sampleChallenge())

        assertBlockedFailure(
            result = result,
            diagnosticsDigest = "codex_device_authorization_blocked",
        )
    }

    @Test
    fun `request 403 cloudflare challenge body fails blocked`() = runTest {
        server.enqueue(MockResponse.Builder().code(403).body(cloudflareChallengeBody()).build())
        val client = newClient()

        val result = client.requestDeviceCode()

        assertBlockedFailure(
            result = result,
            diagnosticsDigest = "codex_device_code_blocked",
        )
    }

    @Test
    fun `poll 403 cf-mitigated header without body markers fails blocked`() = runTest {
        // CM-8 命门场景：Cloudflare 质询以 cf-mitigated 响应头为特征、
        // body 不含 cf-chl/__cf_chl/just a moment 三标记——修复前走 Pending 静默轮询。
        server.enqueue(
            MockResponse.Builder()
                .code(403)
                .addHeader("cf-mitigated", "challenge")
                .body("synthetic empty challenge body")
                .build(),
        )
        val client = newClient()

        val result = client.pollAuthorization(sampleChallenge())

        assertBlockedFailure(
            result = result,
            diagnosticsDigest = "codex_device_authorization_blocked",
        )
    }

    @Test
    fun `poll 403 cf-mitigated header any value fails blocked`() = runTest {
        server.enqueue(
            MockResponse.Builder()
                .code(403)
                .addHeader("cf-mitigated", "interactive")
                .body("synthetic empty challenge body")
                .build(),
        )
        val client = newClient()

        val result = client.pollAuthorization(sampleChallenge())

        assertBlockedFailure(
            result = result,
            diagnosticsDigest = "codex_device_authorization_blocked",
        )
    }

    @Test
    fun `poll plain 403 without cf signals stays pending`() = runTest {
        // 普通 403（无 cf-mitigated 头、body 无 cf 标记）钉死的显式语义：
        // 服务端在部分部署上用 403 表达 authorization_pending——保持 Pending，
        // 但仅在 body 是 pending 形 JSON 或可判定的服务端 pending 文本时。
        server.enqueue(
            MockResponse.Builder()
                .code(403)
                .body("""{"error":"authorization_pending"}""")
                .build(),
        )
        val client = newClient()

        val result = client.pollAuthorization(sampleChallenge())

        assertTrue(result is CodexDeviceCodeClient.Result.Pending)
    }

    @Test
    fun `request 403 cf-mitigated header fails blocked`() = runTest {
        server.enqueue(
            MockResponse.Builder()
                .code(403)
                .addHeader("cf-mitigated", "challenge")
                .body("synthetic empty challenge body")
                .build(),
        )
        val client = newClient()

        val result = client.requestDeviceCode()

        assertBlockedFailure(
            result = result,
            diagnosticsDigest = "codex_device_code_blocked",
        )
    }

    @Test
    fun `poll 503 cf-mitigated header fails blocked`() = runTest {
        // cf-mitigated 质询也可能以 503 承载（挑战平台惯例）——
        // header 检测与状态码无关，任何 cf 质询都必须终结。
        server.enqueue(
            MockResponse.Builder()
                .code(503)
                .addHeader("cf-mitigated", "challenge")
                .body("synthetic empty challenge body")
                .build(),
        )
        val client = newClient()

        val result = client.pollAuthorization(sampleChallenge())

        assertBlockedFailure(
            result = result,
            diagnosticsDigest = "codex_device_authorization_blocked",
        )
    }

    private fun newClient(): CodexDeviceCodeClient =
        CodexDeviceCodeClient(
            httpClient = ProviderHttpClient(),
            json = json,
            userCodeEndpointUrl = server.url("/api/accounts/deviceauth/usercode").toString(),
            tokenEndpointUrl = server.url("/api/accounts/deviceauth/token").toString(),
            clock = Clock.systemUTC(),
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

    private fun assertBlockedFailure(
        result: CodexDeviceCodeClient.Result<*>,
        diagnosticsDigest: String,
    ) {
        assertTrue(result is CodexDeviceCodeClient.Result.Failure)
        val failure = result as CodexDeviceCodeClient.Result.Failure
        assertTrue(failure.error is QuotaError.Network)
        assertEquals(diagnosticsDigest, failure.error.diagnosticsDigest)
        assertEquals("error_login_blocked", failure.error.safeMessageKey)
        // Challenge page HTML must never surface in diagnostics or error rendering.
        CHALLENGE_FORBIDDEN_TEXTS.forEach { forbidden ->
            assertFalse(failure.toString().contains(forbidden))
            assertFalse(failure.error.diagnosticsDigest.orEmpty().contains(forbidden))
        }
    }

    private fun cloudflareChallengeBody(): String =
        """
        <!DOCTYPE html>
        <html>
          <head>
            <title>Just a moment...</title>
          </head>
          <body>
            <div class="main-wrapper">synthetic-challenge-page</div>
            <script>
              window.__cf_chl_opt = { cRay: "synthetic-ray-id" };
              var s = document.createElement('script');
              s.src = '/cdn-cgi/challenge-platform/h/g/orchestrate/chl_page_v1?ray=cf-chl-synthetic';
              document.body.appendChild(s);
            </script>
          </body>
        </html>
        """.trimIndent()

    private companion object {
        const val SAMPLE_DEVICE_AUTH_ID = "synthetic-device-auth-id"
        val CHALLENGE_FORBIDDEN_TEXTS = listOf("Just a moment", "cf-chl", "__cf_chl", "synthetic-challenge-page")
    }
}
