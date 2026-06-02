package com.kmnexus.codexmeter.ui.settings

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Test

class SettingsHiddenEntryPolicyTest {
    @Test
    fun `settings hides temporary font scheme picker`() {
        val settingsScreen = sourceFile("src/main/java/com/kmnexus/codexmeter/ui/settings/SettingsScreen.kt").readText()
        val settingsCards = sourceFile("src/main/java/com/kmnexus/codexmeter/ui/settings/SettingsCards.kt").readText()

        assertFalse(
            "Font scheme is fixed to Mono Focus; Settings must not expose a font scheme choice dialog.",
            settingsScreen.contains("SettingsChoiceDialog.FontScheme"),
        )
        assertFalse(
            "Font scheme is fixed to Mono Focus; DisplayCard must not show a font scheme row.",
            settingsCards.contains("settings_font_scheme_title"),
        )
    }

    @Test
    fun `background refresh card hides immediate check action`() {
        val settingsScreen = sourceFile("src/main/java/com/kmnexus/codexmeter/ui/settings/SettingsScreen.kt").readText()
        val settingsCards = sourceFile("src/main/java/com/kmnexus/codexmeter/ui/settings/SettingsCards.kt").readText()

        assertFalse(
            "Background refresh card should be status-only plus the background switch; immediate check belongs to diagnostics.",
            settingsCards.contains("settings_refresh_check_now") ||
                settingsCards.contains("settings_refresh_checking") ||
                settingsCards.contains("onImmediateCheckClick"),
        )
        assertFalse(
            "SettingsScreen should not wire an immediate check action into RefreshCard.",
            settingsScreen.contains("RefreshCard(uiState.refresh, onBackgroundRefreshChanged, onImmediateCheckClick)"),
        )
    }

    private fun sourceFile(path: String): File {
        val moduleFile = File(path)
        if (moduleFile.exists()) return moduleFile

        val rootFile = File("app", path)
        if (rootFile.exists()) return rootFile

        error("Cannot locate $path from ${File(".").absolutePath}")
    }
}
