package com.kmnexus.codexmeter.providers.claude

import com.kmnexus.codexmeter.data.secure.PayloadCipher
import com.kmnexus.codexmeter.data.secure.ProviderSessionEnvelope
import com.kmnexus.codexmeter.data.secure.SecureSessionStore
import com.kmnexus.codexmeter.domain.model.ProviderAccount
import com.kmnexus.codexmeter.domain.model.ProviderId
import com.kmnexus.codexmeter.domain.quota.QuotaSnapshotSource
import com.kmnexus.codexmeter.domain.refresh.QuotaError
import com.kmnexus.codexmeter.domain.refresh.RefreshTrigger
import com.kmnexus.codexmeter.providers.claude.mapper.ClaudeUsageMapper
import com.kmnexus.codexmeter.providers.claude.network.ClaudeUsageClient
import com.kmnexus.codexmeter.providers.claude.session.ClaudeSessionPayload
import com.kmnexus.codexmeter.providers.common.auth.OAuthTokenClient
import com.kmnexus.codexmeter.refresh.ProviderRefreshResult
import com.kmnexus.codexmeter.refresh.RefreshProvider
import kotlinx.serialization.json.Json
import java.time.Clock

class ClaudeRefreshProvider(
    private val client: ClaudeUsageClient,
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
                QuotaError.AuthRequired(httpStatus = null, diagnosticsDigest = "claude_session_missing"),
            )
        var session = try {
            json.decodeFromString<ClaudeSessionPayload>(
                payloadCipher.decrypt(envelope.payloadCiphertext, envelope.payloadNonce).decodeToString(),
            )
        } catch (_: Exception) {
            return ProviderRefreshResult.Failure(
                QuotaError.AuthRequired(httpStatus = null, diagnosticsDigest = "claude_session_decode_failed"),
            )
        }

        // Refresh the access token before it expires rather than forcing a re-login.
        val nowEpoch = clock.instant().epochSecond
        val expired = session.tokenExpiresAtEpochSeconds?.let { it <= nowEpoch + EXPIRY_SKEW_SECONDS } ?: false
        if (expired) {
            val refreshToken = session.refreshToken
                ?: return ProviderRefreshResult.Failure(
                    QuotaError.AuthRequired(httpStatus = null, diagnosticsDigest = "claude_token_expired"),
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
                    persist(account, session, envelope, now)
                }
            }
        }

        return when (val result = client.fetchUsage(session.accessToken)) {
            is ClaudeUsageClient.Result.Failure ->
                ProviderRefreshResult.Failure(result.error)
            is ClaudeUsageClient.Result.Success ->
                ProviderRefreshResult.Success(
                    ClaudeUsageMapper.map(
                        dto = result.dto,
                        localAccountId = account.localAccountId,
                        providerAccountId = account.providerAccountId,
                        fetchedAt = clock.instant(),
                        source = trigger.toSnapshotSource(),
                    ),
                )
        }
    }

    private suspend fun persist(
        account: ProviderAccount,
        session: ClaudeSessionPayload,
        previous: ProviderSessionEnvelope,
        now: java.time.Instant,
    ) {
        val jsonBytes = json.encodeToString(ClaudeSessionPayload.serializer(), session).encodeToByteArray()
        val encrypted = payloadCipher.encrypt(jsonBytes)
        sessionStore.save(
            previous.copy(
                payloadCiphertext = encrypted.ciphertext,
                payloadNonce = encrypted.nonce,
                updatedAt = now.toString(),
            ),
        )
    }

    private fun RefreshTrigger.toSnapshotSource(): QuotaSnapshotSource = when (this) {
        RefreshTrigger.AppOpen -> QuotaSnapshotSource.AppOpenRefresh
        RefreshTrigger.Manual -> QuotaSnapshotSource.ManualRefresh
        RefreshTrigger.Widget -> QuotaSnapshotSource.WidgetRefresh
        RefreshTrigger.ImportValidation -> QuotaSnapshotSource.OAuthPkceLogin
        RefreshTrigger.AccountSwitch -> QuotaSnapshotSource.ManualRefresh
        RefreshTrigger.Periodic -> QuotaSnapshotSource.BackgroundRefresh
    }

    companion object {
        val CLAUDE_PROVIDER_ID = ProviderId("claude")
        private const val EXPIRY_SKEW_SECONDS = 60L
    }
}
