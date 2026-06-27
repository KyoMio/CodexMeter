package com.kmnexus.codexmeter.providers.zaibalance

import com.kmnexus.codexmeter.data.secure.PayloadCipher
import com.kmnexus.codexmeter.data.secure.SecureSessionStore
import com.kmnexus.codexmeter.domain.model.ProviderAccount
import com.kmnexus.codexmeter.domain.model.ProviderId
import com.kmnexus.codexmeter.domain.quota.QuotaSnapshotSource
import com.kmnexus.codexmeter.domain.refresh.QuotaError
import com.kmnexus.codexmeter.domain.refresh.RefreshTrigger
import com.kmnexus.codexmeter.providers.zaibalance.mapper.ZaiBalanceMapper
import com.kmnexus.codexmeter.providers.zaibalance.network.ZaiBalanceClient
import com.kmnexus.codexmeter.providers.zaibalance.session.ZaiBalanceSessionPayload
import com.kmnexus.codexmeter.refresh.ProviderRefreshResult
import com.kmnexus.codexmeter.refresh.RefreshProvider
import java.time.Clock
import kotlinx.serialization.json.Json

class ZaiBalanceRefreshProvider(
    private val client: ZaiBalanceClient,
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
                QuotaError.AuthRequired(httpStatus = null, diagnosticsDigest = "zai_balance_session_missing"),
            )
        val session = try {
            json.decodeFromString<ZaiBalanceSessionPayload>(
                payloadCipher.decrypt(envelope.payloadCiphertext, envelope.payloadNonce).decodeToString(),
            )
        } catch (_: Exception) {
            return ProviderRefreshResult.Failure(
                QuotaError.AuthRequired(httpStatus = null, diagnosticsDigest = "zai_balance_session_decode_failed"),
            )
        }

        return when (val result = client.fetchBalance(session.apiKey)) {
            is ZaiBalanceClient.Result.Failure -> ProviderRefreshResult.Failure(result.error)
            is ZaiBalanceClient.Result.Success -> ProviderRefreshResult.Success(
                ZaiBalanceMapper.map(
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
        RefreshTrigger.ImportValidation -> QuotaSnapshotSource.ApiKeyImport
        RefreshTrigger.AccountSwitch -> QuotaSnapshotSource.ManualRefresh
        RefreshTrigger.Periodic -> QuotaSnapshotSource.BackgroundRefresh
    }

    companion object {
        val ZAI_BALANCE_PROVIDER_ID = ProviderId("zai_balance")
    }
}
