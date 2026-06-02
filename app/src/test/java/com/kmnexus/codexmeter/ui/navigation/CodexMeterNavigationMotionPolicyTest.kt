package com.kmnexus.codexmeter.ui.navigation

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CodexMeterNavigationMotionPolicyTest {
    @Test
    fun `destinations keep page cascade behind a full screen backdrop cover`() {
        val source = sourceFile(
            "src/main/java/com/kmnexus/codexmeter/ui/navigation/CodexMeterNavHost.kt",
        ).readText()

        listOf(
            "Home" to "Home bottom tab",
            "Account" to "Account bottom tab",
            "Settings" to "Settings bottom tab",
        ).forEach { (routeName, label) ->
            val block = source.destinationBlock("CodexMeterRoute.$routeName.route")
            assertTrue(
                "$label must keep the page cascade instead of hard-switching content.",
                block.contains("CodexMeterDestination"),
            )
            assertTrue(
                "$label must pass Scaffold inner padding into the destination content, not " +
                    "into NavHost itself; otherwise the fixed cover starts below the status bar.",
                block.contains("contentPadding = innerPadding"),
            )
        }

        val navHost = source.functionCallBlock("NavHost(")
        assertFalse(
            "NavHost must fill the full window. Padding the NavHost restarts the fixed backdrop " +
                "inside the content area and creates a visible status-bar seam.",
            navHost.contains(".padding(innerPadding)"),
        )

        val wrapper = source.functionBlock("private fun CodexMeterDestination")
        val backdropIndex = wrapper.indexOf("CodexMeterBackdrop")
        val cascadeIndex = wrapper.indexOf("CodexMeterPageCascade")
        assertTrue(
            "A fixed backdrop cover must be drawn before the translated page cascade so the " +
                "previous destination cannot show through during the Y-offset entry motion.",
            backdropIndex >= 0 && cascadeIndex > backdropIndex,
        )
        assertTrue(
            "Only the cascade content should consume Scaffold inner padding; the fixed backdrop " +
                "must remain full-screen to match the root background coordinates.",
            wrapper.contains(".padding(contentPadding)"),
        )
    }

    @Test
    fun `nav host disables compose navigation destination transitions`() {
        val source = sourceFile(
            "src/main/java/com/kmnexus/codexmeter/ui/navigation/CodexMeterNavHost.kt",
        ).readText()

        assertTrue(source.contains("enterTransition = { EnterTransition.None }"))
        assertTrue(source.contains("exitTransition = { ExitTransition.None }"))
        assertTrue(source.contains("popEnterTransition = { EnterTransition.None }"))
        assertTrue(source.contains("popExitTransition = { ExitTransition.None }"))
    }

    private fun String.destinationBlock(routeExpression: String): String {
        val start = indexOf("composable($routeExpression)")
        require(start >= 0) { "Cannot find destination for $routeExpression" }
        val next = indexOf("\n            composable(", start + 1).takeIf { it >= 0 } ?: length
        return substring(start, next)
    }

    private fun String.functionCallBlock(signature: String): String {
        val start = indexOf(signature)
        require(start >= 0) { "Cannot find call $signature" }
        val next = indexOf("\n        ) {", start + 1).takeIf { it >= 0 } ?: length
        return substring(start, next)
    }

    private fun String.functionBlock(signature: String): String {
        val start = indexOf(signature)
        require(start >= 0) { "Cannot find function $signature" }
        val nextPrivateFunction = indexOf("\n@Composable\nprivate fun ", start + 1).takeIf { it >= 0 } ?: length
        return substring(start, nextPrivateFunction)
    }

    private fun sourceFile(path: String): File {
        val moduleFile = File(path)
        if (moduleFile.exists()) return moduleFile

        val rootFile = File("app", path)
        if (rootFile.exists()) return rootFile

        error("Cannot locate $path from ${File(".").absolutePath}")
    }
}
