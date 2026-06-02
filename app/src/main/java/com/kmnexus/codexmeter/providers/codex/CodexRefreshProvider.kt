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
    override suspend fun refresh(
        account: ProviderAccount,
        trigger: RefreshTrigger,
    ): ProviderRefreshResult {
        val loadedEnvelope = loadEnvelope(account) ?: return authRequired("codex_refresh_session_missing")
        val currentSession = sessionCipher.decrypt(loadedEnvelope).getOrElse {
            return authRequired("codex_refresh_session_decrypt_failed")
        }

        val refreshedSession = when (val refresh = tokenRefresh.refresh(currentSession)) {
            is CodexTokenRefresher.Result.Failure -> return ProviderRefreshResult.Failure(refresh.error)
            is CodexTokenRefresher.Result.Success -> refresh.session
        }

        val refreshedProviderAccountId = refreshedSession.accountId
            ?.takeIf { it.isNotBlank() }
            ?.let(::ProviderAccountId)
            ?: account.providerAccountId
        val saveError = saveRefreshedSession(
            session = refreshedSession,
            envelope = loadedEnvelope.copy(providerAccountId = refreshedProviderAccountId?.value),
        )
        if (saveError != null) {
            return ProviderRefreshResult.Failure(saveError)
        }

        return when (
            val usage = usageFetcher.fetchUsage(
                accessToken = refreshedSession.accessToken,
                accountId = refreshedProviderAccountId?.value,
            )
        ) {
            is CodexUsageClient.Result.Failure -> ProviderRefreshResult.Failure(usage.error)
            is CodexUsageClient.Result.Success -> ProviderRefreshResult.Success(
                mapper.map(
                    dto = usage.dto,
                    localAccountId = account.localAccountId,
                    providerAccountId = refreshedProviderAccountId,
                    fetchedAt = clock.instant(),
                    source = trigger.toSnapshotSource(),
                ),
            )
        }
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
