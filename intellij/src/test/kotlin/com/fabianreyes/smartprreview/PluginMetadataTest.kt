package com.fabianreyes.smartprreview

import org.junit.Assert.*
import org.junit.Test
import javax.xml.parsers.DocumentBuilderFactory

class PluginMetadataTest {
    @Test
    fun `distribution includes the Marketplace metadata and an SVG icon`() {
        val parser = DocumentBuilderFactory.newInstance().newDocumentBuilder()
        val xml = javaClass.getResourceAsStream("/META-INF/plugin.xml")!!.use { parser.parse(it) }
        fun text(name: String) = xml.getElementsByTagName(name).item(0).textContent
        assertEquals("com.fabianreyes.smartprreview", text("id"))
        assertEquals("Smart PR Review", text("name"))
        assertTrue(text("description").contains("Android Studio"))
        assertTrue(text("change-notes").contains("Initial release"))
        assertTrue(xml.getElementsByTagName("vendor").item(0).attributes.getNamedItem("url").nodeValue.startsWith("https://"))
        assertEquals("fabrey.bco@gmail.com", xml.getElementsByTagName("vendor").item(0).attributes.getNamedItem("email").nodeValue)
        assertFalse(xml.documentElement.textContent.contains("PreviewStartup"))
        val icon = javaClass.getResourceAsStream("/META-INF/pluginIcon.svg")!!.use { parser.parse(it) }.documentElement
        assertEquals("svg", icon.nodeName)
        assertEquals("40", icon.getAttribute("width"))
        assertEquals("40", icon.getAttribute("height"))
        assertTrue(javaClass.getResourceAsStream("/META-INF/LICENSE")!!.bufferedReader().use { it.readText() }.startsWith("MIT License"))
    }
}
