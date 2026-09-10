package com.kmnexus.codexmeter.providers.grok

import com.kmnexus.codexmeter.data.secure.ProviderSessionEnvelope
import com.kmnexus.codexmeter.data.secure.SecureSessionStore
import com.kmnexus.codexmeter.domain.model.LocalAccountId
import com.kmnexus.codexmeter.domain.model.ProviderAccount
import com.kmnexus.codexmeter.domain.model.ProviderAccountId
import com.kmnexus.codexmeter.domain.model.ProviderId
import com.kmnexus.codexmeter.domain.quota.QuotaSnapshotSource
import com.kmnexus.codexmeter.domain.refresh.QuotaError
import com.kmnexus.codexmeter.domain.refresh.RefreshTrigger
import com.kmnexus.codexmeter.providers.grok.auth.GrokTokenRefresher
import com.kmnexus.codexmeter.providers.grok.dto.GrokBillingPeriodDto
import com.kmnexus.codexmeter.providers.grok.dto.GrokBillingResponseDto
import com.kmnexus.codexmeter.providers.grok.network.GrokBillingClient
import com.kmnexus.codexmeter.providers.grok.session.GrokSessionPayload
import com.kmnexus.codexmeter.refresh.ProviderRefreshResult
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GrokRefreshProviderTest {
    private val now: Instant = Instant.parse("2026-09-10T12:00:00Z")
    private val clock: Clock = Clock.fixed(now, ZoneOffset.UTC)

    @Test
    fun `unexpired access token fetches billing without calling the token endpoint`() = runTest {
        val tokenRefresh = RecordingTokenRefresh(refreshedSession())
        val billingFetcher = RecordingBillingFetcher(successfulBillingDto())
        val provider = provider(
            store = RecordingSecureSessionStore(envelope(localAccountId = "local-1")),
            cipher = RecordingGrokSessionCipher(
                decryptedSession = storedSession(
                    tokenExpiresAtEpochSeconds = now.plusSeconds(1_800).epochSecond,
                ),
            ),
            tokenRefresh = tokenRefresh,
            billingFetcher = billingFetcher,
        )

        val result = provider.refresh(account(), RefreshTrigger.Periodic)

        assertTrue(result is ProviderRefreshResult.Success)
        val snapshot = (result as ProviderRefreshResult.Success).snapshot
        assertEquals(QuotaSnapshotSource.BackgroundRefresh, snapshot.source)
        assertEquals("weekly", snapshot.windows.single().windowId.value)
        assertEquals(1, snapshot.windows.single().usedPercent)
        assertEquals(Instant.parse("2026-09-14T00:00:00Z"), snapshot.windows.single().resetAt)
        assertEquals(2.5, snapshot.credits?.balance)
        assertTrue(tokenRefresh.refreshTokens.isEmpty())
        assertEquals(listOf("old-access"), billingFetcher.accessTokens)
    }

    @Test
    fun `access token inside the expiry skew is refreshed before fetching billing`() = runTest {
        val tokenRefresh = RecordingTokenRefresh(refreshedSession())
        val billingFetcher = RecordingBillingFetcher(successfulBillingDto())
        val store = RecordingSecureSessionStore(envelope(localAccountId = "local-1"))
        val cipher = RecordingGrokSessionCipher(
            decryptedSession = storedSession(
                tokenExpiresAtEpochSeconds = now.plusSeconds(30).epochSecond,
            ),
        )
        val provider = provider(
            store = store,
            cipher = cipher,
            tokenRefresh = tokenRefresh,
            billingFetcher = billingFetcher,
        )

        val result = provider.refresh(account(), RefreshTrigger.Periodic)

        assertTrue(result is ProviderRefreshResult.Success)
        assertEquals(listOf("old-refresh"), tokenRefresh.refreshTokens)
        assertEquals(listOf("new-access"), billingFetcher.accessTokens)
        // The rotated refresh token must reach disk before the response is trusted.
        assertEquals(listOf("new-refresh"), cipher.encryptedRefreshTokens)
        assertEquals("local-1", store.savedEnvelopes.single().localAccountId)
    }

    @Test
    fun `terminal refresh failure returns auth required and keeps the stored envelope`() = runTest {
        val store = RecordingSecureSessionStore(envelope(localAccountId = "local-1"))
        val tokenRefresh = FailingTokenRefresh(
            QuotaError.AuthRequired(
                httpStatus = 400,
                diagnosticsDigest = "grok_refresh_auth_required_invalid_grant",
            ),
        )
        val billingFetcher = RecordingBillingFetcher(successfulBillingDto())
        val provider = provider(
            store = store,
            cipher = RecordingGrokSessionCipher(
                decryptedSession = storedSession(
                    tokenExpiresAtEpochSeconds = now.plusSeconds(30).epochSecond,
                ),
            ),
            tokenRefresh = tokenRefresh,
            billingFetcher = billingFetcher,
        )

        val result = provider.refresh(account(), RefreshTrigger.Periodic)

        assertTrue(result is ProviderRefreshResult.Failure)
        val failure = result as ProviderRefreshResult.Failure
        assertTrue(failure.error is QuotaError.AuthRequired)
        assertTrue(billingFetcher.accessTokens.isEmpty())
        // Last-known-good protection: nothing is written back over the stored envelope.
        assertTrue(store.savedEnvelopes.isEmpty())
    }

    @Test
    fun `billing auth failure on an unexpired token refreshes once and retries`() = runTest {
        val tokenRefresh = RecordingTokenRefresh(refreshedSession())
        val billingFetcher = SequencedBillingFetcher(
            listOf(
                GrokBillingClient.Result.Failure(
                    QuotaError.AuthRequired(
                        httpStatus = 401,
                        diagnosticsDigest = "grok_billing_auth_required_401",
                    ),
                ),
                GrokBillingClient.Result.Success(successfulBillingDto()),
            ),
        )
        val provider = provider(
            store = RecordingSecureSessionStore(envelope(localAccountId = "local-1")),
            cipher = RecordingGrokSessionCipher(
                decryptedSession = storedSession(
                    tokenExpiresAtEpochSeconds = now.plusSeconds(1_800).epochSecond,
                ),
            ),
            tokenRefresh = tokenRefresh,
            billingFetcher = billingFetcher,
        )

        val result = provider.refresh(account(), RefreshTrigger.Periodic)

        assertTrue(result is ProviderRefreshResult.Success)
        assertEquals(listOf("old-refresh"), tokenRefresh.refreshTokens)
        assertEquals(listOf("old-access", "new-access"), billingFetcher.accessTokens)
    }

    @Test
    fun `billing auth failure after a just-refreshed token is not retried again`() = runTest {
        val tokenRefresh = RecordingTokenRefresh(refreshedSession())
        val billingFetcher = FailingBillingFetcher(
            QuotaError.AuthRequired(
                httpStatus = 401,
                diagnosticsDigest = "grok_billing_auth_required_401",
            ),
        )
        val provider = provider(
            store = RecordingSecureSessionStore(envelope(localAccountId = "local-1")),
            cipher = RecordingGrokSessionCipher(
                // Unknown expiry forces a refresh before the first billing call.
                decryptedSession = storedSession(tokenExpiresAtEpochSeconds = null),
            ),
            tokenRefresh = tokenRefresh,
            billingFetcher = billingFetcher,
        )

        val result = provider.refresh(account(), RefreshTrigger.Periodic)

        assertTrue(result is ProviderRefreshResult.Failure)
        val failure = result as ProviderRefreshResult.Failure
        assertTrue(failure.error is QuotaError.AuthRequired)
        assertEquals("grok_billing_auth_required_401", failure.error.diagnosticsDigest)
        assertEquals(listOf("old-refresh"), tokenRefresh.refreshTokens)
        assertEquals(listOf("new-access"), billingFetcher.accessTokens)
    }

    @Test
    fun `billing auth failure that persists after refresh returns auth required`() = runTest {
        val tokenRefresh = RecordingTokenRefresh(refreshedSession())
        val billingFetcher = SequencedBillingFetcher(
            listOf(
                GrokBillingClient.Result.Failure(
                    QuotaError.AuthRequired(
                        httpStatus = 401,
                        diagnosticsDigest = "grok_billing_auth_required_401",
                    ),
                ),
                GrokBillingClient.Result.Failure(
                    QuotaError.AuthRequired(
                        httpStatus = 401,
                        diagnosticsDigest = "grok_billing_auth_required_401",
                    ),
                ),
            ),
        )
        val provider = provider(
            store = RecordingSecureSessionStore(envelope(localAccountId = "local-1")),
            cipher = RecordingGrokSessionCipher(
                decryptedSession = storedSession(
                    tokenExpiresAtEpochSeconds = now.plusSeconds(1_800).epochSecond,
                ),
            ),
            tokenRefresh = tokenRefresh,
            billingFetcher = billingFetcher,
        )

        val result = provider.refresh(account(), RefreshTrigger.Periodic)

        assertTrue(result is ProviderRefreshResult.Failure)
        val failure = result as ProviderRefreshResult.Failure
        assertTrue(failure.error is QuotaError.AuthRequired)
        assertEquals(listOf("old-access", "new-access"), billingFetcher.accessTokens)
    }

    @Test
    fun `missing session envelope returns auth required without network calls`() = runTest {
        val tokenRefresh = RecordingTokenRefresh(refreshedSession())
        val billingFetcher = RecordingBillingFetcher(successfulBillingDto())
        val provider = provider(
            store = RecordingSecureSessionStore(initialEnvelope = null),
            cipher = RecordingGrokSessionCipher(decryptedSession = storedSession(null)),
            tokenRefresh = tokenRefresh,
            billingFetcher = billingFetcher,
        )

        val result = provider.refresh(account(), RefreshTrigger.Periodic)

        assertTrue(result is ProviderRefreshResult.Failure)
        val failure = result as ProviderRefreshResult.Failure
        assertTrue(failure.error is QuotaError.AuthRequired)
        assertEquals("grok_session_missing", failure.error.diagnosticsDigest)
        assertTrue(tokenRefresh.refreshTokens.isEmpty())
        assertTrue(billingFetcher.accessTokens.isEmpty())
    }

    @Test
    fun `undecryptable session returns auth required without network calls`() = runTest {
        val tokenRefresh = RecordingTokenRefresh(refreshedSession())
        val billingFetcher = RecordingBillingFetcher(successfulBillingDto())
        val provider = provider(
            store = RecordingSecureSessionStore(envelope(localAccountId = "local-1")),
            cipher = UndecryptableGrokSessionCipher,
            tokenRefresh = tokenRefresh,
            billingFetcher = billingFetcher,
        )

        val result = provider.refresh(account(), RefreshTrigger.Periodic)

        assertTrue(result is ProviderRefreshResult.Failure)
        val failure = result as ProviderRefreshResult.Failure
        assertTrue(failure.error is QuotaError.AuthRequired)
        assertEquals("grok_session_decode_failed", failure.error.diagnosticsDigest)
        assertTrue(tokenRefresh.refreshTokens.isEmpty())
        assertTrue(billingFetcher.accessTokens.isEmpty())
    }

    @Test
    fun `rotation write-back failure returns structured retryable failure`() = runTest {
        val provider = provider(
            store = ThrowingSaveSecureSessionStore(envelope(localAccountId = "local-1")),
            cipher = RecordingGrokSessionCipher(
                decryptedSession = storedSession(tokenExpiresAtEpochSeconds = null),
            ),
            tokenRefresh = RecordingTokenRefresh(refreshedSession()),
            billingFetcher = RecordingBillingFetcher(successfulBillingDto()),
        )

        val result = provider.refresh(account(), RefreshTrigger.Periodic)

        assertTrue(result is ProviderRefreshResult.Failure)
        val failure = result as ProviderRefreshResult.Failure
        assertEquals("error_network", failure.error.safeMessageKey)
        assertEquals("grok_refresh_session_save_failed", failure.error.diagnosticsDigest)
    }

    private fun storedSession(tokenExpiresAtEpochSeconds: Long?): GrokSessionPayload =
        GrokSessionPayload(
            accessToken = "old-access",
            refreshToken = "old-refresh",
            idToken = "old-id",
            tokenExpiresAtEpochSeconds = tokenExpiresAtEpochSeconds,
            tokenEndpoint = "https://auth.x.ai/oauth2/token",
            accountId = "acct-1",
            email = "grok@example.test",
        )

    private fun refreshedSession(): GrokSessionPayload =
        GrokSessionPayload(
            accessToken = "new-access",
            refreshToken = "new-refresh",
            idToken = "new-id",
            tokenExpiresAtEpochSeconds = now.plusSeconds(3_600).epochSecond,
            tokenEndpoint = "https://auth.x.ai/oauth2/token",
            accountId = "acct-1",
            email = "grok@example.test",
        )

    private fun successfulBillingDto(): GrokBillingResponseDto =
        GrokBillingResponseDto(
            currentPeriod = GrokBillingPeriodDto(
                type = "USAGE_PERIOD_TYPE_WEEKLY",
                start = "2026-09-07T00:00:00Z",
                end = "2026-09-14T00:00:00Z",
            ),
            creditUsagePercent = 1.0,
            prepaidBalance = 2.5,
        )

    private fun provider(
        store: SecureSessionStore,
        cipher: GrokSessionCipher,
        tokenRefresh: GrokTokenRefresh,
        billingFetcher: GrokBillingFetcher,
    ): GrokRefreshProvider =
        GrokRefreshProvider(
            sessionStore = store,
            sessionCipher = cipher,
            tokenRefresh = tokenRefresh,
            billingFetcher = billingFetcher,
            clock = clock,
        )

    private class RecordingSecureSessionStore(
        initialEnvelope: ProviderSessionEnvelope?,
    ) : SecureSessionStore {
        private val envelope = initialEnvelope
        val savedEnvelopes = mutableListOf<ProviderSessionEnvelope>()

        override suspend fun save(envelope: ProviderSessionEnvelope) {
            savedEnvelopes += envelope
        }

        override suspend fun load(providerId: String, localAccountId: String): ProviderSessionEnvelope? = envelope

        override suspend fun delete(providerId: String, localAccountId: String) = Unit
    }

    private class ThrowingSaveSecureSessionStore(
        private val envelope: ProviderSessionEnvelope,
    ) : SecureSessionStore {
        override suspend fun save(envelope: ProviderSessionEnvelope) {
            throw IllegalStateException("disk unavailable")
        }

        override suspend fun load(providerId: String, localAccountId: String): ProviderSessionEnvelope = envelope

        override suspend fun delete(providerId: String, localAccountId: String) = Unit
    }

    private class RecordingGrokSessionCipher(
        private val decryptedSession: GrokSessionPayload,
    ) : GrokSessionCipher {
        val encryptedRefreshTokens = mutableListOf<String>()

        override fun decrypt(envelope: ProviderSessionEnvelope): Result<GrokSessionPayload> =
            Result.success(decryptedSession)

        override fun encrypt(
            session: GrokSessionPayload,
            envelope: ProviderSessionEnvelope,
            updatedAt: Instant,
        ): ProviderSessionEnvelope {
            encryptedRefreshTokens += session.refreshToken
            return envelope.copy(
                payloadCiphertext = session.refreshToken.toByteArray(),
                updatedAt = updatedAt.toString(),
            )
        }
    }

    private object UndecryptableGrokSessionCipher : GrokSessionCipher {
        override fun decrypt(envelope: ProviderSessionEnvelope): Result<GrokSessionPayload> =
            Result.failure(IllegalStateException("Unable to decrypt Grok session."))

        override fun encrypt(
            session: GrokSessionPayload,
            envelope: ProviderSessionEnvelope,
            updatedAt: Instant,
        ): ProviderSessionEnvelope = envelope
    }

    private class FailingTokenRefresh(
        private val error: QuotaError,
    ) : GrokTokenRefresh {
        val refreshTokens = mutableListOf<String>()

        override suspend fun refresh(session: GrokSessionPayload): GrokTokenRefresher.Result {
            refreshTokens += session.refreshToken
            return GrokTokenRefresher.Result.Failure(error)
        }
    }

    private class FailingBillingFetcher(
        private val error: QuotaError,
    ) : GrokBillingFetcher {
        val accessTokens = mutableListOf<String>()

        override suspend fun fetchBilling(accessToken: String): GrokBillingClient.Result {
            accessTokens += accessToken
            return GrokBillingClient.Result.Failure(error)
        }
    }

    private class RecordingTokenRefresh(
        private val refreshedSession: GrokSessionPayload,
    ) : GrokTokenRefresh {
        val refreshTokens = mutableListOf<String>()

        override suspend fun refresh(session: GrokSessionPayload): GrokTokenRefresher.Result {
            refreshTokens += session.refreshToken
            return GrokTokenRefresher.Result.Success(refreshedSession)
        }
    }

    private class RecordingBillingFetcher(
        private val dto: GrokBillingResponseDto,
    ) : GrokBillingFetcher {
        val accessTokens = mutableListOf<String>()

        override suspend fun fetchBilling(accessToken: String): GrokBillingClient.Result {
            accessTokens += accessToken
            return GrokBillingClient.Result.Success(dto)
        }
    }

    private class SequencedBillingFetcher(
        results: List<GrokBillingClient.Result>,
    ) : GrokBillingFetcher {
        private val remaining = ArrayDeque(results)
        val accessTokens = mutableListOf<String>()

        override suspend fun fetchBilling(accessToken: String): GrokBillingClient.Result {
            accessTokens += accessToken
            return remaining.removeFirst()
        }
    }

    private fun account(): ProviderAccount =
        ProviderAccount.createNew(
            localAccountId = LocalAccountId("local-1"),
            providerId = ProviderId("grok"),
            providerAccountId = ProviderAccountId("acct-1"),
            displayName = "Grok",
            now = now,
        )

    private fun envelope(localAccountId: String): ProviderSessionEnvelope =
        ProviderSessionEnvelope(
            providerId = "grok",
            localAccountId = localAccountId,
            providerAccountId = "acct-1",
            schemaVersion = 1,
            payloadCiphertext = byteArrayOf(1, 2, 3),
            payloadNonce = byteArrayOf(4, 5, 6),
            createdAt = now.toString(),
            updatedAt = now.toString(),
        )
}
