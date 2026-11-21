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

    @Test fun `local address labels do not claim the computer shares the phone network`() {
        val english = strings("values")
        val chinese = strings("values-zh-rCN")

        assertEquals("Phone local network", english["way_lan"])
        assertEquals("手机本地网络", chinese["way_lan"])
        assertEquals("File server running", english["status_ready_title"])
        assertEquals("文件服务已运行", chinese["status_ready_title"])
        assertTrue(english.getValue("way_lan_note").contains("not checked"))
        assertTrue(english.getValue("way_lan_note").contains("same network"))
        assertTrue(chinese.getValue("way_lan_note").contains("未验证"))
        assertTrue(chinese.getValue("way_lan_note").contains("同一网络"))
    }
}
