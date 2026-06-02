package com.kmnexus.codexmeter.widget

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Test

class WidgetProviderMetadataTest {
    @Test
    fun `widget provider allows resizing down to three by one and across to four by two`() {
        val provider = widgetProviderXml()

        assertEquals("180dp", provider.androidAttribute("minWidth"))
        assertEquals("40dp", provider.androidAttribute("minHeight"))
        assertEquals("horizontal|vertical", provider.androidAttribute("resizeMode"))
        assertEquals("3", provider.androidAttribute("targetCellWidth"))
        assertEquals("1", provider.androidAttribute("targetCellHeight"))
    }

    private fun widgetProviderXml() =
        DocumentBuilderFactory.newInstance()
            .apply { isNamespaceAware = true }
            .newDocumentBuilder()
            .parse(resourceFile("src/main/res/xml/codexmeter_widget_info.xml"))
            .documentElement

    private fun resourceFile(path: String): File {
        val moduleFile = File(path)
        if (moduleFile.exists()) return moduleFile

        val rootFile = File("app", path)
        if (rootFile.exists()) return rootFile

        error("Cannot locate $path from ${File(".").absolutePath}")
    }

    private fun org.w3c.dom.Element.androidAttribute(name: String): String =
        getAttributeNS(ANDROID_NAMESPACE, name)

    private companion object {
        const val ANDROID_NAMESPACE = "http://schemas.android.com/apk/res/android"
    }
}
