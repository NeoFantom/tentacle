package dev.tentacle.core

/** Small JSON writer; strings remain Unicode and are encoded as UTF-8 by the transport. */
object Json {
    class ParseException(message: String) : Exception(message)

    const val MAX_INPUT_CHARS = 1_048_576
    const val MAX_DEPTH = 64

    fun stringify(value: Any?): String = buildString { writeValue(value) }

    @Throws(ParseException::class)
    fun parse(text: String): Any? {
        if (text.length > MAX_INPUT_CHARS) throw ParseException("JSON input is too large")
        return Parser(text).parse()
    }

    private fun StringBuilder.writeValue(value: Any?) {
        when (value) {
            null -> append("null")
            is String -> writeString(value)
            is Boolean, is Byte, is Short, is Int, is Long -> append(value)
            is Float -> { require(value.isFinite()); append(value) }
            is Double -> { require(value.isFinite()); append(value) }
            is Map<*, *> -> {
                append('{')
                value.entries.forEachIndexed { index, entry ->
                    require(entry.key is String) { "JSON object keys must be strings" }
                    if (index > 0) append(',')
                    writeString(entry.key as String)
                    append(':')
                    writeValue(entry.value)
                }
                append('}')
            }
            is Iterable<*> -> {
                append('[')
                value.forEachIndexed { index, item ->
                    if (index > 0) append(',')
                    writeValue(item)
                }
                append(']')
            }
            else -> throw IllegalArgumentException("Unsupported JSON value")
        }
    }

    private fun StringBuilder.writeString(value: String) {
        append('"')
        value.forEachIndexed { index, c ->
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '\u000c' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> {
                    val unpaired = when {
                        c.isHighSurrogate() -> index + 1 == value.length || !value[index + 1].isLowSurrogate()
                        c.isLowSurrogate() -> index == 0 || !value[index - 1].isHighSurrogate()
                        else -> false
                    }
                    if (c < ' ' || unpaired) append("\\u" + c.code.toString(16).padStart(4, '0'))
                    else append(c)
                }
            }
        }
        append('"')
    }

    private class Parser(private val text: String) {
        private var offset = 0

        fun parse(): Any? {
            skipWhitespace()
            val value = value(0)
            skipWhitespace()
            if (offset != text.length) fail("Unexpected trailing data")
            return value
        }

        private fun value(depth: Int): Any? {
            if (depth > MAX_DEPTH) fail("JSON is nested too deeply")
            if (offset >= text.length) fail("Expected a JSON value")
            return when (text[offset]) {
                '{' -> objectValue(depth)
                '[' -> arrayValue(depth)
                '"' -> stringValue()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                '-', in '0'..'9' -> numberValue()
                else -> fail("Expected a JSON value")
            }
        }

        private fun objectValue(depth: Int): Map<String, Any?> {
            offset++
            skipWhitespace()
            val result = linkedMapOf<String, Any?>()
            if (take('}')) return result
            while (true) {
                if (offset >= text.length || text[offset] != '"') fail("Expected an object key")
                val key = stringValue()
                skipWhitespace()
                expect(':')
                skipWhitespace()
                if (result.containsKey(key)) fail("Duplicate object key")
                result[key] = value(depth + 1)
                skipWhitespace()
                if (take('}')) return result
                expect(',')
                skipWhitespace()
            }
        }

        private fun arrayValue(depth: Int): List<Any?> {
            offset++
            skipWhitespace()
            val result = mutableListOf<Any?>()
            if (take(']')) return result
            while (true) {
                result += value(depth + 1)
                skipWhitespace()
                if (take(']')) return result
                expect(',')
                skipWhitespace()
            }
        }

        private fun stringValue(): String {
            expect('"')
            val result = StringBuilder()
            while (offset < text.length) {
                val c = text[offset++]
                when {
                    c == '"' -> return result.toString()
                    c == '\\' -> appendEscape(result)
                    c < ' ' -> fail("Control character in string")
                    c.isHighSurrogate() -> {
                        if (offset >= text.length || !text[offset].isLowSurrogate()) fail("Unpaired surrogate")
                        result.append(c).append(text[offset++])
                    }
                    c.isLowSurrogate() -> fail("Unpaired surrogate")
                    else -> result.append(c)
                }
            }
            fail("Unterminated string")
        }

        private fun appendEscape(result: StringBuilder) {
            if (offset >= text.length) fail("Unterminated escape")
            when (val escaped = text[offset++]) {
                '"', '\\', '/' -> result.append(escaped)
                'b' -> result.append('\b')
                'f' -> result.append('\u000c')
                'n' -> result.append('\n')
                'r' -> result.append('\r')
                't' -> result.append('\t')
                'u' -> {
                    val first = unicodeEscape()
                    when {
                        first.isHighSurrogate() -> {
                            if (offset + 1 >= text.length || text[offset] != '\\' || text[offset + 1] != 'u') {
                                fail("Unpaired surrogate escape")
                            }
                            offset += 2
                            val second = unicodeEscape()
                            if (!second.isLowSurrogate()) fail("Unpaired surrogate escape")
                            result.append(first).append(second)
                        }
                        first.isLowSurrogate() -> fail("Unpaired surrogate escape")
                        else -> result.append(first)
                    }
                }
                else -> fail("Invalid escape")
            }
        }

        private fun unicodeEscape(): Char {
            if (offset + 4 > text.length) fail("Incomplete Unicode escape")
            var value = 0
            repeat(4) {
                val digit = asciiHex(text[offset++]) ?: fail("Invalid Unicode escape")
                value = value * 16 + digit
            }
            return value.toChar()
        }

        private fun numberValue(): Number {
            val start = offset
            take('-')
            if (take('0')) {
                if (offset < text.length && text[offset] in '0'..'9') fail("Leading zero in number")
            } else {
                digits(required = true)
            }
            var decimal = false
            if (take('.')) {
                decimal = true
                digits(required = true)
            }
            if (offset < text.length && (text[offset] == 'e' || text[offset] == 'E')) {
                decimal = true
                offset++
                if (offset < text.length && (text[offset] == '+' || text[offset] == '-')) offset++
                digits(required = true)
            }
            val raw = text.substring(start, offset)
            if (!decimal) raw.toLongOrNull()?.let { return it }
            val parsed = raw.toDoubleOrNull()
            if (parsed == null || !parsed.isFinite()) fail("Invalid number")
            return parsed
        }

        private fun digits(required: Boolean) {
            val start = offset
            while (offset < text.length && text[offset] in '0'..'9') offset++
            if (required && start == offset) fail("Expected a digit")
        }

        private fun <T> literal(expected: String, result: T): T {
            if (!text.regionMatches(offset, expected, 0, expected.length)) fail("Invalid literal")
            offset += expected.length
            return result
        }

        private fun expect(expected: Char) {
            if (!take(expected)) fail("Expected '$expected'")
        }

        private fun take(expected: Char): Boolean {
            if (offset >= text.length || text[offset] != expected) return false
            offset++
            return true
        }

        private fun skipWhitespace() {
            while (offset < text.length && text[offset] in " \t\r\n") offset++
        }

        private fun asciiHex(value: Char): Int? = when (value) {
            in '0'..'9' -> value - '0'
            in 'a'..'f' -> value - 'a' + 10
            in 'A'..'F' -> value - 'A' + 10
            else -> null
        }

        private fun fail(message: String): Nothing = throw ParseException("$message at character $offset")
    }
}
