package com.kmnexus.codexmeter.providers

import com.kmnexus.codexmeter.domain.model.ProviderAccount
import com.kmnexus.codexmeter.domain.model.ProviderId
import com.kmnexus.codexmeter.domain.quota.QuotaSnapshot

class SessionImportRouter(
    private val importers: Map<ProviderId, SessionImporter>,
) {
    suspend fun importApiKey(
        apiKey: String,
        account: ProviderAccount,
        apiBaseUrl: String? = null,
    ): Result<QuotaSnapshot> =
        resolve(account.providerId).importFromApiKey(apiKey, account, apiBaseUrl)

    suspend fun importCookie(cookieJson: String, account: ProviderAccount): Result<QuotaSnapshot> =
        resolve(account.providerId).importFromCookie(cookieJson, account)

    suspend fun importOAuthPkce(
        code: String,
        verifier: String,
        redirectUri: String,
        account: ProviderAccount,
    ): Result<QuotaSnapshot> =
        resolve(account.providerId).importFromOAuthPkce(code, verifier, redirectUri, account)

    private fun resolve(providerId: ProviderId): SessionImporter =
        importers[providerId] ?: throw NoSuchElementException("No SessionImporter for $providerId")
}
