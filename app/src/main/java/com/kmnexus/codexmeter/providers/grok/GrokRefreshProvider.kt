package com.kmnexus.codexmeter.providers.grok

import com.kmnexus.codexmeter.data.secure.ProviderSessionEnvelope
import com.kmnexus.codexmeter.data.secure.SecureSessionStore
import com.kmnexus.codexmeter.domain.model.ProviderAccount
import com.kmnexus.codexmeter.domain.model.ProviderAccountId
import com.kmnexus.codexmeter.domain.model.ProviderId
import com.kmnexus.codexmeter.domain.quota.QuotaSnapshotSource
import com.kmnexus.codexmeter.domain.refresh.QuotaError
import com.kmnexus.codexmeter.domain.refresh.RefreshTrigger
import com.kmnexus.codexmeter.providers.grok.auth.GrokTokenRefresher
import com.kmnexus.codexmeter.providers.grok.mapper.GrokMapper
import com.kmnexus.codexmeter.providers.grok.network.GrokBillingClient
import com.kmnexus.codexmeter.providers.grok.session.GrokSessionPayload
import com.kmnexus.codexmeter.refresh.ProviderRefreshResult
import com.kmnexus.codexmeter.refresh.RefreshProvider
import java.time.Clock
import java.time.Instant
import kotlinx.coroutines.CancellationException

class GrokRefreshProvider(
    private val sessionStore: SecureSessionStore,
    private val sessionCipher: GrokSessionCipher,
    private val tokenRefresh: GrokTokenRefresh,
    private val billingFetcher: GrokBillingFetcher,
    private val clock: Clock = Clock.systemUTC(),
) : RefreshProvider {
    /**
     * Rotates the OAuth token only when the stored access token is spent, then fetches billing
     * (mirrors CodexRefreshProvider). xAI rotates the refresh token on every grant, so a rotation
     * whose result never reaches disk would strand the app with a retired token — the rotated
     * session is written back before billing runs, and any failure path leaves the stored
     * envelope untouched to preserve the last known good state.
     */
    override suspend fun refresh(
        account: ProviderAccount,
        trigger: RefreshTrigger,
    ): ProviderRefreshResult {
        val loadedEnvelope = loadEnvelope(account) ?: return authRequired("grok_session_missing")
        val storedSession = sessionCipher.decrypt(loadedEnvelope).getOrElse {
            return authRequired("grok_session_decode_failed")
        }

        var session = storedSession
        var alreadyRefreshed = false
        if (storedSession.accessTokenIsSpent(clock.instant())) {
            session = when (val rotated = rotateToken(storedSession, loadedEnvelope, account)) {
                is TokenRotation.Failed -> return ProviderRefreshResult.Failure(rotated.error)
                is TokenRotation.Rotated -> rotated.session
            }
            alreadyRefreshed = true
        }

        val billing = billingFetcher.fetchBilling(session.accessToken)
        if (billing is GrokBillingClient.Result.Failure &&
            billing.error is QuotaError.AuthRequired &&
            !alreadyRefreshed
        ) {
            return retryAfterAuthFailure(session, loadedEnvelope, account, trigger, billing.error)
        }
        return billing.toRefreshResult(session, account, trigger)
    }

    private suspend fun retryAfterAuthFailure(
        session: GrokSessionPayload,
        envelope: ProviderSessionEnvelope,
        account: ProviderAccount,
        trigger: RefreshTrigger,
        authError: QuotaError,
    ): ProviderRefreshResult {
        val rotated = when (val rotation = rotateToken(session, envelope, account)) {
            // Report why the replacement failed rather than the 401: a network failure here is
            // retryable and must not be mistaken for a dead credential.
            is TokenRotation.Failed -> return ProviderRefreshResult.Failure(rotation.error)
            is TokenRotation.Rotated -> rotation.session
        }
        val retry = billingFetcher.fetchBilling(rotated.accessToken)
        if (retry is GrokBillingClient.Result.Failure && retry.error is QuotaError.AuthRequired) {
            return ProviderRefreshResult.Failure(authError)
        }
        return retry.toRefreshResult(rotated, account, trigger)
    }

    private suspend fun rotateToken(
        session: GrokSessionPayload,
        envelope: ProviderSessionEnvelope,
        account: ProviderAccount,
    ): TokenRotation {
        val refreshed = when (val refresh = tokenRefresh.refresh(session)) {
            is GrokTokenRefresher.Result.Failure -> return TokenRotation.Failed(refresh.error)
            is GrokTokenRefresher.Result.Success -> refresh.session
        }
        val providerAccountId = refreshed.resolveProviderAccountId(account)
        val saveError = saveRefreshedSession(
            session = refreshed,
            envelope = envelope.copy(providerAccountId = providerAccountId?.value),
        )
        return saveError?.let(TokenRotation::Failed) ?: TokenRotation.Rotated(refreshed)
    }

    private fun GrokBillingClient.Result.toRefreshResult(
        session: GrokSessionPayload,
        account: ProviderAccount,
        trigger: RefreshTrigger,
    ): ProviderRefreshResult =
        when (this) {
            is GrokBillingClient.Result.Failure -> ProviderRefreshResult.Failure(error)
            is GrokBillingClient.Result.Success -> ProviderRefreshResult.Success(
                GrokMapper.map(
                    dto = dto,
                    localAccountId = account.localAccountId,
                    providerAccountId = session.resolveProviderAccountId(account),
                    fetchedAt = clock.instant(),
                    source = trigger.toSnapshotSource(),
                ),
            )
        }

    /** True when the access token is expired, inside the skew, or of unknown age (legacy sessions). */
    private fun GrokSessionPayload.accessTokenIsSpent(now: Instant): Boolean =
        tokenExpiresAtEpochSeconds?.let { it <= now.epochSecond + EXPIRY_SKEW_SECONDS } ?: true

    private fun GrokSessionPayload.resolveProviderAccountId(account: ProviderAccount): ProviderAccountId? =
        accountId
            ?.takeIf { it.isNotBlank() }
            ?.let(::ProviderAccountId)
            ?: account.providerAccountId

    private sealed interface TokenRotation {
        data class Rotated(val session: GrokSessionPayload) : TokenRotation

        data class Failed(val error: QuotaError) : TokenRotation
    }

    private suspend fun loadEnvelope(account: ProviderAccount): ProviderSessionEnvelope? =
        if (account.providerId == GROK_PROVIDER_ID) {
            sessionStore.load(
                providerId = account.providerId.value,
                localAccountId = account.localAccountId.value,
            )
        } else {
            null
        }

    private suspend fun saveRefreshedSession(
        session: GrokSessionPayload,
        envelope: ProviderSessionEnvelope,
    ): QuotaError? =
        try {
            sessionStore.save(
                sessionCipher.encrypt(
                    session = session,
                    envelope = envelope,
                    updatedAt = clock.instant(),
                ),
            )
            null
        } catch (exception: CancellationException) {
            throw exception
        } catch (_: Exception) {
            QuotaError.Network(diagnosticsDigest = "grok_refresh_session_save_failed")
        }

    private fun authRequired(diagnosticsDigest: String): ProviderRefreshResult.Failure =
        ProviderRefreshResult.Failure(
            QuotaError.AuthRequired(
                httpStatus = null,
                diagnosticsDigest = diagnosticsDigest,
            ),
        )

    private fun RefreshTrigger.toSnapshotSource(): QuotaSnapshotSource =
        when (this) {
            RefreshTrigger.AppOpen -> QuotaSnapshotSource.AppOpenRefresh
            RefreshTrigger.Manual -> QuotaSnapshotSource.ManualRefresh
            RefreshTrigger.Widget -> QuotaSnapshotSource.WidgetRefresh
            RefreshTrigger.ImportValidation -> QuotaSnapshotSource.DeviceCodeLogin
            RefreshTrigger.AccountSwitch -> QuotaSnapshotSource.ManualRefresh
            RefreshTrigger.Periodic -> QuotaSnapshotSource.BackgroundRefresh
        }

    private companion object {
        val GROK_PROVIDER_ID = ProviderId("grok")

        /** Matches the Codex provider: refresh a minute before the token dies. */
        const val EXPIRY_SKEW_SECONDS = 60L
    }
}

interface GrokSessionCipher {
    fun decrypt(envelope: ProviderSessionEnvelope): Result<GrokSessionPayload>

    fun encrypt(
        session: GrokSessionPayload,
        envelope: ProviderSessionEnvelope,
        updatedAt: Instant,
    ): ProviderSessionEnvelope
}

fun interface GrokTokenRefresh {
    suspend fun refresh(session: GrokSessionPayload): GrokTokenRefresher.Result
}

fun interface GrokBillingFetcher {
    suspend fun fetchBilling(accessToken: String): GrokBillingClient.Result
}
