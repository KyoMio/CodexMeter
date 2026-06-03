package com.kmnexus.codexmeter.app

import com.kmnexus.codexmeter.domain.model.LocalAccountId
import com.kmnexus.codexmeter.domain.model.ProviderId
import com.kmnexus.codexmeter.domain.model.QuotaWindowId
import com.kmnexus.codexmeter.domain.settings.AccountQuotaAlertKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsAccountAlertConfigBuilderTest {

    private fun key(provider: String, account: String, window: String) =
        AccountQuotaAlertKey(ProviderId(provider), LocalAccountId(account), QuotaWindowId(window))

    @Test
    fun `safeHash is deterministic 12-char lowercase hex and never echoes raw input`() {
        val raw = "local-account-secret-id"
        val hash = raw.diagnosticsSafeHash()
        assertEquals(12, hash.length)
        assertTrue(hash.matches(Regex("[0-9a-f]{12}")))
        assertEquals(hash, raw.diagnosticsSafeHash())
        assertFalse(raw.contains(hash))
    }

    @Test
    fun `safeHash differs for different inputs`() {
        assertFalse("account-a".diagnosticsSafeHash() == "account-b".diagnosticsSafeHash())
    }

    @Test
    fun `disabled alert settings are excluded`() {
        val configs = buildDiagnosticsAccountAlertConfigs(
            mapOf(
                key("codex", "acct-1", "five_hour") to true,
                key("codex", "acct-1", "weekly") to false,
            ),
        )
        assertEquals(1, configs.size)
        assertEquals(listOf("five_hour"), configs.single().enabledWindowIds)
    }

    @Test
    fun `windows for same account are grouped and sorted`() {
        val configs = buildDiagnosticsAccountAlertConfigs(
            mapOf(
                key("codex", "acct-1", "weekly") to true,
                key("codex", "acct-1", "five_hour") to true,
            ),
        )
        assertEquals(1, configs.size)
        assertEquals(listOf("five_hour", "weekly"), configs.single().enabledWindowIds)
    }

    @Test
    fun `provider is carried and account id is hashed not raw`() {
        val configs = buildDiagnosticsAccountAlertConfigs(
            mapOf(key("codex", "raw-account-id", "five_hour") to true),
        )
        val config = configs.single()
        assertEquals("codex", config.providerId)
        assertTrue(config.accountIdHash.startsWith("sha256:"))
        assertFalse(config.accountIdHash.contains("raw-account-id"))
    }

    @Test
    fun `configs are ordered deterministically across accounts and providers`() {
        val configs = buildDiagnosticsAccountAlertConfigs(
            mapOf(
                key("deepseek", "z-acct", "quota") to true,
                key("codex", "a-acct", "weekly") to true,
                key("codex", "a-acct", "five_hour") to true,
            ),
        )
        // same input must always yield the same order
        val again = buildDiagnosticsAccountAlertConfigs(
            mapOf(
                key("codex", "a-acct", "five_hour") to true,
                key("deepseek", "z-acct", "quota") to true,
                key("codex", "a-acct", "weekly") to true,
            ),
        )
        assertEquals(configs.map { it.providerId to it.enabledWindowIds }, again.map { it.providerId to it.enabledWindowIds })
        assertEquals(2, configs.size)
    }
}
