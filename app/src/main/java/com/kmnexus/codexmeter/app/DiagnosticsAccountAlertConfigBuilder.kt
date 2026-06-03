package com.kmnexus.codexmeter.app

import com.kmnexus.codexmeter.domain.settings.AccountQuotaAlertKey
import com.kmnexus.codexmeter.ui.settings.DiagnosticsAccountAlertConfig
import java.security.MessageDigest

/** SHA-256 → first 12 lowercase hex chars. The diagnostics account-id de-identification boundary. */
internal fun String.diagnosticsSafeHash(): String {
    val bytes = MessageDigest.getInstance("SHA-256").digest(toByteArray())
    return bytes.joinToString(separator = "") { byte -> "%02x".format(byte) }.take(12)
}

/**
 * Pure grouping of per-account quota-alert settings into diagnostics rows.
 * Only enabled windows are included; account ids are hashed; window and row order are
 * deterministic so two copies of the same state produce identical text.
 */
internal fun buildDiagnosticsAccountAlertConfigs(
    settings: Map<AccountQuotaAlertKey, Boolean>,
): List<DiagnosticsAccountAlertConfig> =
    settings.filterValues { it }
        .keys
        .groupBy { it.providerId.value to it.localAccountId.value }
        .map { (account, keys) ->
            DiagnosticsAccountAlertConfig(
                providerId = account.first,
                accountIdHash = "sha256:${account.second.diagnosticsSafeHash()}",
                enabledWindowIds = keys.map { it.windowId.value }.sorted(),
            )
        }
        .sortedWith(compareBy({ it.providerId }, { it.accountIdHash }))
