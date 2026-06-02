package com.kmnexus.codexmeter.ui.settings

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Test

class SettingsLanguageEntryPolicyTest {
    @Test
    fun `settings screen does not expose in app language switcher`() {
        val source = sourceFile("src/main/java/com/kmnexus/codexmeter/ui/settings/SettingsScreen.kt").readText()

        assertFalse(
            "CodexMeter follows the system language; Settings must not show an in-app language card.",
            source.contains("LanguageCard("),
        )
        assertFalse(
            "CodexMeter follows the system language; Settings must not open a language choice dialog.",
            source.contains("SettingsChoiceDialog.Language"),
        )
        assertFalse(
            "The Language section header should be removed together with the in-app switcher.",
            source.contains("settings_group_language"),
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
