package dev.phone2agent.core

/** Small JSON writer; strings remain Unicode and are encoded as UTF-8 by the transport. */
object Json {
    fun stringify(value: Any?): String = buildString { writeValue(value) }

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
}
