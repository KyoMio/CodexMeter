package com.kmnexus.codexmeter.providers.kimicode.auth

import com.kmnexus.codexmeter.data.secure.PayloadCipher
import com.kmnexus.codexmeter.data.secure.ProviderSessionEnvelope
import com.kmnexus.codexmeter.data.secure.SecureSessionStore
import com.kmnexus.codexmeter.domain.model.ProviderAccount
import com.kmnexus.codexmeter.domain.model.ProviderId
import com.kmnexus.codexmeter.domain.quota.QuotaSnapshot
import com.kmnexus.codexmeter.domain.quota.QuotaSnapshotSource
import com.kmnexus.codexmeter.providers.SessionImporter
import com.kmnexus.codexmeter.providers.kimicode.mapper.KimiCodeUsageMapper
import com.kmnexus.codexmeter.providers.kimicode.network.KimiCodeUsageClient
import com.kmnexus.codexmeter.providers.kimicode.session.KimiCodeSessionPayload
import java.time.Clock
import kotlinx.serialization.json.Json

/**
 * Validates a Kimi Code API key by fetching usage once; only a successful fetch persists the
 * encrypted session (with the chosen region base URL) and returns the first snapshot.
 */
class KimiCodeSessionImporter(
    private val usageClient: KimiCodeUsageClient,
    private val sessionStore: SecureSessionStore,
    private val payloadCipher: PayloadCipher,
    private val clock: Clock = Clock.systemUTC(),
) : SessionImporter {
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun importFromApiKey(
        apiKey: String,
        account: ProviderAccount,
        apiBaseUrl: String?,
    ): Result<QuotaSnapshot> {
        val baseUrl = apiBaseUrl ?: KimiCodeUsageClient.DEFAULT_BASE_URL
        val usageResult = usageClient.fetchUsage(apiKey, baseUrl)

        return when (usageResult) {
            is KimiCodeUsageClient.Result.Failure ->
                Result.failure(
                    RuntimeException(
                        "Kimi Code usage fetch failed: ${usageResult.error.safeMessageKey}",
                    ),
                )

            is KimiCodeUsageClient.Result.Success -> {
                val now = clock.instant()
                // Persist the chosen region base URL so later refreshes hit the same platform.
                val payload = KimiCodeSessionPayload(apiKey = apiKey, apiBaseUrl = baseUrl)
                val jsonBytes = json.encodeToString(
                    KimiCodeSessionPayload.serializer(),
                    payload,
                ).encodeToByteArray()

                val encrypted = payloadCipher.encrypt(jsonBytes)
                val envelope = ProviderSessionEnvelope(
                    providerId = KIMI_CODE_PROVIDER_ID.value,
                    localAccountId = account.localAccountId.value,
                    providerAccountId = account.providerAccountId?.value,
                    schemaVersion = 1,
                    payloadCiphertext = encrypted.ciphertext,
                    payloadNonce = encrypted.nonce,
                    createdAt = now.toString(),
                    updatedAt = now.toString(),
                )

                sessionStore.save(envelope)

                val snapshot = KimiCodeUsageMapper.map(
                    dto = usageResult.dto,
                    localAccountId = account.localAccountId,
                    providerAccountId = account.providerAccountId,
                    fetchedAt = now,
                    source = QuotaSnapshotSource.ApiKeyImport,
                )

                Result.success(snapshot)
            }
        }
    }

    override suspend fun importFromCookie(
        cookieJson: String,
        account: ProviderAccount,
    ): Result<QuotaSnapshot> =
        Result.failure(
            UnsupportedOperationException("Kimi Code does not support cookie auth"),
        )

    override suspend fun importFromOAuthPkce(
        code: String,
        verifier: String,
        redirectUri: String,
        account: ProviderAccount,
    ): Result<QuotaSnapshot> =
        Result.failure(
            UnsupportedOperationException("Kimi Code does not support OAuth PKCE auth"),
        )

    private companion object {
        val KIMI_CODE_PROVIDER_ID = ProviderId("kimi_code")
    }
}
