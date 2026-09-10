package com.kmnexus.codexmeter.providers.codex

import com.kmnexus.codexmeter.data.secure.ProviderSessionEnvelope
import com.kmnexus.codexmeter.data.secure.SecureSessionStore
import com.kmnexus.codexmeter.domain.model.ProviderAccount
import com.kmnexus.codexmeter.domain.model.ProviderAccountId
import com.kmnexus.codexmeter.domain.model.ProviderId
import com.kmnexus.codexmeter.domain.quota.QuotaSnapshotSource
import com.kmnexus.codexmeter.domain.refresh.QuotaError
import com.kmnexus.codexmeter.domain.refresh.RefreshTrigger
import com.kmnexus.codexmeter.providers.codex.auth.CodexTokenRefresher
import com.kmnexus.codexmeter.providers.codex.mapper.CodexUsageMapper
import com.kmnexus.codexmeter.providers.codex.network.CodexUsageClient
import com.kmnexus.codexmeter.providers.codex.session.CodexSessionPayload
import com.kmnexus.codexmeter.refresh.ProviderRefreshResult
import com.kmnexus.codexmeter.refresh.RefreshProvider
import java.time.Clock
import java.time.Instant
import kotlinx.coroutines.CancellationException

class CodexRefreshProvider(
    private val sessionStore: SecureSessionStore,
    private val sessionCipher: CodexSessionCipher,
    private val tokenRefresh: CodexTokenRefresh,
    private val usageFetcher: CodexUsageFetcher,
    private val mapper: CodexUsageMapper = CodexUsageMapper(),
    private val clock: Clock = Clock.systemUTC(),
) : RefreshProvider {
    /**
     * Rotates the OAuth token only when the stored access token is spent, then fetches usage.
     *
     * Refreshing on every poll used to rotate the refresh token several times an hour; a rotation
     * whose result never reaches disk leaves the app holding a token the server already retired,
     * which surfaces as a login that "keeps expiring". Per the device-code spec the token is
     * refreshed when it is about to expire, or once after a 401/403, before giving up on the login.
     */
    override suspend fun refresh(
        account: ProviderAccount,
        trigger: RefreshTrigger,
    ): ProviderRefreshResult {
        val loadedEnvelope = loadEnvelope(account) ?: return authRequired("codex_refresh_session_missing")
        val storedSession = sessionCipher.decrypt(loadedEnvelope).getOrElse {
            return authRequired("codex_refresh_session_decrypt_failed")
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

        val usage = usageFetcher.fetchUsage(
            accessToken = session.accessToken,
            accountId = session.resolveProviderAccountId(account)?.value,
        )
        if (usage is CodexUsageClient.Result.Failure && usage.error is QuotaError.AuthRequired && !alreadyRefreshed) {
            return retryAfterAuthFailure(session, loadedEnvelope, account, trigger, usage.error)
        }
        return usage.toRefreshResult(session, account, trigger)
    }

    private suspend fun retryAfterAuthFailure(
        session: CodexSessionPayload,
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
        val retry = usageFetcher.fetchUsage(
            accessToken = rotated.accessToken,
            accountId = rotated.resolveProviderAccountId(account)?.value,
        )
        if (retry is CodexUsageClient.Result.Failure && retry.error is QuotaError.AuthRequired) {
            return ProviderRefreshResult.Failure(authError)
        }
        return retry.toRefreshResult(rotated, account, trigger)
    }

    private suspend fun rotateToken(
        session: CodexSessionPayload,
        envelope: ProviderSessionEnvelope,
        account: ProviderAccount,
    ): TokenRotation {
        val refreshed = when (val refresh = tokenRefresh.refresh(session)) {
            is CodexTokenRefresher.Result.Failure -> return TokenRotation.Failed(refresh.error)
            is CodexTokenRefresher.Result.Success -> refresh.session
        }
        val providerAccountId = refreshed.resolveProviderAccountId(account)
        val saveError = saveRefreshedSession(
            session = refreshed,
            envelope = envelope.copy(providerAccountId = providerAccountId?.value),
        )
        return saveError?.let(TokenRotation::Failed) ?: TokenRotation.Rotated(refreshed)
    }

    private fun CodexUsageClient.Result.toRefreshResult(
        session: CodexSessionPayload,
        account: ProviderAccount,
        trigger: RefreshTrigger,
    ): ProviderRefreshResult =
        when (this) {
            is CodexUsageClient.Result.Failure -> ProviderRefreshResult.Failure(error)
            is CodexUsageClient.Result.Success -> ProviderRefreshResult.Success(
                mapper.map(
                    dto = dto,
                    localAccountId = account.localAccountId,
                    providerAccountId = session.resolveProviderAccountId(account),
                    fetchedAt = clock.instant(),
                    source = trigger.toSnapshotSource(),
                ),
            )
        }

    /** True when the access token is expired, inside the skew, or of unknown age (legacy sessions). */
    private fun CodexSessionPayload.accessTokenIsSpent(now: Instant): Boolean =
        tokenExpiresAtEpochSeconds?.let { it <= now.epochSecond + EXPIRY_SKEW_SECONDS } ?: true

    private fun CodexSessionPayload.resolveProviderAccountId(account: ProviderAccount): ProviderAccountId? =
        accountId
            ?.takeIf { it.isNotBlank() }
            ?.let(::ProviderAccountId)
            ?: account.providerAccountId

    private sealed interface TokenRotation {
        data class Rotated(val session: CodexSessionPayload) : TokenRotation

        data class Failed(val error: QuotaError) : TokenRotation
    }

    private suspend fun loadEnvelope(account: ProviderAccount): ProviderSessionEnvelope? =
        if (account.providerId == CODEX_PROVIDER_ID) {
            sessionStore.load(
                providerId = account.providerId.value,
                localAccountId = account.localAccountId.value,
            )
        } else {
            null
        }

    private suspend fun saveRefreshedSession(
        session: CodexSessionPayload,
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
            QuotaError.Network(diagnosticsDigest = "codex_refresh_session_save_failed")
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
            RefreshTrigger.ImportValidation -> QuotaSnapshotSource.AuthJsonImport
            RefreshTrigger.AccountSwitch -> QuotaSnapshotSource.ManualRefresh
            RefreshTrigger.Periodic -> QuotaSnapshotSource.BackgroundRefresh
        }

    private companion object {
        val CODEX_PROVIDER_ID = ProviderId("codex")

        /** Matches the Claude and Antigravity providers: refresh a minute before the token dies. */
        const val EXPIRY_SKEW_SECONDS = 60L
    }
}

interface CodexSessionCipher {
    fun decrypt(envelope: ProviderSessionEnvelope): Result<CodexSessionPayload>

    fun encrypt(
        session: CodexSessionPayload,
        envelope: ProviderSessionEnvelope,
        updatedAt: Instant,
    ): ProviderSessionEnvelope
}

fun interface CodexTokenRefresh {
    suspend fun refresh(session: CodexSessionPayload): CodexTokenRefresher.Result
}

fun interface CodexUsageFetcher {
    suspend fun fetchUsage(
        accessToken: String,
        accountId: String?,
    ): CodexUsageClient.Result
}
