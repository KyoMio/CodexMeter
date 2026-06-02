package com.kmnexus.codexmeter.providers

import com.kmnexus.codexmeter.domain.model.ProviderId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderRegistryTest {
    @Test
    fun all_containsCodex() {
        val codex = ProviderRegistry.all.find { it.providerId == ProviderId("codex") }
        assertNotNull(codex)
        assertTrue(codex!!.isDefault)
    }

    @Test
    fun configFor_returnsCorrectProvider() {
        val config = ProviderRegistry.configFor(ProviderId("codex"))
        assertEquals("Codex", config.displayName)
        assertEquals(ProviderAuthKind.OAuthWebView, config.authKind)
    }

    @Test
    fun defaultProvider_isCodex() {
        assertEquals(ProviderId("codex"), ProviderRegistry.defaultProvider().providerId)
    }

    @Test
    fun deepseekProvider_hasBalanceSupport() {
        val ds = ProviderRegistry.configFor(ProviderId("deepseek"))
        assertTrue(ds.supportsBalance)
        assertEquals(ProviderAuthKind.ApiKeyImport, ds.authKind)
    }
}
