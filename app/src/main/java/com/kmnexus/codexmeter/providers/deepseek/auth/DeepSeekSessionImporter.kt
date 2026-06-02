package com.kmnexus.codexmeter.providers.deepseek.auth

import com.kmnexus.codexmeter.data.secure.PayloadCipher
import com.kmnexus.codexmeter.data.secure.ProviderSessionEnvelope
import com.kmnexus.codexmeter.data.secure.SecureSessionStore
import com.kmnexus.codexmeter.domain.model.ProviderAccount
import com.kmnexus.codexmeter.domain.model.ProviderId
import com.kmnexus.codexmeter.domain.quota.QuotaSnapshot
import com.kmnexus.codexmeter.domain.quota.QuotaSnapshotSource
import com.kmnexus.codexmeter.providers.SessionImporter
import com.kmnexus.codexmeter.providers.deepseek.mapper.DeepSeekBalanceMapper
import com.kmnexus.codexmeter.providers.deepseek.network.DeepSeekBalanceClient
import com.kmnexus.codexmeter.providers.deepseek.session.DeepSeekSessionPayload
import java.time.Clock
import kotlinx.serialization.json.Json

class DeepSeekSessionImporter(
    private val balanceClient: DeepSeekBalanceClient,
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
        val balanceResult = balanceClient.fetchBalance(apiKey)

        return when (balanceResult) {
            is DeepSeekBalanceClient.Result.Failure ->
                Result.failure(
                    RuntimeException(
                        "DeepSeek balance fetch failed: ${balanceResult.error.safeMessageKey}",
                    ),
                )

            is DeepSeekBalanceClient.Result.Success -> {
                val now = clock.instant()
                val payload = DeepSeekSessionPayload(apiKey)
                val jsonBytes = json.encodeToString(
                    DeepSeekSessionPayload.serializer(),
                    payload,
                ).encodeToByteArray()

                val encrypted = payloadCipher.encrypt(jsonBytes)
                val envelope = ProviderSessionEnvelope(
                    providerId = DEEPSEEK_PROVIDER_ID.value,
                    localAccountId = account.localAccountId.value,
                    providerAccountId = account.providerAccountId?.value,
                    schemaVersion = 1,
                    payloadCiphertext = encrypted.ciphertext,
                    payloadNonce = encrypted.nonce,
                    createdAt = now.toString(),
                    updatedAt = now.toString(),
                )

                sessionStore.save(envelope)

                val snapshot = DeepSeekBalanceMapper.map(
                    dto = balanceResult.dto,
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
            UnsupportedOperationException("DeepSeek does not support cookie auth"),
        )

    override suspend fun importFromOAuthPkce(
        code: String,
        verifier: String,
        redirectUri: String,
        account: ProviderAccount,
    ): Result<QuotaSnapshot> =
        Result.failure(
            UnsupportedOperationException("DeepSeek does not support OAuth PKCE auth"),
        )

    private companion object {
        val DEEPSEEK_PROVIDER_ID = ProviderId("deepseek")
    }
}
