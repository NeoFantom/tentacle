package dev.tentacle.core

import java.net.URI
import java.text.Normalizer

/**
 * Own-server (SSH tunnel) settings recognised in free text; `null` means not found.
 * [hostFromUrl] is set when the SSH host was only taken from [publicUrl]'s host name,
 * which may be a CDN front rather than the server itself.
 */
data class ServerInfo(
    val host: String? = null,
    val sshPort: Int? = null,
    val user: String? = null,
    val remotePort: Int? = null,
    val publicUrl: String? = null,
    val hostFromUrl: Boolean = false,
) {
    val isEmpty: Boolean
        get() = host == null && sshPort == null && user == null && remotePort == null && publicUrl == null
}

/**
 * Pulls SSH host, SSH port, user, remote (forwarded) port and public URL out of
 * whatever a user pastes: an `ssh` command line, `ssh://` or `user@host:port`
 * targets, an `authorized_keys` line with `permitlisten`, labelled lines in
 * English or Chinese (`Host: …`, `端口：22`), bare IPv4/IPv6 addresses, domain
 * names and `https://` URLs. Full-width punctuation is accepted. Pure JVM;
 * never throws (unrecognisable input gives an empty [ServerInfo]).
 */
object ServerInfoParser {
    private const val MAX_INPUT = 16_000

    fun parse(text: String): ServerInfo = try {
        Scan(normalize(text.take(MAX_INPUT))).run()
    } catch (_: Exception) {
        ServerInfo()
    } catch (_: StackOverflowError) {
        ServerInfo()
    }

    // Lower rank wins; equal rank keeps the first value seen.
    private const val COMMAND = 0
    private const val LABEL = 1
    private const val TARGET = 2
    private const val ADDRESS = 3
    private const val DOMAIN = 4

    private enum class Field { HOST, PORT, USER, REMOTE, URL }

    private val labels: List<Pair<String, Field>> = listOf(
        Field.REMOTE to listOf("remote bind port", "remote port", "remote_port", "remoteport", "bind port",
            "forward port", "forwarded port", "tunnel port", "远程绑定端口", "远程端口", "远端端口", "转发端口",
            "反向端口", "隧道端口", "绑定端口"),
        Field.URL to listOf("public url", "public_url", "url", "公网地址", "访问地址", "公网url", "公开地址",
            "网址", "链接"),
        Field.PORT to listOf("ssh port", "ssh_port", "port", "ssh端口", "端口号", "端口"),
        Field.USER to listOf("ssh user", "ssh_user", "username", "user name", "user", "login", "ssh用户",
            "用户名", "用户", "账号", "帐号", "账户", "登录名"),
        Field.HOST to listOf("ssh host", "ssh_host", "hostname", "host name", "host", "server address",
            "server ip", "server", "ip address", "address", "addr", "ip", "ssh主机", "主机地址", "主机名", "主机",
            "服务器地址", "服务器ip", "服务器", "ip地址", "地址"),
    ).flatMap { (field, keys) -> keys.map { it to field } }.sortedByDescending { it.first.length }

    private val labelRegex: Regex = run {
        val alternatives = labels.joinToString("|") { (key, _) ->
            val body = Regex.escape(key).replace(" ", "\\E[ _-]?\\Q")
            if (key.last().isAsciiLetter()) "$body(?![A-Za-z0-9_@.])" else body
        }
        Regex("(?i)(?<![A-Za-z0-9@._/-])($alternatives) *([:=])? *(\"[^\"\\n]*\"|[^\\s,;\"]+)")
    }

    private val permitListen = Regex("(?i)permitlisten *= *\"?([^\"\\s,]*)\"?")
    private val sshUrl = Regex("(?i)(?<![A-Za-z0-9+.-])ssh://(?:([^@\\s/:]+)@)?(\\[[0-9a-f:.]+]|[^\\s/:@\"']+)(?::(\\d{1,5}))?[^\\s\"']*")
    private val httpUrl = Regex("(?i)(?<![A-Za-z0-9+.-])https?://[^\\s\"'<>,;\\u3000-\\u303f\\uff00-\\uffef]+")
    private val sshCommand = Regex("(?i)(?<![A-Za-z0-9_./-])ssh(?= )")
    private val userAt = Regex("(?i)(?<![A-Za-z0-9_.@-])([a-z_][a-z0-9_.-]{0,31})@(\\[[0-9a-f:.]+]|[0-9a-f]*:[0-9a-f:.]*|[a-z0-9.-]+)(?::(\\d{1,5}))?(?![A-Za-z0-9_-])")
    private val bracketed = Regex("(?i)\\[([0-9a-f:.]+)](?::(\\d{1,5}))?")
    private val ipv4 = Regex("(?<![0-9.])(\\d{1,3}(?:\\.\\d{1,3}){3})(?::(\\d{1,5}))?(?![0-9]|\\.[0-9])")
    private val ipv6 = Regex("(?i)(?<![0-9a-z:.])([0-9a-f]{0,4}(?::[0-9a-f]{0,4}){2,7}(?::\\d{1,3}(?:\\.\\d{1,3}){3})?)(?![0-9a-z:.])")
    private val domain = Regex("(?i)(?<![A-Za-z0-9_@.-])((?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z]{2,63})(?::(\\d{1,5}))?(?![A-Za-z0-9_-])")
    private val userName = Regex("(?i)[a-z_][a-z0-9_.-]{0,31}\\$?")
    private val fileExtensions = setOf("pub", "pem", "key", "toml", "txt", "json", "conf", "cfg", "ini", "yml",
        "yaml", "xml", "md", "log", "sh", "py", "kt", "java", "html", "htm", "zip", "apk", "gz", "tar", "sock")
    private val optionsWithArgument = "BbcDEeFIiJLlmOopQRSWw".toSet()
    private val labelWords = labels.map { it.first }.toSet()
    private val separators = Regex("[ _-]")

    private fun Char.isAsciiLetter() = this in 'a'..'z' || this in 'A'..'Z'

    private fun normalize(text: String): String {
        val folded = Normalizer.normalize(text, Normalizer.Form.NFKC)
        return buildString(folded.length) {
            for (c in folded) append(when (c) {
                '\u3002', '\u3001', '\u00a0', '\t', '\u200b' -> ' '
                '\u201c', '\u201d', '\u201e', '\u00ab', '\u00bb', '\u300c', '\u300d' -> '"'
                '\u2018', '\u2019', '`' -> '\''
                '\r' -> '\n'
                else -> c
            })
        }
    }

    private class Slot<T : Any> {
        var value: T? = null
        var rank = Int.MAX_VALUE
        fun offer(candidate: T?, candidateRank: Int) {
            if (candidate != null && candidateRank < rank) { value = candidate; rank = candidateRank }
        }
    }

    private class Scan(text: String) {
        private val rest = StringBuilder(text)
        private val host = Slot<String>()
        private val port = Slot<Int>()
        private val user = Slot<String>()
        private val remote = Slot<Int>()
        private val url = Slot<String>()
        private var urlHost: String? = null

        fun run(): ServerInfo {
            permitListens()
            httpUrls()
            sshCommands()
            sshUrls()
            labelled()
            generic()
            var fromUrl = false
            if (host.value == null && urlHost != null) {
                host.offer(urlHost, DOMAIN)
                fromUrl = true
            }
            return ServerInfo(host.value, port.value, user.value, remote.value, url.value, fromUrl)
        }

        private fun blank(range: IntRange) {
            for (index in range) if (rest[index] != '\n') rest.setCharAt(index, ' ')
        }

        private fun matches(regex: Regex): List<MatchResult> = regex.findAll(rest.toString()).toList()

        private fun permitListens() {
            for (match in matches(permitListen)) {
                remote.offer(portOf(match.groupValues[1].substringAfterLast(':')), LABEL)
                blank(match.range)
            }
        }

        private fun sshUrls() {
            for (match in matches(sshUrl)) {
                val target = hostOf(match.groupValues[2])
                if (target != null) {
                    host.offer(target, COMMAND)
                    user.offer(userOf(match.groupValues[1]), COMMAND)
                    port.offer(portOf(match.groupValues[3]), COMMAND)
                }
                blank(match.range)
            }
        }

        private fun httpUrls() {
            for (match in matches(httpUrl)) {
                val raw = trimUrlPunctuation(match.value)
                publicUrl(raw)?.let { (value, name) ->
                    if (url.value == null) urlHost = name
                    url.offer(value, LABEL)
                }
                blank(match.range)
            }
        }

        /** `ssh [options] destination [options]`, including SSH URL destinations. */
        private fun sshCommands() {
            for (match in matches(sshCommand)) {
                val lineEnd = rest.indexOf("\n", match.range.last).let { if (it < 0) rest.length else it }
                val line = rest.substring(match.range.last + 1, lineEnd)
                val tokens = shellTokens(line)
                if (tokens.isEmpty()) continue
                val first = tokens.first().lowercase().trimEnd(':', '=')
                if (first in labelWords || first.startsWith(":") || first.startsWith("=")) continue
                var target: String? = null
                var cmdPort: Int? = null
                var cmdUser: String? = null
                var cmdRemote: Int? = null
                var sawOption = false
                var trailing = false
                var index = 0
                loop@ while (index < tokens.size) {
                    val token = tokens[index]
                    if (token in setOf(";", "&&", "||", "|", "&") || token.startsWith("#")) break
                    if (token == "--") { index++; continue }
                    if (token.length > 1 && token.startsWith("-")) {
                        sawOption = true
                        var position = 1
                        while (position < token.length) {
                            val option = token[position]
                            if (option in optionsWithArgument) {
                                val inline = token.substring(position + 1)
                                val argument = inline.ifEmpty { tokens.getOrNull(++index).orEmpty() }
                                when (option) {
                                    'p' -> cmdPort = portOf(argument) ?: cmdPort
                                    'l' -> cmdUser = userOf(argument) ?: cmdUser
                                    'R' -> cmdRemote = remoteForwardPort(argument) ?: cmdRemote
                                    'o' -> {
                                        val (key, value) = optionValue(argument, tokens.getOrNull(index + 1))
                                        when (key) {
                                            "port" -> cmdPort = portOf(value) ?: cmdPort
                                            "user" -> cmdUser = userOf(value) ?: cmdUser
                                        }
                                    }
                                }
                                break
                            }
                            position++
                        }
                        index++
                        continue
                    }
                    if (target != null) { trailing = true; break@loop } // The remote command starts here.
                    if (targetOf(token) != null) target = token
                    index++
                }
                val parsed = target?.let(::targetOf) ?: continue
                val atLineStart = rest.substring(rest.lastIndexOf("\n", match.range.first).coerceAtLeast(0), match.range.first)
                    .trim().let { it.isEmpty() || it.all { c -> c in "$#>%" } }
                val plausible = parsed.user != null || sawOption || '.' in parsed.host || ':' in parsed.host ||
                    (atLineStart && !trailing)
                if (!plausible) continue
                host.offer(parsed.host, COMMAND)
                user.offer(cmdUser ?: parsed.user, COMMAND)
                port.offer(cmdPort ?: parsed.port, COMMAND)
                remote.offer(cmdRemote, COMMAND)
                blank(match.range.first until lineEnd)
            }
        }

        private fun labelled() {
            for (match in matches(labelRegex)) {
                val key = match.groupValues[1].lowercase().replace(separators, "")
                val field = labels.firstOrNull { (label, _) -> label.replace(separators, "") == key }?.second ?: continue
                val value = match.groupValues[3].trim('"', '\'').trimEnd('.', ',', ';', ')', '!')
                // "server using …" in prose is not a label: Latin labels need ":" or "=" unless the value is an address.
                val explicit = match.groupValues[2].isNotEmpty() || !key.last().isAsciiLetter() ||
                    IpAddresses.parse(value.substringAfter('@').removePrefix("[").substringBefore(']')) != null
                var used = true
                when (field) {
                    Field.PORT -> used = portOf(value)?.also { port.offer(it, LABEL) } != null
                    Field.REMOTE -> used = portOf(value.substringAfterLast(':'))?.also { remote.offer(it, LABEL) } != null
                    Field.USER -> used = explicit && userOf(value.substringBefore('@'))?.also { user.offer(it, LABEL) } != null
                    Field.URL -> used = (if ('.' in value && "://" !in value) publicUrl("https://$value") else null)
                        ?.also { (link, name) -> if (url.value == null) urlHost = name; url.offer(link, LABEL) } != null
                    Field.HOST -> {
                        val parsed = if ("://" in value || !(explicit || '.' in value)) null else targetOf(value)
                        used = parsed != null
                        if (parsed != null) {
                            host.offer(parsed.host, LABEL)
                            user.offer(parsed.user, LABEL)
                            port.offer(parsed.port, TARGET)
                        }
                    }
                }
                if (used) blank(match.range) else blank(match.groups[1]!!.range)
            }
        }

        private fun generic() {
            for (match in matches(userAt)) {
                val parsed = targetOf(match.groupValues[2]) ?: continue
                host.offer(parsed.host, TARGET)
                user.offer(userOf(match.groupValues[1]), TARGET)
                port.offer(portOf(match.groupValues[3]) ?: parsed.port, TARGET)
                blank(match.range)
            }
            for (match in matches(bracketed)) {
                val address = IpAddresses.parse(match.groupValues[1])?.takeIf { it.size == 16 } ?: continue
                if (!IpAddresses.isUnspecifiedOrLoopback(address)) {
                    host.offer(match.groupValues[1].lowercase(), ADDRESS)
                    port.offer(portOf(match.groupValues[2]), TARGET)
                }
                blank(match.range)
            }
            for (match in matches(ipv6)) {
                val text = match.groupValues[1]
                val address = IpAddresses.parse(text)?.takeIf { it.size == 16 } ?: continue
                if (!IpAddresses.isUnspecifiedOrLoopback(address)) host.offer(text.lowercase(), ADDRESS)
                blank(match.range)
            }
            for (match in matches(ipv4)) {
                val address = IpAddresses.parse(match.groupValues[1]) ?: continue
                if (!IpAddresses.isUnspecifiedOrLoopback(address)) {
                    host.offer(match.groupValues[1], ADDRESS)
                    port.offer(portOf(match.groupValues[2]), TARGET)
                }
                blank(match.range)
            }
            for (match in matches(domain)) {
                val name = match.groupValues[1].lowercase()
                if (name.substringAfterLast('.') in fileExtensions) continue
                host.offer(name, DOMAIN)
                port.offer(portOf(match.groupValues[2]), TARGET)
                blank(match.range)
            }
        }
    }

    private data class Target(val host: String, val user: String?, val port: Int?)

    /** `[user@]host[:port]`, `[user@][v6][:port]`, a bare IPv6 literal or an SSH URL. */
    private fun targetOf(raw: String): Target? {
        val trimmed = raw.trim().trim('"', '\'').trimEnd('.', ',', ';', ')')
        val value = if (trimmed.startsWith("ssh://", ignoreCase = true)) trimmed.substring(6).removeSuffix("/") else trimmed
        if (value.isEmpty()) return null
        val at = value.lastIndexOf('@')
        val user = if (at > 0) userOf(value.substring(0, at)) ?: return null else null
        val rest = if (at >= 0) value.substring(at + 1) else value
        if (rest.startsWith("[")) {
            val close = rest.indexOf(']').takeIf { it > 0 } ?: return null
            val host = hostOf(rest.substring(0, close + 1)) ?: return null
            val tail = rest.substring(close + 1)
            val port = if (tail.startsWith(":")) portOf(tail.substring(1)) ?: return null else if (tail.isEmpty()) null else return null
            return Target(host, user, port)
        }
        if (rest.count { it == ':' } >= 2) return hostOf(rest)?.let { Target(it, user, null) }
        val host = hostOf(rest.substringBefore(':')) ?: return null
        val port = if (':' in rest) portOf(rest.substringAfter(':')) ?: return null else null
        return Target(host, user, port)
    }

    /** A usable SSH host: IPv4, IPv6 (brackets removed) or a DNS name; never loopback. */
    private fun hostOf(raw: String): String? {
        val value = raw.trim().removePrefix("[").removeSuffix("]").trimEnd('.')
        if (value.isEmpty() || value.length > 253) return null
        IpAddresses.parse(value)?.let { return if (IpAddresses.isUnspecifiedOrLoopback(it)) null else value.lowercase() }
        if (value.all { it.isDigit() || it == '.' }) return null
        val labelsOk = value.split('.').all { label ->
            label.isNotEmpty() && label.length <= 63 && label.first() != '-' && label.last() != '-' &&
                label.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '-' }
        }
        if (!labelsOk || value.equals("localhost", ignoreCase = true)) return null
        return value.lowercase()
    }

    private fun portOf(raw: String?): Int? = raw?.trim()?.takeIf { it.length in 1..5 && it.all(Char::isDigit) }
        ?.toInt()?.takeIf { it in 1..65535 }

    private fun userOf(raw: String?): String? = raw?.trim()?.takeIf { userName.matches(it) }

    /** `-R [bind:]port:host:hostport`, `-R [bind:]port:socket` or `-R [bind:]port`: the server-side port. */
    private fun remoteForwardPort(spec: String): Int? {
        val parts = Regex("\\[[^]]*]|[^:]+").findAll(spec).map { it.value }.toList()
        return when (parts.size) {
            4 -> portOf(parts[1])
            3 -> portOf(parts[0])
            2 -> portOf(parts[0]) ?: portOf(parts[1])
            1 -> portOf(parts[0])
            else -> null
        }
    }

    private fun optionValue(argument: String, next: String?): Pair<String, String> {
        val separator = argument.indexOfFirst { it == '=' || it == ' ' }
        return if (separator > 0) argument.substring(0, separator).lowercase() to argument.substring(separator + 1).trim()
        else argument.lowercase() to next.orEmpty()
    }

    /** Shell quoting and escapes only; pasted commands are never evaluated. */
    private fun shellTokens(line: String): List<String> {
        val tokens = mutableListOf<String>()
        val token = StringBuilder()
        var quote: Char? = null
        var started = false
        var index = 0
        while (index < line.length) {
            val c = line[index]
            when {
                c == '\\' && quote != '\'' -> {
                    val next = line.getOrNull(index + 1)
                    if (next != null && (quote == null || next in "\"\\$`")) {
                        token.append(next)
                        index++
                    } else token.append(c)
                    started = true
                }
                quote != null -> if (c == quote) quote = null else token.append(c)
                c == '\'' || c == '"' -> { quote = c; started = true }
                c.isWhitespace() -> if (started) {
                    tokens.add(token.toString())
                    token.setLength(0)
                    started = false
                }
                else -> { token.append(c); started = true }
            }
            index++
        }
        // An unbalanced quote (an apostrophe in prose after the command) ends the last token.
        if (started) tokens.add(token.toString())
        return tokens
    }

    private fun trimUrlPunctuation(value: String): String {
        var end = value.length
        var brackets = value.count { it == '[' } - value.count { it == ']' }
        var parentheses = value.count { it == '(' } - value.count { it == ')' }
        while (end > 0) {
            when (value[end - 1]) {
                ']' -> if (brackets < 0) brackets++ else break
                ')' -> if (parentheses < 0) parentheses++ else break
                '.', ',', ';', ':', '}', '>', '!', '?', '\'', '"' -> Unit
                else -> break
            }
            end--
        }
        return value.substring(0, end)
    }

    /** An http(s) URL without credentials, query or fragment, and its host name. */
    private fun publicUrl(raw: String): Pair<String, String>? = try {
        val uri = URI(raw)
        val name = uri.host?.removePrefix("[")?.removeSuffix("]")
        if (uri.scheme?.lowercase() !in setOf("http", "https") || name.isNullOrEmpty() || uri.rawUserInfo != null) null
        else {
            val base = URI(uri.scheme.lowercase(), null, uri.host, uri.port, uri.path?.trimEnd('/')?.ifEmpty { null }, null, null)
            base.toASCIIString() to name.lowercase()
        }
    } catch (_: Exception) {
        null
    }
}

/** Literal IP parsing (no DNS) and Cloudflare's published address ranges. */
object IpAddresses {
    /** 4 or 16 bytes for an IPv4 or IPv6 literal (zone ids not accepted), else null. */
    fun parse(text: String): ByteArray? {
        val value = text.trim()
        if (value.isEmpty()) return null
        if (':' !in value) return parseIpv4(value)
        return parseIpv6(value)
    }

    private fun parseIpv4(value: String): ByteArray? {
        val parts = value.split('.')
        if (parts.size != 4) return null
        val bytes = ByteArray(4)
        for ((index, part) in parts.withIndex()) {
            if (part.isEmpty() || part.length > 3 || !part.all(Char::isDigit)) return null
            val number = part.toInt()
            if (number > 255 || (part.length > 1 && part.startsWith("0"))) return null
            bytes[index] = number.toByte()
        }
        return bytes
    }

    private fun parseIpv6(value: String): ByteArray? {
        if (value.count { it == ':' } > 7 + 1 || value.contains(":::")) return null
        var tail = value
        var ipv4: ByteArray? = null
        val lastColon = value.lastIndexOf(':')
        if ('.' in value.substring(lastColon + 1)) {
            ipv4 = parseIpv4(value.substring(lastColon + 1)) ?: return null
            tail = value.substring(0, lastColon + 1) + "0:0"
        }
        val halves = tail.split("::")
        if (halves.size > 2) return null
        fun groups(part: String): List<Int>? = if (part.isEmpty()) emptyList() else part.split(':').map { group ->
            if (group.isEmpty() || group.length > 4 || !group.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) return null
            group.toInt(16)
        }
        val head = groups(halves[0]) ?: return null
        val end = if (halves.size == 2) groups(halves[1]) ?: return null else emptyList()
        val words = if (halves.size == 2) {
            if (head.size + end.size > 7) return null
            head + List(8 - head.size - end.size) { 0 } + end
        } else {
            if (head.size != 8) return null
            head
        }
        val bytes = ByteArray(16)
        words.forEachIndexed { index, word ->
            bytes[index * 2] = (word shr 8).toByte()
            bytes[index * 2 + 1] = word.toByte()
        }
        ipv4?.copyInto(bytes, 12)
        return bytes
    }

    fun isUnspecifiedOrLoopback(address: ByteArray): Boolean = when (address.size) {
        4 -> address[0].toInt() == 127 || address.all { it.toInt() == 0 }
        16 -> address.take(15).all { it.toInt() == 0 } && address[15].toInt() in 0..1
        else -> false
    }

    /** Cloudflare's published ranges (https://www.cloudflare.com/ips/), as of 2026-11. */
    private val cloudflare: List<Pair<ByteArray, Int>> = listOf(
        "173.245.48.0/20", "103.21.244.0/22", "103.22.200.0/22", "103.31.4.0/22", "141.101.64.0/18",
        "108.162.192.0/18", "190.93.240.0/20", "188.114.96.0/20", "197.234.240.0/22", "198.41.128.0/17",
        "162.158.0.0/15", "104.16.0.0/13", "104.24.0.0/14", "172.64.0.0/13", "131.0.72.0/22",
        "2400:cb00::/32", "2606:4700::/32", "2803:f800::/32", "2405:b500::/32", "2405:8100::/32",
        "2a06:98c0::/29", "2c0f:f248::/32",
    ).map { cidr -> parse(cidr.substringBefore('/'))!! to cidr.substringAfter('/').toInt() }

    /** True when [address] (4 or 16 bytes) lies in a Cloudflare range; IPv4-mapped IPv6 counts as IPv4. */
    fun isCloudflare(address: ByteArray): Boolean {
        val bytes = if (address.size == 16 && address.take(10).all { it.toInt() == 0 } &&
            address[10].toInt() == -1 && address[11].toInt() == -1) address.copyOfRange(12, 16) else address
        return cloudflare.any { (network, prefix) -> network.size == bytes.size && inPrefix(bytes, network, prefix) }
    }

    fun isCloudflare(text: String): Boolean = parse(text)?.let(::isCloudflare) ?: false

    private fun inPrefix(address: ByteArray, network: ByteArray, prefix: Int): Boolean {
        for (bit in 0 until prefix) {
            val mask = 0x80 ushr (bit % 8)
            if ((address[bit / 8].toInt() and mask) != (network[bit / 8].toInt() and mask)) return false
        }
        return true
    }
}
