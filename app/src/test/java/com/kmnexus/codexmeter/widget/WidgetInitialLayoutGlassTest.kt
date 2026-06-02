package com.kmnexus.codexmeter.widget

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetInitialLayoutGlassTest {
    @Test
    fun `initial widget background uses a faint attached contact shadow`() {
        val layerList = drawableXml()
        val firstSolid = layerList.getElementsByTagName("solid").item(0) as org.w3c.dom.Element
        val color = firstSolid.androidAttribute("color")

        assertTrue("initial widget shadow should stay faint", color.alphaChannel() in 0x08..0x14)
    }

    @Test
    fun `initial widget background uses a single hairline glass stroke`() {
        val layerList = drawableXml()
        val strokes = layerList.getElementsByTagName("stroke")

        assertTrue("initial widget background should not stack thick dual borders", strokes.length == 1)
        val stroke = strokes.item(0) as org.w3c.dom.Element
        assertTrue(stroke.androidAttribute("color").alphaChannel() in 0x24..0x40)
    }

    private fun drawableXml() =
        DocumentBuilderFactory.newInstance()
            .apply { isNamespaceAware = true }
            .newDocumentBuilder()
            .parse(resourceFile("src/main/res/drawable/bg_widget_loading_glass.xml"))
            .documentElement

    private fun String.alphaChannel(): Int {
        val normalized = removePrefix("#")
        return when (normalized.length) {
            8 -> normalized.substring(0, 2).toInt(16)
            6 -> 0xFF
            else -> 0
        }
    }

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
