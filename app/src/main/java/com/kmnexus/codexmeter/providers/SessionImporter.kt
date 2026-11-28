package com.kmnexus.codexmeter.providers

import com.kmnexus.codexmeter.data.secure.ProviderSessionEnvelope
import com.kmnexus.codexmeter.domain.model.ProviderAccount
import com.kmnexus.codexmeter.domain.quota.QuotaSnapshot

/**
 * A validated credential import: the quota snapshot plus the freshly encrypted session envelope
 * the caller must persist.
 *
 * CM-10 R4 ordering protocol: importers prepare the envelope (encrypt, build) but NEVER write it.
 * The envelope is a late-arriving write racing account deletion; it may only be committed inside
 * the shared account delete/commit boundary, after re-checking the account is still alive.
 * The caller ([com.kmnexus.codexmeter.domain.auth.SessionLoginUseCase]) owns that commit.
 */
data class ValidatedSessionImport(
    val snapshot: QuotaSnapshot,
    val sessionEnvelope: ProviderSessionEnvelope,
)

interface SessionImporter {
    /**
     * Validates the credential through the provider's usage API. On success returns the snapshot
     * plus the prepared (unsaved) session envelope — nothing is persisted here.
     *
     * @param apiBaseUrl region API base URL chosen by the user; null means use the provider default.
     *   Only region-aware API-key providers (z.ai, MiniMax) honour it; others ignore it.
     */
    suspend fun importFromApiKey(
        apiKey: String,
        account: ProviderAccount,
        apiBaseUrl: String? = null,
    ): Result<ValidatedSessionImport>
    suspend fun importFromCookie(cookieJson: String, account: ProviderAccount): Result<ValidatedSessionImport>
    suspend fun importFromOAuthPkce(
        code: String,
        verifier: String,
        redirectUri: String,
        account: ProviderAccount,
    ): Result<ValidatedSessionImport>
}
