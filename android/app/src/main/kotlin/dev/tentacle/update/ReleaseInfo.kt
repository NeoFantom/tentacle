package dev.tentacle.update

import dev.tentacle.core.Versions

data class ReleaseAsset(val name: String, val url: String, val size: Long)

data class ReleaseInfo(val version: String, val apk: ReleaseAsset, val checksum: ReleaseAsset?)

object Releases {
    fun compareVersions(left: String, right: String): Int = Versions.compare(left, right) ?: 0

    fun parse(json: String): ReleaseInfo {
        val root = JsonParser(json).parse() as? Map<*, *> ?: error("Release JSON must be an object")
        val tag = root["tag_name"] as? String ?: error("Release JSON has no tag_name")
        require(tag.matches(Regex("v[0-9]+\\.[0-9]+\\.[0-9]+")) && Versions.compare(tag, tag) != null) {
            "Release tag must be vX.Y.Z"
        }
        val version = tag.removePrefix("v")
        val assets = (root["assets"] as? List<*>)?.map { raw ->
            val item = raw as? Map<*, *> ?: error("Release asset must be an object")
            ReleaseAsset(
                name = item["name"] as? String ?: error("Release asset has no name"),
                url = item["browser_download_url"] as? String ?: error("Release asset has no URL"),
                size = (item["size"] as? Number)?.toLong() ?: -1,
            )
        }.orEmpty()
        val apk = selectApk(version, assets) ?: error("Release contains no APK")
        return ReleaseInfo(version, apk, assets.firstOrNull { it.name == "${apk.name}.sha256" })
    }

    fun selectApk(version: String, assets: List<ReleaseAsset>): ReleaseAsset? {
        val apks = assets.filter { it.name.endsWith(".apk", ignoreCase = true) }
        return apks.firstOrNull { it.name == "tentacle-$version.apk" } ?: apks.firstOrNull()
    }

}

private class JsonParser(private val source: String) {
    private var offset = 0

    fun parse(): Any? = value().also { whitespace(); check(offset == source.length) { "Trailing JSON data" } }

    private fun value(): Any? {
        whitespace()
        check(offset < source.length) { "Unexpected end of JSON" }
        return when (source[offset]) {
            '{' -> objectValue()
            '[' -> arrayValue()
            '"' -> stringValue()
            't' -> literal("true", true)
            'f' -> literal("false", false)
            'n' -> literal("null", null)
            else -> numberValue()
        }
    }

    private fun objectValue(): Map<String, Any?> {
        offset++
        val result = linkedMapOf<String, Any?>()
        whitespace()
        if (take('}')) return result
        while (true) {
            whitespace()
            val key = stringValue()
            whitespace()
            check(take(':')) { "Expected colon" }
            result[key] = value()
            whitespace()
            if (take('}')) return result
            check(take(',')) { "Expected comma" }
        }
    }

    private fun arrayValue(): List<Any?> {
        offset++
        val result = mutableListOf<Any?>()
        whitespace()
        if (take(']')) return result
        while (true) {
            result += value()
            whitespace()
            if (take(']')) return result
            check(take(',')) { "Expected comma" }
        }
    }

    private fun stringValue(): String {
        check(take('"')) { "Expected string" }
        val result = StringBuilder()
        while (offset < source.length) {
            val char = source[offset++]
            when (char) {
                '"' -> return result.toString()
                '\\' -> {
                    check(offset < source.length) { "Incomplete escape" }
                    when (val escaped = source[offset++]) {
                        '"', '\\', '/' -> result.append(escaped)
                        'b' -> result.append('\b')
                        'f' -> result.append('\u000c')
                        'n' -> result.append('\n')
                        'r' -> result.append('\r')
                        't' -> result.append('\t')
                        'u' -> {
                            check(offset + 4 <= source.length) { "Incomplete Unicode escape" }
                            result.append(source.substring(offset, offset + 4).toInt(16).toChar())
                            offset += 4
                        }
                        else -> error("Invalid escape: $escaped")
                    }
                }
                else -> result.append(char)
            }
        }
        error("Unterminated string")
    }

    private fun numberValue(): Number {
        val start = offset
        while (offset < source.length && source[offset] in "-+0123456789.eE") offset++
        check(start != offset) { "Expected JSON value" }
        val number = source.substring(start, offset)
        return if ('.' in number || 'e' in number.lowercase()) number.toDouble() else number.toLong()
    }

    private fun <T> literal(text: String, value: T): T {
        check(source.startsWith(text, offset)) { "Invalid JSON literal" }
        offset += text.length
        return value
    }

    private fun whitespace() { while (offset < source.length && source[offset].isWhitespace()) offset++ }
    private fun take(char: Char): Boolean = if (offset < source.length && source[offset] == char) {
        offset++
        true
    } else false
}
