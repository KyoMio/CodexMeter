package com.kmnexus.codexmeter.providers.antigravity.auth

import com.kmnexus.codexmeter.data.secure.PayloadCipher
import com.kmnexus.codexmeter.data.secure.ProviderSessionEnvelope
import com.kmnexus.codexmeter.data.secure.SecureSessionStore
import com.kmnexus.codexmeter.domain.model.ProviderAccount
import com.kmnexus.codexmeter.domain.model.ProviderId
import com.kmnexus.codexmeter.domain.quota.QuotaSnapshot
import com.kmnexus.codexmeter.domain.quota.QuotaSnapshotSource
import com.kmnexus.codexmeter.providers.SessionImporter
import com.kmnexus.codexmeter.providers.antigravity.mapper.AntigravityQuotaMapper
import com.kmnexus.codexmeter.providers.antigravity.network.AntigravityQuotaClient
import com.kmnexus.codexmeter.providers.antigravity.session.AntigravitySessionPayload
import com.kmnexus.codexmeter.providers.common.auth.OAuthTokenClient
import java.time.Clock
import kotlinx.serialization.json.Json

class AntigravitySessionImporter(
    private val tokenClient: OAuthTokenClient,
    private val client: AntigravityQuotaClient,
    private val sessionStore: SecureSessionStore,
    private val payloadCipher: PayloadCipher,
    private val clock: Clock = Clock.systemUTC(),
) : SessionImporter {
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun importFromOAuthPkce(
        code: String,
        verifier: String,
        redirectUri: String,
        account: ProviderAccount,
    ): Result<QuotaSnapshot> {
        // Google returns an authorization code; exchange it (with client_secret) for tokens first.
        val tokens = when (val result = tokenClient.exchangeAuthorizationCode(code, verifier, redirectUri)) {
            is OAuthTokenClient.Result.Failure ->
                return Result.failure(RuntimeException("Antigravity OAuth token exchange failed: ${result.error}"))
            is OAuthTokenClient.Result.Success -> result.tokens
        }

        return when (val quotaResult = client.fetchQuota(tokens.accessToken)) {
            is AntigravityQuotaClient.Result.Failure ->
                Result.failure(
                    RuntimeException("Antigravity quota fetch failed: ${quotaResult.error.diagnosticsDigest}"),
                )

            is AntigravityQuotaClient.Result.Success -> {
                val now = clock.instant()
                val payload = AntigravitySessionPayload(
                    accessToken = tokens.accessToken,
                    refreshToken = tokens.refreshToken,
                    tokenExpiresAtEpochSeconds = tokens.expiresInSeconds?.let { now.epochSecond + it },
                    planTier = quotaResult.dto.tier,
                )
                val jsonBytes = json.encodeToString(AntigravitySessionPayload.serializer(), payload).encodeToByteArray()
                val encrypted = payloadCipher.encrypt(jsonBytes)
                sessionStore.save(
                    ProviderSessionEnvelope(
                        providerId = ANTIGRAVITY_PROVIDER_ID.value,
                        localAccountId = account.localAccountId.value,
                        providerAccountId = account.providerAccountId?.value,
                        schemaVersion = 1,
                        payloadCiphertext = encrypted.ciphertext,
                        payloadNonce = encrypted.nonce,
                        createdAt = now.toString(),
                        updatedAt = now.toString(),
                    ),
                )
                Result.success(
                    AntigravityQuotaMapper.map(
                        dto = quotaResult.dto,
                        localAccountId = account.localAccountId,
                        providerAccountId = account.providerAccountId,
                        fetchedAt = now,
                        source = QuotaSnapshotSource.OAuthPkceLogin,
                    ),
                )
            }
        }
    }

    override suspend fun importFromApiKey(
        apiKey: String,
        account: ProviderAccount,
        apiBaseUrl: String?,
    ): Result<QuotaSnapshot> =
        Result.failure(UnsupportedOperationException("Antigravity does not support API key auth"))

    override suspend fun importFromCookie(
        cookieJson: String,
        account: ProviderAccount,
    ): Result<QuotaSnapshot> =
        Result.failure(UnsupportedOperationException("Antigravity does not support cookie auth"))

    private companion object {
        val ANTIGRAVITY_PROVIDER_ID = ProviderId("antigravity")
    }
}
