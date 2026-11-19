package dev.tentacle

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.net.URI
import java.net.URLDecoder

class PairingTest {
    @Test fun `CGNAT range boundaries identify Tailscale`() {
        for (host in listOf("100.64.0.1", "100.127.255.254")) {
            assertEquals("Tailscale", Pairing.address(host, "vpn", false).label)
        }
        for (host in listOf("100.63.255.254", "100.128.0.1", "101.64.0.1")) {
            assertTrue(Pairing.address(host, "vpn", false).label.startsWith("Other"))
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
        assertEquals(8765, uri.port)
        assertEquals(device, URLDecoder.decode(uri.rawQuery.substringAfter("&name="), "UTF-8"))
        assertEquals(2, uri.rawQuery.split('&').size)
    }
}
