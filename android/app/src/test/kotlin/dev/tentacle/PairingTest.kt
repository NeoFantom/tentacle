package dev.tentacle

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.net.URI
import java.net.URLDecoder

class PairingTest {
    @Test fun `CGNAT range boundaries identify Tailscale`() {
        for (host in listOf("100.64.0.1", "100.127.255.254")) {
            assertEquals(1, Pairing.address(host, "vpn", false).priority)
        }
        for (host in listOf("100.63.255.254", "100.128.0.1", "101.64.0.1")) {
            assertEquals(2, Pairing.address(host, "vpn", false).priority)
        }
    }

    @Test fun `LAN precedes VPN and duplicate host appears once`() {
        val lan = Pairing.address("192.0.2.1", "eth", true)
        val vpn = Pairing.address("100.64.0.1", "tun", false)
        val other = Pairing.address("198.51.100.1", "cell", false)
        assertEquals(listOf(lan, vpn, other), Pairing.sorted(listOf(other, vpn, lan, lan)))
    }

    @Test fun `pairing device name safely round trips unicode and delimiters`() {
        val device = "Example 设备 & +?#"
        val uri = URI(Pairing.uri("192.0.2.1", 8765, "example-token", device))
        assertEquals("tentacle", uri.scheme)
        assertEquals("pair", uri.host)
        val fields = uri.rawQuery.split('&').associate {
            val parts = it.split('=', limit = 2)
            parts[0] to URLDecoder.decode(parts[1], "UTF-8")
        }
        assertEquals(device, fields["name"])
        assertEquals("http://192.0.2.1:8765", fields["url"])
        assertEquals(3, fields.size)
    }
    @Test fun `public HTTPS URL and token delimiters round trip`() {
        val url = "https://example.com/tentacle/设备?q=a+b&x=1"
        val token = "a+b&?=#"
        val uri = URI(Pairing.uri(url, token, "设备"))
        val fields = uri.rawQuery.split('&').associate {
            val parts = it.split('=', limit = 2)
            parts[0] to URLDecoder.decode(parts[1], "UTF-8")
        }
        assertEquals(url, fields["url"])
        assertEquals(token, fields["token"])
    }

    @Test fun `all-addresses link lists tailnet, LAN, other and public URLs in order`() {
        val lan = Pairing.address("192.0.2.1", "wlan0", true)
        val vpn = Pairing.address("100.64.0.9", "tun0", false)
        val other = Pairing.address("198.51.100.1", "rmnet0", false)
        val uri = URI(Pairing.uriAll("http://100.64.0.1:8765", listOf(other, vpn, lan), 8765,
            "https://example.com/tentacle", "t&k", "Phone"))
        val pairs = uri.rawQuery.split('&').map {
            val parts = it.split('=', limit = 2)
            parts[0] to URLDecoder.decode(parts[1], "UTF-8")
        }
        assertEquals(listOf("http://100.64.0.1:8765", "http://192.0.2.1:8765", "http://100.64.0.9:8765",
            "http://198.51.100.1:8765", "https://example.com/tentacle"), pairs.filter { it.first == "url" }.map { it.second })
        assertEquals("t&k", pairs.single { it.first == "token" }.second)
        val noTailnet = URI(Pairing.uriAll(null, listOf(lan), 8765, null, "t", "Phone"))
        assertEquals(1, noTailnet.rawQuery.split('&').count { it.startsWith("url=") })
    }
}
