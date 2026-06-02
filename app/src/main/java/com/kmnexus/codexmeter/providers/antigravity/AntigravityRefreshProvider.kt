package com.kmnexus.codexmeter.providers.antigravity

import com.kmnexus.codexmeter.data.secure.PayloadCipher
import com.kmnexus.codexmeter.data.secure.ProviderSessionEnvelope
import com.kmnexus.codexmeter.data.secure.SecureSessionStore
import com.kmnexus.codexmeter.domain.model.ProviderAccount
import com.kmnexus.codexmeter.domain.model.ProviderId
import com.kmnexus.codexmeter.domain.quota.QuotaSnapshotSource
import com.kmnexus.codexmeter.domain.refresh.QuotaError
import com.kmnexus.codexmeter.domain.refresh.RefreshTrigger
import com.kmnexus.codexmeter.providers.antigravity.mapper.AntigravityQuotaMapper
import com.kmnexus.codexmeter.providers.antigravity.network.AntigravityQuotaClient
import com.kmnexus.codexmeter.providers.antigravity.session.AntigravitySessionPayload
import com.kmnexus.codexmeter.providers.common.auth.OAuthTokenClient
import com.kmnexus.codexmeter.refresh.ProviderRefreshResult
import com.kmnexus.codexmeter.refresh.RefreshProvider
import kotlinx.serialization.json.Json
import java.time.Clock

class AntigravityRefreshProvider(
    private val client: AntigravityQuotaClient,
    private val tokenClient: OAuthTokenClient,
    private val sessionStore: SecureSessionStore,
    private val payloadCipher: PayloadCipher,
    private val clock: Clock = Clock.systemUTC(),
) : RefreshProvider {
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun refresh(
        account: ProviderAccount,
        trigger: RefreshTrigger,
    ): ProviderRefreshResult {
        val envelope = sessionStore.load(account.providerId.value, account.localAccountId.value)
            ?: return ProviderRefreshResult.Failure(
                QuotaError.AuthRequired(
                    httpStatus = null,
                    diagnosticsDigest = "antigravity_session_missing",
                ),
            )
        var session = try {
            json.decodeFromString<AntigravitySessionPayload>(
                payloadCipher.decrypt(envelope.payloadCiphertext, envelope.payloadNonce).decodeToString(),
            )
        } catch (_: Exception) {
            return ProviderRefreshResult.Failure(
                QuotaError.AuthRequired(
                    httpStatus = null,
                    diagnosticsDigest = "antigravity_session_decode_failed",
                ),
            )
        }

        // Refresh the Google access token before it expires rather than forcing a re-login.
        val nowEpoch = clock.instant().epochSecond
        val expired = session.tokenExpiresAtEpochSeconds?.let { it <= nowEpoch + EXPIRY_SKEW_SECONDS } ?: false
        if (expired) {
            val refreshToken = session.refreshToken
                ?: return ProviderRefreshResult.Failure(
                    QuotaError.AuthRequired(httpStatus = null, diagnosticsDigest = "antigravity_token_expired"),
                )
            when (val refreshed = tokenClient.refresh(refreshToken)) {
                is OAuthTokenClient.Result.Failure ->
                    return ProviderRefreshResult.Failure(refreshed.error)
                is OAuthTokenClient.Result.Success -> {
                    val now = clock.instant()
                    session = session.copy(
                        accessToken = refreshed.tokens.accessToken,
                        refreshToken = refreshed.tokens.refreshToken ?: session.refreshToken,
                        tokenExpiresAtEpochSeconds = refreshed.tokens.expiresInSeconds?.let { now.epochSecond + it },
                    )
                    val jsonBytes = json.encodeToString(AntigravitySessionPayload.serializer(), session)
                        .encodeToByteArray()
                    val encrypted = payloadCipher.encrypt(jsonBytes)
                    sessionStore.save(
                        envelope.copy(
                            payloadCiphertext = encrypted.ciphertext,
                            payloadNonce = encrypted.nonce,
                            updatedAt = now.toString(),
                        ),
                    )
                }
            }
        }

        return when (val result = client.fetchQuota(session.accessToken)) {
            is AntigravityQuotaClient.Result.Failure ->
                ProviderRefreshResult.Failure(result.error)
            is AntigravityQuotaClient.Result.Success ->
                ProviderRefreshResult.Success(
                    AntigravityQuotaMapper.map(
                        dto = result.dto,
                        localAccountId = account.localAccountId,
                        providerAccountId = account.providerAccountId,
                        fetchedAt = clock.instant(),
                        source = trigger.toSnapshotSource(),
                    ),
                )
        }
    }

    private fun RefreshTrigger.toSnapshotSource(): QuotaSnapshotSource = when (this) {
        RefreshTrigger.AppOpen -> QuotaSnapshotSource.AppOpenRefresh
        RefreshTrigger.Manual -> QuotaSnapshotSource.ManualRefresh
        RefreshTrigger.Widget -> QuotaSnapshotSource.WidgetRefresh
        RefreshTrigger.ImportValidation -> QuotaSnapshotSource.OAuthWebView
        RefreshTrigger.AccountSwitch -> QuotaSnapshotSource.ManualRefresh
        RefreshTrigger.Periodic -> QuotaSnapshotSource.BackgroundRefresh
    }

    companion object {
        val ANTIGRAVITY_PROVIDER_ID = ProviderId("antigravity")
        private const val EXPIRY_SKEW_SECONDS = 60L
    }
}
