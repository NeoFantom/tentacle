package dev.tentacle.update

data class ReleaseAsset(val name: String, val url: String, val size: Long)

data class ReleaseInfo(val version: String, val apk: ReleaseAsset, val checksum: ReleaseAsset?)

object Releases {
    fun compareVersions(left: String, right: String): Int {
        val a = parseVersion(left) ?: return 0
        val b = parseVersion(right) ?: return 0
        for (index in 0..2) {
            val compared = a.core[index].compareTo(b.core[index])
            if (compared != 0) return compared
        }
        if (a.preRelease.isEmpty() || b.preRelease.isEmpty()) {
            return when {
                a.preRelease.isEmpty() && b.preRelease.isEmpty() -> 0
                a.preRelease.isEmpty() -> 1
                else -> -1
            }
        }
        for (index in 0 until minOf(a.preRelease.size, b.preRelease.size)) {
            val leftPart = a.preRelease[index]
            val rightPart = b.preRelease[index]
            val leftNumber = leftPart.toBigIntegerOrNull()
            val rightNumber = rightPart.toBigIntegerOrNull()
            val compared = when {
                leftNumber != null && rightNumber != null -> leftNumber.compareTo(rightNumber)
                leftNumber != null -> -1
                rightNumber != null -> 1
                else -> leftPart.compareTo(rightPart)
            }
            if (compared != 0) return compared
        }
        return a.preRelease.size.compareTo(b.preRelease.size)
    }

    fun parse(json: String): ReleaseInfo {
        val root = JsonParser(json).parse() as? Map<*, *> ?: error("Release JSON must be an object")
        val tag = root["tag_name"] as? String ?: error("Release JSON has no tag_name")
        require(tag.matches(Regex("v[0-9]+\\.[0-9]+\\.[0-9]+"))) { "Release tag must be vX.Y.Z" }
        val version = parseVersion(tag)?.core?.joinToString(".") ?: error("Release tag must be vX.Y.Z")
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

    private data class Version(val core: List<java.math.BigInteger>, val preRelease: List<String>)

    private fun parseVersion(value: String): Version? {
        val match = Regex("^v?(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)(?:-([0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*))?(?:\\+[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?$")
            .matchEntire(value) ?: return null
        val preRelease = match.groupValues[4].takeIf { it.isNotEmpty() }?.split('.').orEmpty()
        if (preRelease.any { it.all(Char::isDigit) && it.length > 1 && it.startsWith('0') }) return null
        return Version((1..3).map { match.groupValues[it].toBigInteger() }, preRelease)
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
