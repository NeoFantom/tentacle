package dev.tentacle

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class NotificationListenerManifestTest {
    @Test fun `one notification access component keeps the existing class name`() {
        val document = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(File("src/main/AndroidManifest.xml"))
        val services = document.getElementsByTagName("service")
        val listeners = buildList {
            for (index in 0 until services.length) {
                val service = services.item(index) as org.w3c.dom.Element
                val actions = service.getElementsByTagName("action")
                if ((0 until actions.length).any { actionIndex ->
                        (actions.item(actionIndex) as org.w3c.dom.Element).getAttributeNS(ANDROID_NAMESPACE, "name") ==
                            "android.service.notification.NotificationListenerService"
                    }) add(service.getAttributeNS(ANDROID_NAMESPACE, "name"))
            }
        }

        assertEquals(listOf(".codes.CodeNotificationListener"), listeners)
    }

    private companion object {
        const val ANDROID_NAMESPACE = "http://schemas.android.com/apk/res/android"
    }
}
