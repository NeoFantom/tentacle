package dev.tentacle

import dev.tentacle.codes.CodeCaptureLog
import dev.tentacle.codes.NotificationCodeText
import dev.tentacle.core.CodeStore
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class NotificationCodeTextTest {
    @Test fun `empty and redacted bodies never parse a code from the title`() {
        val store = CodeStore(initialEnabled = true)
        val log = mutableListOf<String>()
        val title = "Verification code 123456"
        for (parts in listOf(emptyList(), listOf("", " \n"),
            listOf("Sensitive notification content hidden"),
            listOf(" ", "Sensitive notification content hidden", "Sensitive notification content hidden"))) {
            NotificationCodeText.combine(title, parts)?.let {
                CodeCaptureLog.capture(store, it, "Messages", "notification", log::add)
            }
        }
        assertTrue(store.listAfter(0).isEmpty())
        assertTrue(log.isEmpty())
    }

    @Test fun `visible body and title retain sender context and remove repeated notification fields`() {
        val text = NotificationCodeText.combine("[Example]", listOf("Your code is AB12CD", "Your code is AB12CD", ""))
        assertEquals("[Example]\nYour code is AB12CD", text)
        val store = CodeStore(initialEnabled = true)
        CodeCaptureLog.capture(store, text!!, "Messages", "notification") {}
        assertEquals("AB12CD", store.latest()!!.code)
        assertEquals("Example", store.latest()!!.sender)
    }

    @Test fun `redacted fields are excluded when expanded text is available`() {
        assertEquals("Messages\nYour code is 654321", NotificationCodeText.combine("Messages",
            listOf("Sensitive notification content hidden", "Your code is 654321")))
    }
}
