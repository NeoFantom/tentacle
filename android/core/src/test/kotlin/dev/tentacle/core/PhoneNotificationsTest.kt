package dev.tentacle.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PhoneNotificationsTest {
    @Test
    fun `filter rejects own ongoing foreground summaries and excluded packages`() {
        val normal = input()
        assertTrue(PhoneNotifications.shouldCapture(normal))
        assertFalse(PhoneNotifications.shouldCapture(normal.copy(packageName = "dev.tentacle")))
        assertFalse(PhoneNotifications.shouldCapture(normal.copy(ongoing = true)))
        assertFalse(PhoneNotifications.shouldCapture(normal.copy(foregroundService = true)))
        assertFalse(PhoneNotifications.shouldCapture(normal.copy(groupSummary = true), includeOngoing = true))
        assertFalse(PhoneNotifications.shouldCapture(normal, excludedPackages = setOf("example.chat")))
        assertTrue(PhoneNotifications.shouldCapture(normal.copy(ongoing = true), includeOngoing = true))
        assertTrue(PhoneNotifications.shouldCapture(normal.copy(foregroundService = true), includeOngoing = true))
    }

    @Test
    fun `text extraction prefers last message then big text lines and plain text`() {
        assertEquals("new message", PhoneNotifications.extractText(input(
            text = "plain", bigText = "expanded", textLines = listOf("line"),
            messages = listOf("old message", " ", "new message"),
        )))
        assertEquals("expanded", PhoneNotifications.extractText(input(
            text = "plain", bigText = "expanded", textLines = listOf("line"),
        )))
        assertEquals("first\nsecond", PhoneNotifications.extractText(input(
            text = "plain", textLines = listOf("first", " ", "second"),
        )))
        assertEquals("plain", PhoneNotifications.extractText(input(text = "plain")))
    }

    @Test
    fun `payment classification checks every alternate text source and scrubs visible fields`() {
        val sources = listOf<(PhoneNotificationInput) -> PhoneNotificationInput>(
            { it.copy(title = "支付验证码 1234") },
            { it.copy(text = "payment code 1234") },
            { it.copy(bigText = "转账验证码 1234") },
            { it.copy(textLines = listOf("purchase code 1234")) },
            { it.copy(messages = listOf("withdrawal code 1234")) },
            { it.copy(subText = "付款验证码 1234") },
        )
        sources.forEach { alter ->
            assertTrue(PhoneNotifications.isPaymentMessage(alter(input())))
        }

        val entry = PhoneNotifications.normalizedEntry(
            input(title = "Bank 1234", text = "ordinary preview", bigText = "支付验证码 1234", subText = "1234"),
            updatedMs = 10,
        )
        assertEquals("", entry.title)
        assertEquals(PhoneNotifications.HIDDEN_PAYMENT_TEXT, entry.text)
        assertEquals("", entry.subText)
    }

    @Test
    fun `entry JSON uses the public snake case contract including null fields`() {
        val entry = PhoneNotifications.normalizedEntry(input(title = "Title", text = "Body"), 123)
        assertEquals(
            setOf(
                "key", "package", "app", "title", "text", "sub_text", "category",
                "posted_ms", "ongoing", "removed_ms", "updated_ms",
            ),
            entry.toJson().keys,
        )
        assertEquals("example.chat", entry.toJson()["package"])
        assertEquals("", entry.toJson()["category"])
        assertEquals(null, entry.toJson()["removed_ms"])
    }

    private fun input(
        title: String = "",
        text: String = "",
        bigText: String = "",
        textLines: List<String> = emptyList(),
        messages: List<String> = emptyList(),
        subText: String = "",
    ) = PhoneNotificationInput(
        key = "key", packageName = "example.chat", appLabel = "Chat",
        title = title, text = text, bigText = bigText, textLines = textLines,
        messages = messages, subText = subText, postTimeMs = 1_000,
    )
}
