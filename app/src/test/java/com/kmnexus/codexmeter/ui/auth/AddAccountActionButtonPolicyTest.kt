package com.kmnexus.codexmeter.ui.auth

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class AddAccountActionButtonPolicyTest {
    @Test
    fun `cancel login button matches primary button width`() {
        val source = sourceFile("src/main/java/com/kmnexus/codexmeter/ui/auth/AddAccountScreen.kt").readText()
        val actionRow = source.functionBlock("private fun AddAccountActionRow")
        val cancelBlock = actionRow.buttonBlock("add_account_cancel_login")

        assertTrue(
            "Cancel login must use the same full-width affordance as the primary start-login button.",
            cancelBlock.contains("modifier = Modifier.fillMaxWidth()"),
        )
    }

    private fun String.functionBlock(signature: String): String {
        val start = indexOf(signature)
        require(start >= 0) { "Cannot find function $signature" }
        val nextFunction = indexOf("\n@Composable\nprivate fun ", start + 1).takeIf { it >= 0 } ?: length
        return substring(start, nextFunction)
    }

    private fun String.buttonBlock(labelToken: String): String {
        val label = indexOf(labelToken)
        require(label >= 0) { "Cannot find button label token $labelToken" }
        val start = lastIndexOf("OutlinedButton(", label)
        require(start >= 0) { "Cannot find outlined button start for $labelToken" }
        val nextCase = indexOf("\n        DeviceCodeLoginUiStatus.ValidationFailed", label).takeIf { it >= 0 } ?: length
        return substring(start, nextCase)
    }

    private fun sourceFile(path: String): File {
        val moduleFile = File(path)
        if (moduleFile.exists()) return moduleFile

        val rootFile = File("app", path)
        if (rootFile.exists()) return rootFile

        error("Cannot locate $path from ${File(".").absolutePath}")
    }
}
