package com.kmnexus.codexmeter.providers.codex

import com.kmnexus.codexmeter.data.secure.ProviderSessionEnvelope
import com.kmnexus.codexmeter.data.secure.SecureSessionStore
import com.kmnexus.codexmeter.domain.model.LocalAccountId
import com.kmnexus.codexmeter.domain.model.ProviderAccount
import com.kmnexus.codexmeter.domain.model.ProviderAccountId
import com.kmnexus.codexmeter.domain.model.ProviderId
import com.kmnexus.codexmeter.domain.quota.QuotaSnapshotSource
import com.kmnexus.codexmeter.domain.refresh.RefreshTrigger
import com.kmnexus.codexmeter.providers.codex.auth.CodexTokenRefresher
import com.kmnexus.codexmeter.providers.codex.dto.CodexRateLimitDto
import com.kmnexus.codexmeter.providers.codex.dto.CodexUsageResponseDto
import com.kmnexus.codexmeter.providers.codex.dto.CodexWindowDto
import com.kmnexus.codexmeter.providers.codex.network.CodexUsageClient
import com.kmnexus.codexmeter.providers.codex.session.CodexSessionPayload
import com.kmnexus.codexmeter.refresh.ProviderRefreshResult
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CodexRefreshProviderTest {
    private val now: Instant = Instant.parse("2026-05-23T12:00:00Z")
    private val clock: Clock = Clock.fixed(now, ZoneOffset.UTC)

    @Test
    fun `refresh loads stored session refreshes token fetches usage and saves updated session`() = runTest {
        val account = account()
        val initialEnvelope = envelope(localAccountId = "local-1")
        val store = RecordingSecureSessionStore(initialEnvelope)
        val cipher = RecordingCodexSessionCipher(
            decryptedSession = CodexSessionPayload(
                accessToken = "old-access",
                refreshToken = "old-refresh",
                idToken = "old-id",
                accountId = "acct-1",
                lastRefresh = Instant.parse("2026-05-23T10:00:00Z"),
            ),
        )
        val tokenRefresh = RecordingTokenRefresh(
            CodexSessionPayload(
                accessToken = "new-access",
                refreshToken = "new-refresh",
                idToken = "new-id",
                accountId = "acct-1",
                lastRefresh = now,
            ),
        )
        val usageFetcher = RecordingUsageFetcher(successfulUsageDto())
        val provider = provider(
            store = store,
            cipher = cipher,
            tokenRefresh = tokenRefresh,
            usageFetcher = usageFetcher,
        )

        val result = provider.refresh(account, RefreshTrigger.Periodic)

        assertTrue(result is ProviderRefreshResult.Success)
        val snapshot = (result as ProviderRefreshResult.Success).snapshot
        assertEquals(QuotaSnapshotSource.BackgroundRefresh, snapshot.source)
        assertEquals(62, snapshot.windows.first().usedPercent)
        assertEquals(listOf("old-refresh"), tokenRefresh.refreshTokens)
        assertEquals(listOf("new-access" to "acct-1"), usageFetcher.requests)
        assertEquals(listOf("new-refresh"), cipher.encryptedRefreshTokens)
        assertEquals("local-1", store.savedEnvelopes.single().localAccountId)
    }

    @Test
    fun `missing session returns auth required without calling network`() = runTest {
        val tokenRefresh = RecordingTokenRefresh(
            CodexSessionPayload(
                accessToken = "unused",
                refreshToken = "unused",
                idToken = null,
                accountId = null,
                lastRefresh = now,
            ),
        )
        val usageFetcher = RecordingUsageFetcher(successfulUsageDto())
        val provider = provider(
            store = RecordingSecureSessionStore(initialEnvelope = null),
            cipher = RecordingCodexSessionCipher(
                decryptedSession = CodexSessionPayload(
                    accessToken = "unused",
                    refreshToken = "unused",
                    idToken = null,
                    accountId = null,
                    lastRefresh = now,
                ),
            ),
            tokenRefresh = tokenRefresh,
            usageFetcher = usageFetcher,
        )

        val result = provider.refresh(account(), RefreshTrigger.Periodic)

        assertTrue(result is ProviderRefreshResult.Failure)
        assertEquals("error_auth_required", (result as ProviderRefreshResult.Failure).error.safeMessageKey)
        assertTrue(tokenRefresh.refreshTokens.isEmpty())
        assertTrue(usageFetcher.requests.isEmpty())
    }

    @Test
    fun `session save failure returns structured retryable failure`() = runTest {
        val provider = provider(
            store = ThrowingSaveSecureSessionStore(envelope(localAccountId = "local-1")),
            cipher = RecordingCodexSessionCipher(
                decryptedSession = CodexSessionPayload(
                    accessToken = "old-access",
                    refreshToken = "old-refresh",
                    idToken = null,
                    accountId = "acct-1",
                    lastRefresh = now,
                ),
            ),
            tokenRefresh = RecordingTokenRefresh(
                CodexSessionPayload(
                    accessToken = "new-access",
                    refreshToken = "new-refresh",
                    idToken = null,
                    accountId = "acct-1",
                    lastRefresh = now,
                ),
            ),
            usageFetcher = RecordingUsageFetcher(successfulUsageDto()),
        )

        val result = provider.refresh(account(), RefreshTrigger.Periodic)

        assertTrue(result is ProviderRefreshResult.Failure)
        val failure = result as ProviderRefreshResult.Failure
        assertEquals("error_network", failure.error.safeMessageKey)
        assertEquals("codex_refresh_session_save_failed", failure.error.diagnosticsDigest)
    }

    private fun provider(
        store: SecureSessionStore,
        cipher: CodexSessionCipher,
        tokenRefresh: CodexTokenRefresh,
        usageFetcher: CodexUsageFetcher,
    ): CodexRefreshProvider =
        CodexRefreshProvider(
            sessionStore = store,
            sessionCipher = cipher,
            tokenRefresh = tokenRefresh,
            usageFetcher = usageFetcher,
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

    private class RecordingCodexSessionCipher(
        private val decryptedSession: CodexSessionPayload,
    ) : CodexSessionCipher {
        val encryptedRefreshTokens = mutableListOf<String>()

        override fun decrypt(envelope: ProviderSessionEnvelope): Result<CodexSessionPayload> =
            Result.success(decryptedSession)

        override fun encrypt(
            session: CodexSessionPayload,
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

    private class ThrowingSaveSecureSessionStore(
        private val envelope: ProviderSessionEnvelope,
    ) : SecureSessionStore {
        override suspend fun save(envelope: ProviderSessionEnvelope) {
            throw IllegalStateException("disk unavailable")
        }

        override suspend fun load(providerId: String, localAccountId: String): ProviderSessionEnvelope = envelope

        override suspend fun delete(providerId: String, localAccountId: String) = Unit
    }

    private class RecordingTokenRefresh(
        private val refreshedSession: CodexSessionPayload,
    ) : CodexTokenRefresh {
        val refreshTokens = mutableListOf<String>()

        override suspend fun refresh(session: CodexSessionPayload): CodexTokenRefresher.Result {
            refreshTokens += session.refreshToken
            return CodexTokenRefresher.Result.Success(refreshedSession)
        }
    }

    private class RecordingUsageFetcher(
        private val dto: CodexUsageResponseDto,
    ) : CodexUsageFetcher {
        val requests = mutableListOf<Pair<String, String?>>()

        override suspend fun fetchUsage(accessToken: String, accountId: String?): CodexUsageClient.Result {
            requests += accessToken to accountId
            return CodexUsageClient.Result.Success(dto)
        }
    }

    private fun account(): ProviderAccount =
        ProviderAccount.createNew(
            localAccountId = LocalAccountId("local-1"),
            providerId = ProviderId("codex"),
            providerAccountId = ProviderAccountId("acct-1"),
            displayName = "Codex",
            now = now,
        )

    private fun envelope(localAccountId: String): ProviderSessionEnvelope =
        ProviderSessionEnvelope(
            providerId = "codex",
            localAccountId = localAccountId,
            providerAccountId = "acct-1",
            schemaVersion = 1,
            payloadCiphertext = byteArrayOf(1, 2, 3),
            payloadNonce = byteArrayOf(4, 5, 6),
            createdAt = now.toString(),
            updatedAt = now.toString(),
        )

    private fun successfulUsageDto(): CodexUsageResponseDto =
        CodexUsageResponseDto(
            planType = "plus",
            rateLimit = CodexRateLimitDto(
                primaryWindow = CodexWindowDto(
                    usedPercent = 62,
                    resetAt = 1_779_426_000,
                    limitWindowSeconds = 18_000,
                ),
                secondaryWindow = CodexWindowDto(
                    usedPercent = 41,
                    resetAt = 1_779_480_000,
                    limitWindowSeconds = 604_800,
                ),
            ),
        )
}
