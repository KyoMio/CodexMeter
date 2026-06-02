package com.kmnexus.codexmeter.widget

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class WidgetConfigurationWindowPolicyTest {
    @Test
    fun `widget configuration activity uses compact floating dialog theme`() {
        val activity = manifestActivity(".widget.CodexMeterWidgetConfigurationActivity")

        assertEquals("true", activity.androidAttribute("exported"))
        assertEquals(
            "@style/Theme.CodexMeter.WidgetConfigurationDialog",
            activity.androidAttribute("theme"),
        )
        assertNotEquals("", activity.androidAttribute("theme"))
    }

    @Test
    fun `widget configuration dialog theme is floating and does not cover launcher`() {
        val style = style("Theme.CodexMeter.WidgetConfigurationDialog")

        assertEquals("@android:style/Theme.Material.Light.Dialog.NoActionBar", style.parent)
        assertEquals("true", style.item("android:windowIsFloating"))
        assertEquals("true", style.item("android:windowCloseOnTouchOutside"))
        assertEquals("true", style.item("android:backgroundDimEnabled"))
        assertEquals("@dimen/widget_config_dialog_width_major", style.item("android:windowMinWidthMajor"))
        assertEquals("@dimen/widget_config_dialog_width_minor", style.item("android:windowMinWidthMinor"))
    }

    private fun manifestActivity(name: String): org.w3c.dom.Element {
        val manifest = xml("src/main/AndroidManifest.xml").documentElement
        val activities = manifest.getElementsByTagName("activity")
        for (index in 0 until activities.length) {
            val element = activities.item(index) as org.w3c.dom.Element
            if (element.androidAttribute("name") == name) return element
        }
        error("Activity $name not found")
    }

    private fun style(name: String): StyleElement {
        val styles = xml("src/main/res/values/themes.xml").documentElement.getElementsByTagName("style")
        for (index in 0 until styles.length) {
            val element = styles.item(index) as org.w3c.dom.Element
            if (element.getAttribute("name") == name) return StyleElement(element)
        }
        error("Style $name not found")
    }

    private fun xml(path: String) =
        DocumentBuilderFactory.newInstance()
            .apply { isNamespaceAware = true }
            .newDocumentBuilder()
            .parse(resourceFile(path))

    private fun resourceFile(path: String): File {
        val moduleFile = File(path)
        if (moduleFile.exists()) return moduleFile

        val rootFile = File("app", path)
        if (rootFile.exists()) return rootFile

        error("Cannot locate $path from ${File(".").absolutePath}")
    }

    private fun org.w3c.dom.Element.androidAttribute(name: String): String =
        getAttributeNS(ANDROID_NAMESPACE, name)

    private class StyleElement(private val element: org.w3c.dom.Element) {
        val parent: String = element.getAttribute("parent")

        fun item(name: String): String {
            val items = element.getElementsByTagName("item")
            for (index in 0 until items.length) {
                val item = items.item(index) as org.w3c.dom.Element
                if (item.getAttribute("name") == name) return item.textContent.trim()
            }
            return ""
        }
    }

    private companion object {
        const val ANDROID_NAMESPACE = "http://schemas.android.com/apk/res/android"
    }
}
