package dev.tentacle.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class JsonParserTest {
    @Test
    fun `parser handles every JSON value and surrogate pair escapes`() {
        val parsed = Json.parse(
            """{"array":[null,true,false,-12,1.5,6e2,"line\n\uD83D\uDE00"],"empty":{}}""",
        ) as Map<*, *>
        assertEquals(listOf(null, true, false, -12L, 1.5, 600.0, "line\n😀"), parsed["array"])
        assertEquals(emptyMap<String, Any?>(), parsed["empty"])
    }

    @Test
    fun `parser rejects malformed JSON strings numbers literals and duplicate keys`() {
        listOf(
            "", "+1", "01", "1.", "1e", "NaN", "tru", "true false",
            "[1,]", "{\"a\":1,}", "{\"a\":1,\"a\":2}",
            "\"line\n\"", "\"\\x\"", "\"\\u12xz\"", "\"\\uD800\"", "\"\\uDC00\"",
            "\"${'\uD800'}\"", "١", "\"\\u１２３４\"",
        ).forEach { input ->
            assertThrows(Json.ParseException::class.java, { Json.parse(input) }, input)
        }
    }

    @Test
    fun `parser bounds input and nesting before exhausting the stack`() {
        val deep = "[".repeat(Json.MAX_DEPTH + 2) + "0" + "]".repeat(Json.MAX_DEPTH + 2)
        assertThrows(Json.ParseException::class.java) { Json.parse(deep) }
        val large = " ".repeat(Json.MAX_INPUT_CHARS + 1)
        assertThrows(Json.ParseException::class.java) { Json.parse(large) }

        val acceptable = "[".repeat(Json.MAX_DEPTH) + "0" + "]".repeat(Json.MAX_DEPTH)
        assertTrue(Json.parse(acceptable) is List<*>)
    }
}
