package com.kmnexus.codexmeter.providers.grok.auth

import com.kmnexus.codexmeter.data.secure.ProviderSessionEnvelope
import com.kmnexus.codexmeter.domain.model.LocalAccountId
import com.kmnexus.codexmeter.domain.model.ProviderAccountId
import com.kmnexus.codexmeter.domain.quota.QuotaSnapshotSource
import com.kmnexus.codexmeter.domain.refresh.QuotaError
import com.kmnexus.codexmeter.providers.grok.dto.GrokBillingPeriodDto
import com.kmnexus.codexmeter.providers.grok.dto.GrokBillingResponseDto
import com.kmnexus.codexmeter.providers.grok.network.GrokBillingClient
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Mirrors CodexSessionImporterTest: the two-phase contract — nothing persists until the official
 * billing endpoint validates the candidate session — is the Task 5 acceptance invariant and must be
 * locked by tests against the real importer, not just through controller fakes.
 */
class GrokSessionImporterTest {
    private val now = Instant.parse("2026-09-10T08:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)

    @Test
    fun `prepare caches the candidate session without any io`() = runTest {
        val billing = RecordingBillingClient(successBilling())
        val persistence = RecordingPersistence()

        val prepared = importer(billing, persistence)
            .prepareDeviceCodeSession(oauthToken(), identity(), tokenEndpoint = "https://auth.x.ai/oauth2/token")

        assertEquals("synthetic-access-token", prepared.session.accessToken)
        assertEquals("acct-123", prepared.session.accountId)
        assertEquals("grok@example.test", prepared.session.email)
        assertEquals(0, billing.calls.size)
        assertEquals(0, persistence.saved.size)
    }

    @Test
    fun `commit validates billing then persists account envelope and snapshot`() = runTest {
        val persistence = RecordingPersistence()
        val importer = importer(RecordingBillingClient(successBilling()), persistence)
        val prepared = importer.prepareDeviceCodeSession(oauthToken(), identity(), "https://auth.x.ai/oauth2/token")

        val result = importer.commitPreparedDeviceCodeSession(prepared)

        assertTrue(result is GrokSessionImporter.Result.Success)
        val success = result as GrokSessionImporter.Result.Success
        assertEquals("grok@example.test", success.account.displayName)
        assertEquals(ProviderAccountId("acct-123"), success.account.providerAccountId)
        assertEquals(QuotaSnapshotSource.DeviceCodeLogin, success.snapshot.source)
        assertEquals(1, persistence.saved.size)
        val committed = persistence.saved.single()
        assertEquals("grok", committed.sessionEnvelope.providerId)
        assertEquals("local-123", committed.sessionEnvelope.localAccountId)
    }

    @Test
    fun `identity without email falls back to default display name`() = runTest {
        val persistence = RecordingPersistence()
        val importer = importer(RecordingBillingClient(successBilling()), persistence)
        val prepared = importer.prepareDeviceCodeSession(
            oauthToken(),
            identity(accountId = "acct-123", email = null),
            "https://auth.x.ai/oauth2/token",
        )

        val result = importer.commitPreparedDeviceCodeSession(prepared)

        assertTrue(result is GrokSessionImporter.Result.Success)
        assertEquals("Grok account", (result as GrokSessionImporter.Result.Success).account.displayName)
    }

    /** The Task 5 core invariant: a billing failure must leave the store untouched. */
    @Test
    fun `billing validation failure persists nothing`() = runTest {
        val persistence = RecordingPersistence()
        val importer = importer(
            RecordingBillingClient(
                GrokBillingClient.Result.Failure(
                    QuotaError.AuthRequired(httpStatus = 401, diagnosticsDigest = "grok_billing_auth_required_401"),
                ),
            ),
            persistence,
        )
        val prepared = importer.prepareDeviceCodeSession(oauthToken(), identity(), null)

        val result = importer.commitPreparedDeviceCodeSession(prepared)

        assertTrue(result is GrokSessionImporter.Result.Failure)
        assertEquals("error_auth_required", (result as GrokSessionImporter.Result.Failure).message)
        assertEquals(0, persistence.saved.size)
    }

    @Test
    fun `billing client throw maps to a safe network failure`() = runTest {
        val persistence = RecordingPersistence()
        val importer = importer(ThrowingBillingClient(), persistence)
        val prepared = importer.prepareDeviceCodeSession(oauthToken(), identity(), null)

        val result = importer.commitPreparedDeviceCodeSession(prepared)

        assertTrue(result is GrokSessionImporter.Result.Failure)
        val failure = result as GrokSessionImporter.Result.Failure
        assertEquals("error_network", failure.message)
        assertEquals("grok_import_billing_validation_failed", failure.quotaError?.diagnosticsDigest)
        assertEquals(0, persistence.saved.size)
    }

    @Test
    fun `persistence failure returns safe persistence error`() = runTest {
        val importer = importer(RecordingBillingClient(successBilling()), ThrowingPersistence())
        val prepared = importer.prepareDeviceCodeSession(oauthToken(), identity(), null)

        val result = importer.commitPreparedDeviceCodeSession(prepared)

        assertEquals(
            GrokSessionImporter.Result.Failure(
                message = "error_session_persistence",
                quotaError = null,
            ),
            result,
        )
    }

    private fun importer(
        billingClient: GrokSessionImporter.BillingClient,
        persistence: GrokSessionImporter.ImportPersistence,
    ): GrokSessionImporter =
        GrokSessionImporter(
            billingClient = billingClient,
            importPersistence = persistence,
            sessionEnvelopeFactory = RecordingEnvelopeFactory(),
            localAccountIdProvider = { LocalAccountId("local-123") },
            defaultDisplayName = "Grok account",
            clock = clock,
        )

    private fun oauthToken() = GrokOAuthToken(
        accessToken = "synthetic-access-token",
        refreshToken = "synthetic-refresh-token",
        idToken = "synthetic-id-token",
        expiresAtEpochSeconds = now.epochSecond + 21_600,
    )

    private fun identity(
        accountId: String? = "acct-123",
        email: String? = "grok@example.test",
    ) = GrokLoginIdentity(accountId = accountId, email = email, name = "Grok Tester")

    private fun successBilling() = GrokBillingClient.Result.Success(
        GrokBillingResponseDto(
            currentPeriod = GrokBillingPeriodDto(
                type = "USAGE_PERIOD_TYPE_WEEKLY",
                start = "2026-09-03T17:33:48Z",
                end = "2026-09-10T17:33:48Z",
            ),
            creditUsagePercent = 23.0,
            prepaidBalance = 12.5,
        ),
    )

    private class RecordingBillingClient(
        private val result: GrokBillingClient.Result,
    ) : GrokSessionImporter.BillingClient {
        val calls = mutableListOf<String>()

        override suspend fun fetchBilling(accessToken: String): GrokBillingClient.Result {
            calls += accessToken
            return result
        }
    }

    private class ThrowingBillingClient : GrokSessionImporter.BillingClient {
        override suspend fun fetchBilling(accessToken: String): GrokBillingClient.Result =
            throw IllegalStateException("unsafe billing detail")
    }

    private class RecordingPersistence : GrokSessionImporter.ImportPersistence {
        val saved = mutableListOf<GrokSessionImporter.CommittedImport>()

        override suspend fun save(
            account: com.kmnexus.codexmeter.domain.model.ProviderAccount,
            sessionEnvelope: ProviderSessionEnvelope,
            snapshot: com.kmnexus.codexmeter.domain.quota.QuotaSnapshot,
        ): GrokSessionImporter.CommittedImport {
            val committed = GrokSessionImporter.CommittedImport(
                account = account,
                sessionEnvelope = sessionEnvelope,
                snapshot = snapshot,
            )
            saved += committed
            return committed
        }
    }

    private class ThrowingPersistence : GrokSessionImporter.ImportPersistence {
        override suspend fun save(
            account: com.kmnexus.codexmeter.domain.model.ProviderAccount,
            sessionEnvelope: ProviderSessionEnvelope,
            snapshot: com.kmnexus.codexmeter.domain.quota.QuotaSnapshot,
        ): GrokSessionImporter.CommittedImport = throw IllegalStateException("unsafe persistence detail")
    }

    private class RecordingEnvelopeFactory : GrokSessionImporter.SessionEnvelopeFactory {
        override fun create(
            payload: com.kmnexus.codexmeter.providers.grok.session.GrokSessionPayload,
            localAccountId: LocalAccountId,
            providerAccountId: ProviderAccountId?,
            now: Instant,
        ): ProviderSessionEnvelope =
            ProviderSessionEnvelope(
                providerId = "grok",
                localAccountId = localAccountId.value,
                providerAccountId = providerAccountId?.value,
                schemaVersion = 1,
                payloadCiphertext = byteArrayOf(1, 2, 3),
                payloadNonce = byteArrayOf(4, 5, 6),
                createdAt = now.toString(),
                updatedAt = now.toString(),
            )
    }
}
