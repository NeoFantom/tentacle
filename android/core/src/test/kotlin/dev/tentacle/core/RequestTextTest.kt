package dev.tentacle.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class RequestTextTest {
    @Test
    fun `all shared length vectors and CJK ranges match`() {
        val document = Json.parse(Files.readString(repoFile("docs/request-length-vectors.json"))) as Map<*, *>
        val expectedRanges = (document["cjk_ranges"] as List<*>).map { value ->
            val pair = value as List<*>
            parseHex(pair[0] as String)..parseHex(pair[1] as String)
        }
        assertEquals(expectedRanges, RequestText.CJK_RANGES)

        val vectors = document["vectors"] as List<*>
        vectors.forEachIndexed { index, value ->
            val vector = value as Map<*, *>
            assertEquals(
                (vector["units"] as Number).toInt(),
                RequestText.units(vector["text"] as String),
                "shared vector $index",
            )
        }
    }

    @Test
    fun `validator enforces emptiness units UTF-16 length and control characters`() {
        assertThrows(ApiException::class.java) { RequestText.validate("") }
        RequestText.validate("", allowEmpty = true)
        RequestText.validate("word ".repeat(RequestText.MAX_UNITS))
        assertBadRequest { RequestText.validate("word ".repeat(RequestText.MAX_UNITS + 1)) }
        RequestText.validate("x".repeat(RequestText.MAX_CHARS))
        assertBadRequest { RequestText.validate("x".repeat(RequestText.MAX_CHARS + 1)) }
        listOf('\u0000', '\u0008', '\r', '\u007f', '\u0085').forEach { control ->
            assertBadRequest { RequestText.validate("before${control}after") }
        }
        RequestText.validate("line one\nline two\tvalue")
        assertEquals(1, RequestText.units("Ⅻ")) // Nl: letter number.
        assertEquals(1, RequestText.units("²½")) // No: other numbers form one run.
    }

    private fun assertBadRequest(block: () -> Unit) {
        val error = assertThrows(ApiException::class.java, block)
        assertEquals(400, error.status)
        assertEquals("bad_request", error.code)
    }

    private fun parseHex(value: String): Int = value.removePrefix("0x").toInt(16)

    private fun repoFile(relative: String): Path {
        var directory = Path.of(System.getProperty("user.dir")).toAbsolutePath()
        while (directory.parent != null && !Files.exists(directory.resolve("AGENTS.md"))) {
            directory = directory.parent
        }
        return directory.resolve(relative)
    }
}
