package com.kmnexus.codexmeter.providers.kimicode

import com.kmnexus.codexmeter.data.secure.EncryptedPayload
import com.kmnexus.codexmeter.data.secure.FakeSecureSessionStore
import com.kmnexus.codexmeter.data.secure.PayloadCipher
import com.kmnexus.codexmeter.data.secure.ProviderSessionEnvelope
import com.kmnexus.codexmeter.domain.model.LocalAccountId
import com.kmnexus.codexmeter.domain.model.ProviderAccount
import com.kmnexus.codexmeter.domain.model.ProviderAccountId
import com.kmnexus.codexmeter.domain.model.ProviderId
import com.kmnexus.codexmeter.domain.quota.QuotaSnapshotSource
import com.kmnexus.codexmeter.domain.refresh.RefreshTrigger
import com.kmnexus.codexmeter.core.network.ProviderHttpClient
import com.kmnexus.codexmeter.providers.kimicode.network.KimiCodeUsageClient
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class KimiCodeRefreshProviderTest {
    private val server = MockWebServer()
    private val now: Instant = Instant.parse("2026-10-08T00:00:00Z")
    private val clock: Clock = Clock.fixed(now, ZoneOffset.UTC)
    private val sessionStore = FakeSecureSessionStore()

    @Before fun setUp() = server.start()
    @After fun tearDown() = server.close()

    /** Test-only cipher: identity round-trip, so fixtures can read the stored payload. */
    private class PassthroughPayloadCipher : PayloadCipher {
        override fun encrypt(plaintext: ByteArray) = EncryptedPayload(plaintext.copyOf(), ByteArray(0))
        override fun decrypt(ciphertext: ByteArray, nonce: ByteArray) = ciphertext.copyOf()
    }

    private fun account() = ProviderAccount.createNew(
        localAccountId = LocalAccountId("local-1"),
        providerId = ProviderId("kimi_code"),
        providerAccountId = ProviderAccountId("acct-1"),
        displayName = "Kimi Code API",
        now = now,
    )

    private fun refreshProvider() = KimiCodeRefreshProvider(
        client = KimiCodeUsageClient(ProviderHttpClient()),
        sessionStore = sessionStore,
        payloadCipher = PassthroughPayloadCipher(),
        clock = clock,
    )

    private suspend fun storePayload(payloadJson: String) {
        val encrypted = PassthroughPayloadCipher().encrypt(payloadJson.encodeToByteArray())
        sessionStore.save(
            ProviderSessionEnvelope(
                providerId = "kimi_code",
                localAccountId = "local-1",
                providerAccountId = "acct-1",
                schemaVersion = 1,
                payloadCiphertext = encrypted.ciphertext,
                payloadNonce = encrypted.nonce,
                createdAt = now.toString(),
                updatedAt = now.toString(),
            ),
        )
    }

    @Test
    fun missingSessionFailsWithAuthRequired() = runTest {
        val result = refreshProvider().refresh(account(), RefreshTrigger.Manual)

        val error = (result as com.kmnexus.codexmeter.refresh.ProviderRefreshResult.Failure).error
        assertTrue(error is com.kmnexus.codexmeter.domain.refresh.QuotaError.AuthRequired)
        assertEquals("kimi_code_session_missing", error.diagnosticsDigest)
    }

    @Test
    fun undecryptableSessionFailsWithDecodeFailed() = runTest {
        storePayload("not-a-session-payload")

        val result = refreshProvider().refresh(account(), RefreshTrigger.Manual)

        val error = (result as com.kmnexus.codexmeter.refresh.ProviderRefreshResult.Failure).error
        assertTrue(error is com.kmnexus.codexmeter.domain.refresh.QuotaError.AuthRequired)
        assertEquals("kimi_code_session_decode_failed", error.diagnosticsDigest)
    }

    @Test
    fun successfulRefreshMapsTriggerToSnapshotSource() = runTest {
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .body("""{"usages":{"limit_5h":{"used_ratio":0.25,"reset_time":"2026-09-16T20:15:44Z"}}}""")
                .build(),
        )
        storePayload("""{"apiKey":"test-key","apiBaseUrl":"${server.url("/")}"}""")

        val result = refreshProvider().refresh(account(), RefreshTrigger.Manual)

        assertTrue(result is com.kmnexus.codexmeter.refresh.ProviderRefreshResult.Success)
        val snapshot = (result as com.kmnexus.codexmeter.refresh.ProviderRefreshResult.Success).snapshot
        assertEquals("kimi_code", snapshot.providerId.value)
        assertEquals(QuotaSnapshotSource.ManualRefresh, snapshot.source)
        assertEquals("kimi_code_${now}", snapshot.snapshotId.value)
        assertEquals(1, snapshot.windows.size)
        val request = server.takeRequest()
        assertEquals("Bearer test-key", request.headers["Authorization"])
        assertEquals("/coding/v1/usages", request.url.encodedPath)
    }

    @Test
    fun importValidationTriggerMapsToApiKeyImportSource() = runTest {
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .body("""{"usages":{"limit_5h":{"used_ratio":0.25,"reset_time":"2026-09-16T20:15:44Z"}}}""")
                .build(),
        )
        storePayload("""{"apiKey":"test-key","apiBaseUrl":"${server.url("/")}"}""")

        val result = refreshProvider().refresh(account(), RefreshTrigger.ImportValidation)

        val snapshot = (result as com.kmnexus.codexmeter.refresh.ProviderRefreshResult.Success).snapshot
        assertEquals(QuotaSnapshotSource.ApiKeyImport, snapshot.source)
    }
}

class KimiCodeSessionImporterTest {
    private val server = MockWebServer()
    private val now: Instant = Instant.parse("2026-10-08T00:00:00Z")
    private val clock: Clock = Clock.fixed(now, ZoneOffset.UTC)
    private val sessionStore = FakeSecureSessionStore()

    @Before fun setUp() = server.start()
    @After fun tearDown() = server.close()

    private class PassthroughPayloadCipher : PayloadCipher {
        override fun encrypt(plaintext: ByteArray) = EncryptedPayload(plaintext.copyOf(), ByteArray(0))
        override fun decrypt(ciphertext: ByteArray, nonce: ByteArray) = ciphertext.copyOf()
    }

    private fun account() = ProviderAccount.createNew(
        localAccountId = LocalAccountId("local-1"),
        providerId = ProviderId("kimi_code"),
        providerAccountId = ProviderAccountId("acct-1"),
        displayName = "Kimi Code API",
        now = now,
    )

    private fun importer() = com.kmnexus.codexmeter.providers.kimicode.auth.KimiCodeSessionImporter(
        usageClient = KimiCodeUsageClient(ProviderHttpClient()),
        sessionStore = sessionStore,
        payloadCipher = PassthroughPayloadCipher(),
        clock = clock,
    )

    private fun okEnqueue() {
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .body("""{"usages":{"limit_5h":{"used_ratio":0.25,"reset_time":"2026-09-16T20:15:44Z"}}}""")
                .build(),
        )
    }

    @Test
    fun failedValidationDoesNotPersistSession() = runTest {
        server.enqueue(MockResponse.Builder().code(401).body("").build())

        val result = importer().importFromApiKey("test-key", account(), apiBaseUrl = null)

        assertTrue(result.isFailure)
        assertEquals(null, sessionStore.load("kimi_code", "local-1"))
    }

    @Test
    fun successfulImportPersistsEnvelopeWithChosenBaseUrl() = runTest {
        okEnqueue()
        val chosenBase = server.url("/").toString()

        val result = importer().importFromApiKey("test-key", account(), apiBaseUrl = chosenBase)

        assertTrue(result.isSuccess)
        val envelope = sessionStore.load("kimi_code", "local-1")
        assertNotNull(envelope)
        assertEquals("kimi_code", envelope!!.providerId)
        val payload = Json.parseToJsonElement(
            PassthroughPayloadCipher().decrypt(envelope.payloadCiphertext, envelope.payloadNonce).decodeToString(),
        ).jsonObject
        assertEquals("test-key", payload["apiKey"]!!.jsonPrimitive.content)
        assertEquals(chosenBase, payload["apiBaseUrl"]!!.jsonPrimitive.content)
        val snapshot = result.getOrThrow()
        assertEquals("kimi_code", snapshot.providerId.value)
        assertEquals(QuotaSnapshotSource.ApiKeyImport, snapshot.source)
    }

    /**
     * A null imported base URL resolves to the default inside the client — a real fetch against it
     * cannot be intercepted by MockWebServer, so the constant itself is what the test pins down.
     */
    @Test
    fun defaultBaseUrlIsTheChinaStation() {
        assertEquals(
            "https://api.kimi.com",
            com.kmnexus.codexmeter.providers.kimicode.network.KimiCodeUsageClient.DEFAULT_BASE_URL,
        )
    }

    @Test
    fun cookieAndOAuthImportsAreUnsupported() = runTest {
        assertTrue(importer().importFromCookie("{}", account()).isFailure)
        assertTrue(
            importer().importFromOAuthPkce("code", "verifier", "redirect", account()).isFailure,
        )
        assertEquals(null, sessionStore.load("kimi_code", "local-1"))
    }
}
