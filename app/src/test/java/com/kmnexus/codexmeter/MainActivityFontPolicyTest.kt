package com.kmnexus.codexmeter

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MainActivityFontPolicyTest {
    @Test
    fun `main activity uses mono focus font scheme directly`() {
        val source = sourceFile("src/main/java/com/kmnexus/codexmeter/MainActivity.kt").readText()

        assertTrue(
            "CodexMeter should be fixed to Mono Focus in the CodexMeterTheme call.",
            source.contains("CodexMeterTheme(themeMode = themeMode, fontScheme = CodexMeterFontScheme.MonoFocusGeistMono)"),
        )
        assertFalse(
            "Runtime font preference collection must not override the fixed Mono Focus scheme.",
            source.contains("fontSchemePreferenceFlow()") || source.contains("toCodexMeterFontScheme"),
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
