package dev.tentacle

import java.net.URLEncoder

/** Kept independent of Android so address ordering and URI encoding run on the JVM. */
object Pairing {
    data class Address(val host: String, val interfaceName: String, val priority: Int)

    fun address(host: String, interfaceName: String, lan: Boolean): Address {
        val bytes = host.split('.').map { it.toInt() }
        val tailscale = bytes[0] == 100 && bytes[1] in 64..127
        return when {
            tailscale -> Address(host, interfaceName, 1)
            lan -> Address(host, interfaceName, 0)
            else -> Address(host, interfaceName, 2)
        }
    }

    fun sorted(addresses: List<Address>): List<Address> = addresses
        .sortedWith(compareBy(Address::priority, Address::host)).distinctBy(Address::host)

    fun uri(host: String, port: Int, token: String, device: String): String =
        uri("http://$host:$port", token, device)

    fun uri(baseUrl: String, token: String, device: String): String =
        "tentacle://pair?name=${encode(device)}&token=${encode(token)}&url=${encode(baseUrl)}"

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")
}
