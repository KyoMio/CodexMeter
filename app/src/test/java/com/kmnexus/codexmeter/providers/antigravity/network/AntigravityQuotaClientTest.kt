package com.kmnexus.codexmeter.providers.antigravity.network

import com.kmnexus.codexmeter.core.network.ProviderHttpClient
import com.kmnexus.codexmeter.domain.refresh.QuotaError
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AntigravityQuotaClientTest {
    private val server = MockWebServer()

    @Before
    fun setUp() {
        server.start()
    }

    @After
    fun tearDown() {
        server.close()
    }

    @Test
    fun `sends the resolved project in the summary request body`() = runTest {
        server.enqueue(codeAssistResponse(project = "proj-1"))
        server.enqueue(summaryResponse())

        val result = newClient().fetchQuota("fake-access-token")

        assertTrue(result is AntigravityQuotaClient.Result.Success)
        val success = result as AntigravityQuotaClient.Result.Success
        assertTrue(success.summary != null)

        val loadCodeAssist = server.takeRequest()
        assertEquals("/v1internal:loadCodeAssist", loadCodeAssist.url.encodedPath)
        assertEquals("antigravity/hub/2.9.1 android/arm64", loadCodeAssist.headers["User-Agent"])
        val summary = server.takeRequest()
        assertEquals("/v1internal:retrieveUserQuotaSummary", summary.url.encodedPath)
        assertTrue(summary.body?.utf8().orEmpty().contains("\"project\":\"proj-1\""))
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `resolves the project through onboardUser when loadCodeAssist has none`() = runTest {
        server.enqueue(
            codeAssistResponse(
                project = null,
                allowedTiers = """[{"id":"standard","isDefault":false},{"id":"free","isDefault":true}]""",
            ),
        )
        server.enqueue(onboardResponse(project = "proj-2"))
        server.enqueue(summaryResponse())

        val result = newClient().fetchQuota("fake-access-token")

        assertTrue(result is AntigravityQuotaClient.Result.Success)
        val success = result as AntigravityQuotaClient.Result.Success
        assertTrue(success.summary != null)
        assertEquals(3, server.requestCount)

        assertEquals("/v1internal:loadCodeAssist", server.takeRequest().url.encodedPath)
        val onboard = server.takeRequest()
        assertEquals("/v1internal:onboardUser", onboard.url.encodedPath)
        val onboardBody = onboard.body?.utf8().orEmpty()
        assertTrue(onboardBody.contains("\"tierId\":\"free\""))
        val summary = server.takeRequest()
        assertEquals("/v1internal:retrieveUserQuotaSummary", summary.url.encodedPath)
        assertTrue(summary.body?.utf8().orEmpty().contains("\"project\":\"proj-2\""))
    }

    @Test
    fun `returns the summary and skips fetchAvailableModels when groups carry known quota`() = runTest {
        server.enqueue(codeAssistResponse(project = "proj-1"))
        server.enqueue(summaryResponse())

        val result = newClient().fetchQuota("fake-access-token")

        assertTrue(result is AntigravityQuotaClient.Result.Success)
        val success = result as AntigravityQuotaClient.Result.Success
        assertEquals("Free", success.tier)
        assertTrue(success.summary != null)
        assertTrue(success.models.isEmpty())
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `falls back to fetchAvailableModels and merges retrieveUserQuota for placeholder-full models`() = runTest {
        server.enqueue(codeAssistResponse(project = "proj-1"))
        server.enqueue(MockResponse.Builder().code(404).body("""{"error":"missing"}""").build())
        server.enqueue(allFullAvailableModelsResponse())
        server.enqueue(mergedRetrieveQuotaResponse())

        val result = newClient().fetchQuota("fake-access-token")

        assertTrue(result is AntigravityQuotaClient.Result.Success)
        val success = result as AntigravityQuotaClient.Result.Success
        assertNull(success.summary)
        assertEquals(4, server.requestCount)

        assertEquals("/v1internal:loadCodeAssist", server.takeRequest().url.encodedPath)
        assertEquals("/v1internal:retrieveUserQuotaSummary", server.takeRequest().url.encodedPath)
        assertEquals("/v1internal:fetchAvailableModels", server.takeRequest().url.encodedPath)
        assertEquals("/v1internal:retrieveUserQuota", server.takeRequest().url.encodedPath)

        val byModel = success.models.associateBy { it.modelId }
        assertEquals(setOf("gemini-4-argon", "gemini-2.5-flash", "veo-3"), byModel.keys)
        // Both endpoints know argon: bucket fraction/resetTime win, the fetchAvailableModels label stays.
        val argon = byModel.getValue("gemini-4-argon")
        assertEquals("Gemini 4 Argon", argon.label)
        assertEquals(0.42, argon.remainingFraction!!, 0.0)
        assertEquals("2026-06-01T12:00:00Z", argon.resetTime)
        // Known only to retrieveUserQuota: appended with the raw model id as its label.
        val veo = byModel.getValue("veo-3")
        assertEquals("veo-3", veo.label)
        assertEquals(0.8, veo.remainingFraction!!, 0.0)
    }

    @Test
    fun `skips verification when fetchAvailableModels reports a non-full fraction`() = runTest {
        server.enqueue(codeAssistResponse(project = "proj-1"))
        server.enqueue(summaryResponse(allDisabledGroups = true))
        server.enqueue(partialAvailableModelsResponse())

        val result = newClient().fetchQuota("fake-access-token")

        assertTrue(result is AntigravityQuotaClient.Result.Success)
        val success = result as AntigravityQuotaClient.Result.Success
        assertNull(success.summary)
        assertEquals("Gemini 4 Argon", success.models.single { it.modelId == "gemini-4-argon" }.label)
        assertEquals(0.5, success.models.single { it.modelId == "gemini-4-argon" }.remainingFraction!!, 0.0)
        assertEquals(3, server.requestCount)
    }

    @Test
    fun `returns an empty model list when the verification quota reports no fractions`() = runTest {
        server.enqueue(codeAssistResponse(project = "proj-1"))
        server.enqueue(MockResponse.Builder().code(404).body("""{"error":"missing"}""").build())
        server.enqueue(allFullAvailableModelsResponse())
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .body("""{"buckets":[{"modelId":"gemini-2.5-flash","resetTime":"2026-06-01T12:00:00Z"}]}""")
                .build(),
        )

        val result = newClient().fetchQuota("fake-access-token")

        assertTrue(result is AntigravityQuotaClient.Result.Success)
        val success = result as AntigravityQuotaClient.Result.Success
        assertNull(success.summary)
        assertTrue(success.models.isEmpty())
        assertEquals(4, server.requestCount)
    }

    @Test
    fun `maps an unauthorized summary to auth required without falling back`() = runTest {
        server.enqueue(codeAssistResponse(project = "proj-1"))
        server.enqueue(MockResponse.Builder().code(401).body("""{"error":"unauthorized"}""").build())

        val result = newClient().fetchQuota("fake-access-token")

        assertTrue(result is AntigravityQuotaClient.Result.Failure)
        val error = (result as AntigravityQuotaClient.Result.Failure).error
        assertTrue(error is QuotaError.AuthRequired)
        assertEquals(401, error.httpStatus)
        assertEquals("antigravity_auth_required_401", error.diagnosticsDigest)
        assertEquals(2, server.requestCount)
    }

    private fun newClient(): AntigravityQuotaClient {
        val rootUrl = server.url("/").toString().trimEnd('/')
        return AntigravityQuotaClient(
            httpClient = ProviderHttpClient(),
            baseUrl = rootUrl,
            summaryBaseUrl = rootUrl,
        )
    }

    private fun codeAssistResponse(project: String?, allowedTiers: String = "[]"): MockResponse {
        val projectField = project?.let { "\"cloudaicompanionProject\":{\"value\":\"$it\"}," } ?: ""
        return MockResponse.Builder()
            .code(200)
            .body(
                """{"currentTier":{"id":"free","name":"Free"},"paidTier":{"id":"paid"},""" +
                    """"allowedTiers":$allowedTiers,${projectField}"planInfo":{"planType":"PRO"}}""",
            )
            .build()
    }

    private fun onboardResponse(project: String): MockResponse =
        MockResponse.Builder()
            .code(200)
            .body("""{"response":{"cloudaicompanionProject":{"value":"$project"}}}""")
            .build()

    private fun summaryResponse(allDisabledGroups: Boolean = false): MockResponse {
        val disabledField = if (allDisabledGroups) ""","disabled":true""" else ""
        return MockResponse.Builder()
            .code(200)
            .body(
                """{"groups":[{"displayName":"Gemini","buckets":[""" +
                    """{"bucketId":"5h","displayName":"Gemini Pro","remainingFraction":0.5$disabledField}]}]}""",
            )
            .build()
    }

    private fun allFullAvailableModelsResponse(): MockResponse =
        MockResponse.Builder()
            .code(200)
            .body(
                """{"models":{""" +
                    """"gemini-4-argon":{"displayName":"Gemini 4 Argon","quotaInfo":{"remainingFraction":1.0,""" +
                    """"resetTime":"2026-06-02T00:00:00Z"}},""" +
                    """"gemini-2.5-flash":{"quotaInfo":{"remainingFraction":1.0}}}}""",
            )
            .build()

    private fun partialAvailableModelsResponse(): MockResponse =
        MockResponse.Builder()
            .code(200)
            .body(
                """{"models":{""" +
                    """"gemini-4-argon":{"displayName":"Gemini 4 Argon","quotaInfo":{"remainingFraction":0.5}}}}""",
            )
            .build()

    private fun mergedRetrieveQuotaResponse(): MockResponse =
        MockResponse.Builder()
            .code(200)
            .body(
                """{"buckets":[""" +
                    """{"modelId":"gemini-4-argon","remainingFraction":0.42,"resetTime":"2026-06-01T12:00:00Z"},""" +
                    """{"modelId":"veo-3","remainingFraction":0.8,"resetTime":"2026-06-03T00:00:00Z"}]}""",
            )
            .build()
}
