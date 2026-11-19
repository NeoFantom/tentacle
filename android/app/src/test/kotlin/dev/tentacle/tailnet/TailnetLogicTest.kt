package dev.tentacle.tailnet

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class TailnetLogicTest {
    @Test fun `hostnames are DNS labels`() {
        assertEquals("tentacle-pixel-8-pro", TailnetLogic.defaultHostname("Pixel 8 Pro"))
        assertEquals("tentacle-sm-s918b", TailnetLogic.defaultHostname("SM-S918B"))
        assertEquals("tentacle", TailnetLogic.defaultHostname("设备"))
        assertEquals("a-b", TailnetLogic.sanitizeHostname("--A__b--"))
        assertEquals(63, TailnetLogic.sanitizeHostname("x".repeat(100)).length)
    }

    @Test fun `control URL validation`() {
        assertTrue(TailnetLogic.validControlUrl(""))
        assertTrue(TailnetLogic.validControlUrl("https://example.com"))
        assertTrue(TailnetLogic.validControlUrl("http://192.0.2.1:8080"))
        assertFalse(TailnetLogic.validControlUrl("example.com"))
        assertFalse(TailnetLogic.validControlUrl("ftp://example.com"))
        assertFalse(TailnetLogic.validControlUrl("https://user@example.com"))
        assertFalse(TailnetLogic.validControlUrl("https://example.com/?x=1"))
    }

    @Test fun `status JSON from the bridge`() {
        val running = TailnetLogic.parseStatus(
            """{"state":"running","ip":"100.64.0.5","dns_name":"tentacle-x.example.ts.net","peers":3,"direct_peers":1}""")
        assertEquals(TailnetStatus.Mode.RUNNING, running.mode)
        assertEquals("100.64.0.5", running.ip)
        assertEquals(3, running.peers)
        assertEquals(1, running.directPeers)
        assertEquals(mapOf("state" to "running", "ip" to "100.64.0.5"), running.pingJson())
        val login = TailnetLogic.parseStatus(
            """{"state":"needs_login","login_url":"https://example.com/a/b&c","error":"x \"y\"","peers":0}""")
        assertEquals(TailnetStatus.Mode.NEEDS_LOGIN, login.mode)
        assertEquals("https://example.com/a/b&c", login.loginUrl)
        assertEquals("x \"y\"", login.error)
        assertEquals(mapOf("state" to "needs_login"), login.pingJson())
        assertEquals(TailnetStatus.Mode.OFF, TailnetLogic.parseStatus("""{"state":"stopped","peers":0}""").mode)
        assertNull(TailnetStatus.State().pingJson())
    }

    @Test fun `interfaces JSON matches the bridge format`() {
        val json = TailnetLogic.interfacesJson(listOf(TailnetLogic.Interface("wlan0", 3, 1500, true, false, false, true,
            listOf("192.0.2.10" to 24))), "wlan0", "192.0.2.1")
        assertEquals("""{"defaultInterface":"wlan0","gateway":"192.0.2.1","interfaces":[{"name":"wlan0","index":3,""" +
            """"mtu":1500,"up":true,"broadcast":true,"loopback":false,"pointToPoint":false,"multicast":true,""" +
            """"addrs":[{"ip":"192.0.2.10","prefixLen":24}]}]}""", json)
    }
}
