package com.kmnexus.codexmeter.providers.codex.auth

import com.kmnexus.codexmeter.data.secure.ProviderSessionEnvelope
import com.kmnexus.codexmeter.domain.model.LocalAccountId
import com.kmnexus.codexmeter.domain.model.ProviderAccount
import com.kmnexus.codexmeter.domain.model.ProviderAccountId
import com.kmnexus.codexmeter.domain.model.ProviderId
import com.kmnexus.codexmeter.domain.quota.QuotaSnapshot
import com.kmnexus.codexmeter.domain.quota.QuotaSnapshotSource
import com.kmnexus.codexmeter.domain.refresh.QuotaError
import com.kmnexus.codexmeter.providers.SessionImporter
import com.kmnexus.codexmeter.providers.codex.mapper.CodexUsageMapper
import com.kmnexus.codexmeter.providers.codex.network.CodexUsageClient
import com.kmnexus.codexmeter.providers.codex.session.CodexSessionPayload
import java.time.Clock
import java.time.Instant
import kotlinx.coroutines.CancellationException

class CodexSessionImporter(
    private val usageClient: UsageClient,
    private val mapper: CodexUsageMapper,
    private val importPersistence: ImportPersistence,
    private val sessionEnvelopeFactory: SessionEnvelopeFactory,
    private val localAccountIdProvider: LocalAccountIdProvider,
    private val defaultDisplayName: String,
    private val clock: Clock,
) : SessionImporter {
    /**
     * Validates a freshly exchanged OAuth session through the official usage endpoint before
     * anything is persisted. Device-code login commits only this prepared, sanitized model.
     */
    suspend fun prepareDeviceCodeSession(payload: CodexSessionPayload): PrepareResult =
        prepareSessionPayload(
            payload = payload,
            source = QuotaSnapshotSource.DeviceCodeLogin,
        )

    suspend fun commitPreparedDeviceCodeSession(preparedImport: PreparedImport): Result =
        savePreparedImport(preparedImport)

    private suspend fun prepareSessionPayload(
        payload: CodexSessionPayload,
        source: QuotaSnapshotSource,
    ): PrepareResult {
        val usageResult = try {
            usageClient.fetchUsage(payload.accessToken, payload.accountId)
        } catch (exception: CancellationException) {
            throw exception
        } catch (_: Exception) {
            val error = QuotaError.Network(
                diagnosticsDigest = USAGE_VALIDATION_FAILURE_DIGEST,
            )
            return PrepareResult.Failure(
                message = error.safeMessageKey,
                quotaError = error,
            )
        }

        return when (usageResult) {
            is CodexUsageClient.Result.Failure -> PrepareResult.Failure(
                message = usageResult.error.safeMessageKey,
                quotaError = usageResult.error,
            )
            is CodexUsageClient.Result.Success -> buildPreparedImport(
                payload = payload,
                usageResult = usageResult,
                source = source,
            )
        }
    }

    private fun buildPreparedImport(
        payload: CodexSessionPayload,
        usageResult: CodexUsageClient.Result.Success,
        source: QuotaSnapshotSource,
    ): PrepareResult =
        try {
            val fetchedAt = clock.instant()
            val localAccountId = localAccountIdProvider.nextId()
            val providerAccountId = payload.accountId
                ?.takeIf { it.isNotBlank() }
                ?.let(::ProviderAccountId)
            val displayName = payload.accountEmail
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: defaultDisplayName
            val account = ProviderAccount.createNew(
                localAccountId = localAccountId,
                providerId = CODEX_PROVIDER_ID,
                providerAccountId = providerAccountId,
                displayName = displayName,
                now = fetchedAt,
            )
            val envelope = sessionEnvelopeFactory.create(
                payload = payload,
                localAccountId = localAccountId,
                providerAccountId = providerAccountId,
                now = fetchedAt,
            )
            val snapshot = mapper.map(
                dto = usageResult.dto,
                localAccountId = localAccountId,
                providerAccountId = providerAccountId,
                fetchedAt = fetchedAt,
                source = source,
            )

            PrepareResult.Success(
                PreparedImport(
                    account = account,
                    displayNameSuggestion = displayName,
                    sessionEnvelope = envelope,
                    snapshot = snapshot,
                ),
            )
        } catch (exception: CancellationException) {
            throw exception
        } catch (_: Exception) {
            PrepareResult.Failure(
                message = PERSISTENCE_FAILURE_MESSAGE_KEY,
                quotaError = null,
            )
        }

    private suspend fun savePreparedImport(preparedImport: PreparedImport): Result =
        try {
            val committed = importPersistence.save(
                account = preparedImport.account,
                sessionEnvelope = preparedImport.sessionEnvelope,
                snapshot = preparedImport.snapshot,
            )

            Result.Success(
                account = committed.account,
                displayNameSuggestion = preparedImport.displayNameSuggestion,
                sessionEnvelopeReference = committed.sessionEnvelope.toReference(),
                snapshot = committed.snapshot,
            )
        } catch (exception: CancellationException) {
            throw exception
        } catch (_: Exception) {
            Result.Failure(
                message = PERSISTENCE_FAILURE_MESSAGE_KEY,
                quotaError = null,
            )
        }

    fun interface ImportPersistence {
        /**
         * Saves a validated import atomically and returns the actual committed records.
         * Implementations that reconcile an existing provider account must canonicalize
         * every local-account-derived field, including snapshot IDs.
         */
        suspend fun save(
            account: ProviderAccount,
            sessionEnvelope: ProviderSessionEnvelope,
            snapshot: QuotaSnapshot,
        ): CommittedImport
    }

    data class CommittedImport(
        val account: ProviderAccount,
        val sessionEnvelope: ProviderSessionEnvelope,
        val snapshot: QuotaSnapshot,
    )

    data class PreparedImport(
        val account: ProviderAccount,
        val displayNameSuggestion: String,
        val sessionEnvelope: ProviderSessionEnvelope,
        val snapshot: QuotaSnapshot,
    )

    sealed interface PrepareResult {
        data class Success(
            val preparedImport: PreparedImport,
        ) : PrepareResult

        data class Failure(
            val message: String,
            val quotaError: QuotaError?,
        ) : PrepareResult
    }

    fun interface UsageClient {
        suspend fun fetchUsage(
            accessToken: String,
            accountId: String?,
        ): CodexUsageClient.Result
    }

    fun interface SessionEnvelopeFactory {
        fun create(
            payload: CodexSessionPayload,
            localAccountId: LocalAccountId,
            providerAccountId: ProviderAccountId?,
            now: Instant,
        ): ProviderSessionEnvelope
    }

    fun interface LocalAccountIdProvider {
        fun nextId(): LocalAccountId
    }

    data class SessionEnvelopeReference(
        val providerId: String,
        val localAccountId: String,
        val providerAccountId: String?,
    )

    sealed interface Result {
        data class Success(
            val account: ProviderAccount,
            val displayNameSuggestion: String,
            val sessionEnvelopeReference: SessionEnvelopeReference,
            val snapshot: QuotaSnapshot,
        ) : Result

        data class Failure(
            val message: String,
            val quotaError: QuotaError?,
        ) : Result
    }

    override suspend fun importFromApiKey(
        apiKey: String,
        account: ProviderAccount,
        apiBaseUrl: String?,
    ): kotlin.Result<QuotaSnapshot> =
        kotlin.Result.failure(UnsupportedOperationException("Codex does not support API key auth"))

    override suspend fun importFromCookie(cookieJson: String, account: ProviderAccount): kotlin.Result<QuotaSnapshot> =
        kotlin.Result.failure(UnsupportedOperationException("Codex does not support cookie auth"))

    override suspend fun importFromOAuthPkce(
        code: String,
        verifier: String,
        redirectUri: String,
        account: ProviderAccount,
    ): kotlin.Result<QuotaSnapshot> =
        kotlin.Result.failure(UnsupportedOperationException("Codex does not support OAuth PKCE auth"))

    private companion object {
        val CODEX_PROVIDER_ID = ProviderId("codex")
        const val PERSISTENCE_FAILURE_MESSAGE_KEY = "error_session_persistence"
        const val USAGE_VALIDATION_FAILURE_DIGEST = "codex_import_usage_validation_failed"
    }
}

private fun ProviderSessionEnvelope.toReference(): CodexSessionImporter.SessionEnvelopeReference =
    CodexSessionImporter.SessionEnvelopeReference(
        providerId = providerId,
        localAccountId = localAccountId,
        providerAccountId = providerAccountId,
    )
