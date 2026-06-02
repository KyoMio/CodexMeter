package com.kmnexus.codexmeter

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Test

class MainActivityLocalePolicyTest {
    @Test
    fun `main activity does not apply platform locale from compose language state`() {
        val source = sourceFile("src/main/java/com/kmnexus/codexmeter/MainActivity.kt").readText()

        assertFalse(
            "Applying LocaleManager from Compose state can recreate MainActivity before DataStore emits " +
                "the saved preference, causing a System-target locale loop.",
            source.contains("LaunchedEffect(languagePreference)"),
        )
        assertFalse(
            "MainActivity must not collect the old in-app language override; CodexMeter follows the " +
                "system language now.",
            source.contains("languagePreferenceFlow()"),
        )
        assertFalse(
            "MainActivity should use the normal Activity context; explicit in-app locale overrides were " +
                "removed.",
            source.contains("CodexMeterLocalizedContent("),
        )
        assertFalse(
            "MainActivity should not apply app locales directly.",
            source.contains("AppLocaleController.applyToApp(this@MainActivity"),
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
