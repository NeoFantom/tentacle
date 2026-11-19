package dev.tentacle.tailnet

import dev.tentacle.core.Json
import java.net.URI

/** Android-independent helpers for the embedded Tailscale node, tested on the JVM. */
object TailnetLogic {
    /** One network interface as the Go bridge expects it (see tsbridge/interfaces.go). */
    data class Interface(
        val name: String,
        val index: Int,
        val mtu: Int,
        val up: Boolean,
        val loopback: Boolean,
        val pointToPoint: Boolean,
        val multicast: Boolean,
        val addresses: List<Pair<String, Int>>,
    )

    /** Hostname label: lowercase letters, digits and dashes, at most 63 characters. */
    fun sanitizeHostname(value: String): String = value.lowercase()
        .replace(Regex("[^a-z0-9-]+"), "-").replace(Regex("-+"), "-")
        .trim('-').take(63).trimEnd('-')

    fun defaultHostname(model: String): String =
        sanitizeHostname("tentacle-$model").ifEmpty { "tentacle" }

    /** Empty (Tailscale's default control server) or an http(s) URL without query or credentials. */
    fun validControlUrl(value: String): Boolean = value.isEmpty() || runCatching {
        val uri = URI(value)
        uri.scheme in setOf("http", "https") && !uri.host.isNullOrEmpty() &&
            uri.rawQuery == null && uri.rawFragment == null && uri.userInfo == null
    }.getOrDefault(false)

    fun interfacesJson(interfaces: List<Interface>, defaultInterface: String, gateway: String): String =
        Json.stringify(linkedMapOf(
            "defaultInterface" to defaultInterface,
            "gateway" to gateway,
            "interfaces" to interfaces.map {
                linkedMapOf(
                    "name" to it.name, "index" to it.index, "mtu" to it.mtu, "up" to it.up,
                    "broadcast" to it.multicast, "loopback" to it.loopback,
                    "pointToPoint" to it.pointToPoint, "multicast" to it.multicast,
                    "addrs" to it.addresses.map { (ip, prefix) -> linkedMapOf("ip" to ip, "prefixLen" to prefix) },
                )
            },
        ))

    /** Parses the bridge's StatusJSON (a flat object of strings, numbers and booleans). */
    fun parseStatus(json: String): TailnetStatus.State {
        val fields = flatJson(json)
        val mode = when (fields["state"]) {
            "starting" -> TailnetStatus.Mode.STARTING
            "needs_login" -> TailnetStatus.Mode.NEEDS_LOGIN
            "running" -> TailnetStatus.Mode.RUNNING
            "error" -> TailnetStatus.Mode.ERROR
            else -> TailnetStatus.Mode.OFF
        }
        return TailnetStatus.State(
            mode = mode,
            ip = fields["ip"]?.toString()?.ifEmpty { null },
            dnsName = fields["dns_name"]?.toString()?.ifEmpty { null },
            loginUrl = fields["login_url"]?.toString()?.ifEmpty { null },
            error = fields["error"]?.toString()?.ifEmpty { null },
            peers = (fields["peers"] as? Long)?.toInt() ?: 0,
            directPeers = (fields["direct_peers"] as? Long)?.toInt() ?: 0,
        )
    }

    internal fun flatJson(json: String): Map<String, Any?> {
        val result = linkedMapOf<String, Any?>()
        var i = 0
        fun skip() { while (i < json.length && json[i].isWhitespace()) i++ }
        fun expect(c: Char) { skip(); require(i < json.length && json[i] == c) { "expected $c at $i" }; i++ }
        fun string(): String {
            expect('"')
            val out = StringBuilder()
            while (true) {
                require(i < json.length) { "unterminated string" }
                val c = json[i++]
                when (c) {
                    '"' -> return out.toString()
                    '\\' -> {
                        when (val e = json[i++]) {
                            'n' -> out.append('\n'); 't' -> out.append('\t'); 'r' -> out.append('\r')
                            'b' -> out.append('\b'); 'f' -> out.append('\u000c')
                            'u' -> { out.append(json.substring(i, i + 4).toInt(16).toChar()); i += 4 }
                            else -> out.append(e)
                        }
                    }
                    else -> out.append(c)
                }
            }
        }
        expect('{')
        skip()
        if (i < json.length && json[i] == '}') return result
        while (true) {
            val key = string()
            expect(':')
            skip()
            result[key] = when {
                json[i] == '"' -> string()
                json.startsWith("true", i) -> { i += 4; true }
                json.startsWith("false", i) -> { i += 5; false }
                json.startsWith("null", i) -> { i += 4; null }
                else -> {
                    val start = i
                    while (i < json.length && (json[i].isDigit() || json[i] in "-+.eE")) i++
                    json.substring(start, i).toLongOrNull()
                }
            }
            skip()
            if (json[i] == ',') { i++; continue }
            expect('}')
            return result
        }
    }
}
