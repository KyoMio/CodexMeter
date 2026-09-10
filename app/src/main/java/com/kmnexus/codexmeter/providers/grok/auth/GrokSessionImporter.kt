package com.kmnexus.codexmeter.providers.grok.auth

import com.kmnexus.codexmeter.data.secure.ProviderSessionEnvelope
import com.kmnexus.codexmeter.domain.model.LocalAccountId
import com.kmnexus.codexmeter.domain.model.ProviderAccount
import com.kmnexus.codexmeter.domain.model.ProviderAccountId
import com.kmnexus.codexmeter.domain.model.ProviderId
import com.kmnexus.codexmeter.domain.quota.QuotaSnapshot
import com.kmnexus.codexmeter.domain.quota.QuotaSnapshotSource
import com.kmnexus.codexmeter.domain.refresh.QuotaError
import com.kmnexus.codexmeter.providers.SessionImporter
import com.kmnexus.codexmeter.providers.grok.mapper.GrokMapper
import com.kmnexus.codexmeter.providers.grok.network.GrokBillingClient
import com.kmnexus.codexmeter.providers.grok.session.GrokSessionPayload
import java.time.Clock
import java.time.Instant
import kotlinx.coroutines.CancellationException

/** Provider-account identity decoded from the id_token (fallback access_token) JWT claims. */
data class GrokLoginIdentity(
    val accountId: String?,
    val email: String?,
    val name: String?,
) {
    override fun toString(): String =
        "GrokLoginIdentity(" +
            "accountId=[REDACTED], " +
            "email=[REDACTED], " +
            "name=[REDACTED]" +
            ")"
}

/**
 * Two-phase device-code session import mirroring CodexSessionImporter: `prepareDeviceCodeSession`
 * only holds the freshly polled OAuth session (token + identity), and
 * `commitPreparedDeviceCodeSession` validates it through the official billing endpoint before
 * anything is encrypted or persisted. A billing failure leaves the store untouched.
 */
class GrokSessionImporter(
    private val billingClient: BillingClient,
    private val importPersistence: ImportPersistence,
    private val sessionEnvelopeFactory: SessionEnvelopeFactory,
    private val localAccountIdProvider: LocalAccountIdProvider,
    private val defaultDisplayName: String,
    private val clock: Clock,
) : SessionImporter {

    /** Caches the candidate session derived from a device-code token; no I/O happens here. */
    suspend fun prepareDeviceCodeSession(
        oauthToken: GrokOAuthToken,
        identity: GrokLoginIdentity,
        tokenEndpoint: String?,
    ): PreparedImport =
        PreparedImport(
            session = GrokSessionPayload(
                accessToken = oauthToken.accessToken,
                refreshToken = oauthToken.refreshToken,
                idToken = oauthToken.idToken,
                tokenExpiresAtEpochSeconds = oauthToken.expiresAtEpochSeconds,
                tokenEndpoint = tokenEndpoint,
                accountId = identity.accountId?.takeIf { it.isNotBlank() },
                email = identity.email?.takeIf { it.isNotBlank() },
                lastRefreshEpochSeconds = clock.instant().epochSecond,
            ),
        )

    /**
     * Validates the prepared session against the billing endpoint, then persists account, envelope
     * and first snapshot atomically. Nothing is written when validation or persistence fails.
     */
    suspend fun commitPreparedDeviceCodeSession(preparedImport: PreparedImport): Result {
        val billingResult = try {
            billingClient.fetchBilling(preparedImport.session.accessToken)
        } catch (exception: CancellationException) {
            throw exception
        } catch (_: Exception) {
            val error = QuotaError.Network(
                diagnosticsDigest = BILLING_VALIDATION_FAILURE_DIGEST,
            )
            return Result.Failure(
                message = error.safeMessageKey,
                quotaError = error,
            )
        }

        return when (billingResult) {
            is GrokBillingClient.Result.Failure -> Result.Failure(
                message = billingResult.error.safeMessageKey,
                quotaError = billingResult.error,
            )
            is GrokBillingClient.Result.Success -> persistValidatedSession(
                preparedImport = preparedImport,
                billingResult = billingResult,
            )
        }
    }

    private suspend fun persistValidatedSession(
        preparedImport: PreparedImport,
        billingResult: GrokBillingClient.Result.Success,
    ): Result =
        try {
            val fetchedAt = clock.instant()
            val localAccountId = localAccountIdProvider.nextId()
            val providerAccountId = preparedImport.session.accountId
                ?.takeIf { it.isNotBlank() }
                ?.let(::ProviderAccountId)
            val displayName = preparedImport.session.email
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: defaultDisplayName
            val account = ProviderAccount.createNew(
                localAccountId = localAccountId,
                providerId = GROK_PROVIDER_ID,
                providerAccountId = providerAccountId,
                displayName = displayName,
                now = fetchedAt,
            )
            val envelope = sessionEnvelopeFactory.create(
                payload = preparedImport.session,
                localAccountId = localAccountId,
                providerAccountId = providerAccountId,
                now = fetchedAt,
            )
            val snapshot = GrokMapper.map(
                dto = billingResult.dto,
                localAccountId = localAccountId,
                providerAccountId = providerAccountId,
                fetchedAt = fetchedAt,
                source = QuotaSnapshotSource.DeviceCodeLogin,
            )

            val committed = importPersistence.save(
                account = account,
                sessionEnvelope = envelope,
                snapshot = snapshot,
            )

            Result.Success(
                account = committed.account,
                displayNameSuggestion = displayName,
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

    fun interface BillingClient {
        suspend fun fetchBilling(accessToken: String): GrokBillingClient.Result
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

    fun interface SessionEnvelopeFactory {
        fun create(
            payload: GrokSessionPayload,
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

    data class PreparedImport(
        val session: GrokSessionPayload,
    ) {
        override fun toString(): String = "PreparedImport(session=[REDACTED])"
    }

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
        kotlin.Result.failure(UnsupportedOperationException("Grok does not support API key auth"))

    override suspend fun importFromCookie(cookieJson: String, account: ProviderAccount): kotlin.Result<QuotaSnapshot> =
        kotlin.Result.failure(UnsupportedOperationException("Grok does not support cookie auth"))

    override suspend fun importFromOAuthPkce(
        code: String,
        verifier: String,
        redirectUri: String,
        account: ProviderAccount,
    ): kotlin.Result<QuotaSnapshot> =
        kotlin.Result.failure(UnsupportedOperationException("Grok does not support OAuth PKCE auth"))

    private companion object {
        val GROK_PROVIDER_ID = ProviderId("grok")
        const val PERSISTENCE_FAILURE_MESSAGE_KEY = "error_session_persistence"
        const val BILLING_VALIDATION_FAILURE_DIGEST = "grok_import_billing_validation_failed"
    }
}

private fun ProviderSessionEnvelope.toReference(): GrokSessionImporter.SessionEnvelopeReference =
    GrokSessionImporter.SessionEnvelopeReference(
        providerId = providerId,
        localAccountId = localAccountId,
        providerAccountId = providerAccountId,
    )
