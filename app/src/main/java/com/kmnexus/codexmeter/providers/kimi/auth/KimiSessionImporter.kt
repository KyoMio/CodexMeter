package com.kmnexus.codexmeter.providers.kimi.auth

import com.kmnexus.codexmeter.data.secure.PayloadCipher
import com.kmnexus.codexmeter.data.secure.ProviderSessionEnvelope
import com.kmnexus.codexmeter.data.secure.SecureSessionStore
import com.kmnexus.codexmeter.domain.model.ProviderAccount
import com.kmnexus.codexmeter.domain.quota.QuotaSnapshot
import com.kmnexus.codexmeter.domain.quota.QuotaSnapshotSource
import com.kmnexus.codexmeter.providers.SessionImporter
import com.kmnexus.codexmeter.providers.kimi.mapper.KimiQuotaMapper
import com.kmnexus.codexmeter.providers.kimi.network.KimiQuotaClient
import com.kmnexus.codexmeter.providers.kimi.session.KimiSessionPayload
import java.time.Clock
import kotlinx.serialization.json.Json

class KimiSessionImporter(
    private val client: KimiQuotaClient,
    private val sessionStore: SecureSessionStore,
    private val payloadCipher: PayloadCipher,
    private val clock: Clock = Clock.systemUTC(),
) : SessionImporter {
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun importFromCookie(
        cookieJson: String,
        account: ProviderAccount,
    ): Result<QuotaSnapshot> {
        return when (val result = client.fetchQuota(cookieJson)) {
            is KimiQuotaClient.Result.Failure ->
                Result.failure(
                    RuntimeException(
                        "Kimi cookie import failed: ${result.error.diagnosticsDigest}",
                    ),
                )
            is KimiQuotaClient.Result.Success -> {
                val now = clock.instant()
                val nowStr = now.toString()

                // Serialize KimiSessionPayload(cookieValue) to JSON bytes
                val payload = KimiSessionPayload(cookieValue = cookieJson)
                val jsonBytes = json.encodeToString(KimiSessionPayload.serializer(), payload)
                    .encodeToByteArray()

                // Create ProviderSessionEnvelope
                val encrypted = payloadCipher.encrypt(jsonBytes)
                val envelope = ProviderSessionEnvelope(
                    providerId = account.providerId.value,
                    localAccountId = account.localAccountId.value,
                    providerAccountId = account.providerAccountId?.value,
                    schemaVersion = 1,
                    payloadCiphertext = encrypted.ciphertext,
                    payloadNonce = encrypted.nonce,
                    createdAt = nowStr,
                    updatedAt = nowStr,
                )

                // Save to session store
                sessionStore.save(envelope)

                // Map response via KimiQuotaMapper with source=CookieAuth
                val snapshot = KimiQuotaMapper.map(
                    dto = result.dto,
                    localAccountId = account.localAccountId,
                    providerAccountId = account.providerAccountId,
                    fetchedAt = now,
                    source = QuotaSnapshotSource.CookieAuth,
                )

                Result.success(snapshot)
            }
        }
    }

    override suspend fun importFromApiKey(
        apiKey: String,
        account: ProviderAccount,
        apiBaseUrl: String?,
    ): Result<QuotaSnapshot> =
        Result.failure(UnsupportedOperationException("Kimi does not support API key auth"))

    override suspend fun importFromOAuthPkce(
        code: String,
        verifier: String,
        redirectUri: String,
        account: ProviderAccount,
    ): Result<QuotaSnapshot> =
        Result.failure(UnsupportedOperationException("Kimi does not support OAuth PKCE auth"))
}
