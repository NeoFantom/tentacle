package dev.tentacle.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Base64

class JsonAndTokensTest {
    @Test
    fun `json escapes strings and keeps non ASCII text`() {
        val value = linkedMapOf<String, Any?>(
            "text" to "微信\n\t\"\\\b\u000c\r\u0001",
            "values" to listOf(true, false, null, 12, 1.5),
        )

        assertEquals(
            "{\"text\":\"微信\\n\\t\\\"\\\\\\b\\f\\r\\u0001\",\"values\":[true,false,null,12,1.5]}",
            Json.stringify(value),
        )
    }

    @Test
    fun `generated tokens contain 32 random bytes in unpadded base64url`() {
        val first = Tokens.generate()
        val second = Tokens.generate()

        assertEquals(43, first.length)
        assertTrue(first.matches(Regex("[A-Za-z0-9_-]+")))
        assertFalse(first.contains('='))
        assertEquals(32, Base64.getUrlDecoder().decode(first).size)
        assertNotEquals(first, second)
    }
}
