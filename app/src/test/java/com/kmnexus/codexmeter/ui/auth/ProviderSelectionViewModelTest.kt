package com.kmnexus.codexmeter.ui.auth

import com.kmnexus.codexmeter.domain.model.ProviderId
import com.kmnexus.codexmeter.providers.ProviderAuthKind
import com.kmnexus.codexmeter.providers.ProviderRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderSelectionViewModelTest {
    @Test
    fun `providerSelection contains Codex and DeepSeek`() {
        val providers = ProviderRegistry.all
        assertTrue(providers.any { it.providerId.value == "codex" })
        assertTrue(providers.any { it.providerId.value == "deepseek" })
    }

    @Test
    fun `providerSelection Codex uses OAuthWebView auth`() {
        val codex = ProviderRegistry.all.find { it.providerId.value == "codex" }
        assertNotNull(codex)
        assertEquals(ProviderAuthKind.OAuthWebView, codex!!.authKind)
        assertEquals("Codex", codex.displayName)
    }

    @Test
    fun `providerSelection DeepSeek uses ApiKeyImport auth`() {
        val deepseek = ProviderRegistry.all.find { it.providerId.value == "deepseek" }
        assertNotNull(deepseek)
        assertEquals(ProviderAuthKind.ApiKeyImport, deepseek!!.authKind)
        assertTrue(deepseek.supportsBalance)
    }

    @Test
    fun `entry mode from route value parses login correctly`() {
        val mode = AddAccountEntryMode.fromRouteValue("login")
        assertTrue(mode is AddAccountEntryMode.LoginToCodex)
    }

    @Test
    fun `entry mode from route value parses apikey with provider`() {
        val mode = AddAccountEntryMode.fromRouteValue("apikey:deepseek")
        assertTrue(mode is AddAccountEntryMode.ApiKeyInput)
        assertEquals(ProviderId("deepseek"), (mode as AddAccountEntryMode.ApiKeyInput).providerId)
    }

    @Test
    fun `entry mode from route value falls back to ProviderSelection`() {
        val mode = AddAccountEntryMode.fromRouteValue(null)
        assertTrue(mode is AddAccountEntryMode.ProviderSelection)

        val mode2 = AddAccountEntryMode.fromRouteValue("unknown")
        assertTrue(mode2 is AddAccountEntryMode.ProviderSelection)
    }

    @Test
    fun `entry mode Choose alias equals ProviderSelection`() {
        val choose = AddAccountEntryMode.Choose
        assertTrue(choose is AddAccountEntryMode.ProviderSelection)
    }
}
