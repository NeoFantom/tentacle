package dev.tentacle

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class StringsTest {
    private fun strings(folder: String): Map<String, String> {
        val factory = DocumentBuilderFactory.newInstance()
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        val nodes = factory.newDocumentBuilder().parse(File("src/main/res/$folder/strings.xml"))
            .getElementsByTagName("string")
        val result = linkedMapOf<String, String>()
        for (index in 0 until nodes.length) {
            val element = nodes.item(index) as org.w3c.dom.Element
            val key = element.getAttribute("name")
            assertFalse(element.textContent.isBlank(), "$folder/$key is empty")
            assertNull(result.put(key, element.textContent), "$folder/$key is duplicated")
        }
        return result
    }

    @Test fun `English and Simplified Chinese resources are complete`() {
        assertEquals(strings("values").keys, strings("values-zh-rCN").keys)
    }
}
