package com.kmnexus.codexmeter.ui

import com.kmnexus.codexmeter.providers.ProviderRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProviderPlanDisplayNameTest {
    @Test
    fun codexUsesPro20xMappingAndTitlecasesUnknowns() {
        assertEquals("Pro 20x", providerPlanDisplayName(ProviderRegistry.CODEX, "pro"))
        assertEquals("Pro 5x", providerPlanDisplayName(ProviderRegistry.CODEX, "prolite"))
        assertEquals("Plus", providerPlanDisplayName(ProviderRegistry.CODEX, "plus"))
        assertEquals("Free", providerPlanDisplayName(ProviderRegistry.CODEX, "free"))
    }

    @Test
    fun cursorBrandsMembershipType() {
        assertEquals("Cursor Pro", providerPlanDisplayName(ProviderRegistry.CURSOR, "pro"))
        assertEquals("Cursor Free", providerPlanDisplayName(ProviderRegistry.CURSOR, "free"))
        assertEquals("Cursor Enterprise", providerPlanDisplayName(ProviderRegistry.CURSOR, "enterprise"))
    }

    @Test
    fun zaiCapitalizesLevelWithoutCodexMapping() {
        // Regression: previously rendered "Pro 20x" by reusing the Codex mapping.
        assertEquals("Pro", providerPlanDisplayName(ProviderRegistry.ZAI, "pro"))
        assertEquals("Free", providerPlanDisplayName(ProviderRegistry.ZAI, "free"))
    }

    @Test
    fun miniMaxAndAntigravityShowApiValueVerbatim() {
        assertEquals("MiniMax Coding Pro", providerPlanDisplayName(ProviderRegistry.MINIMAX, "MiniMax Coding Pro"))
        assertEquals("Antigravity", providerPlanDisplayName(ProviderRegistry.ANTIGRAVITY, "Antigravity"))
    }

    @Test
    fun blankOrMissingPlanIsNull() {
        assertNull(providerPlanDisplayName(ProviderRegistry.KIMI, null))
        assertNull(providerPlanDisplayName(ProviderRegistry.ZAI, "   "))
    }
}
