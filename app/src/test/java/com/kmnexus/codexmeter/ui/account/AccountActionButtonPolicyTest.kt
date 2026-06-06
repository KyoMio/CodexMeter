package com.kmnexus.codexmeter.ui.account

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountActionButtonPolicyTest {
    @Test
    fun `account destructive and disabled actions keep visible outlines`() {
        val source = sourceFile("src/main/java/com/kmnexus/codexmeter/ui/account/AccountCards.kt").readText()
        val actions = source.functionBlock("private fun AccountActions")

        val setCurrent = actions.buttonBlock("account_set_current")
        assertTrue(
            "Disabled 'set current' must keep an explicit outline instead of relying on a Material disabled border.",
            setCurrent.contains("border = actionButtonBorder"),
        )

        val delete = actions.buttonBlock("account_delete")
        assertTrue(
            "Delete must be an outlined button so it visually matches the other account actions.",
            delete.contains("OutlinedButton("),
        )
        assertFalse(
            "Delete must not be a borderless text button.",
            delete.contains("TextButton("),
        )
        assertTrue(
            "Delete must keep an explicit outline while using danger-colored text.",
            delete.contains("border = actionButtonBorder"),
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
        val start = lastIndexOf("OutlinedButton(", label).takeIf { it >= 0 } ?: lastIndexOf("Button(", label)
        require(start >= 0) { "Cannot find button start for $labelToken" }
        val nextButton = indexOf("\n            ", label + labelToken.length).takeIf { it >= 0 } ?: length
        return substring(start, nextButton)
    }

    private fun sourceFile(path: String): File {
        val moduleFile = File(path)
        if (moduleFile.exists()) return moduleFile

        val rootFile = File("app", path)
        if (rootFile.exists()) return rootFile

        error("Cannot locate $path from ${File(".").absolutePath}")
    }
}
