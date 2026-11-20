package dev.tentacle.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.random.Random

class ServerInfoParserTest {
    private fun parse(text: String) = ServerInfoParser.parse(text)

    @Test
    fun `ssh commands with options before or after the destination`() {
        assertEquals(ServerInfo("1.2.3.4", 2222, "user"), parse("ssh -p 2222 user@1.2.3.4"))
        assertEquals(ServerInfo("example.com", 2222, "user"), parse("ssh user@example.com -p 2222"))
        assertEquals(ServerInfo("192.0.2.10", 22, "tentacle"), parse("\$ ssh -p22 -i ~/.ssh/id_ecdsa tentacle@192.0.2.10"))
        assertEquals(ServerInfo("192.0.2.10", 2200, "tentacle"), parse("ssh -o Port=2200 -l tentacle 192.0.2.10"))
        assertEquals(ServerInfo("example.com", null, "tentacle", 9876),
            parse("ssh -N -R 127.0.0.1:9876:127.0.0.1:8765 tentacle@example.com"))
        assertEquals(ServerInfo("192.0.2.10", 2222, "me", 9000), parse("ssh -NT -R9000:localhost:8765 -p 2222 me@192.0.2.10 uptime"))
        assertEquals(ServerInfo("example.com", null, null, 9876), parse("ssh -R 9876:127.0.0.1:8765 example.com"))
    }

    @Test
    fun `ssh urls and user at host forms`() {
        assertEquals(ServerInfo("example.com", 2222, "user"), parse("ssh://user@example.com:2222"))
        assertEquals(ServerInfo("example.com", 2222, "user"), parse("user@example.com:2222"))
        assertEquals(ServerInfo("192.0.2.7", 22, "tentacle"), parse("tentacle@192.0.2.7:22"))
        assertEquals(ServerInfo("2001:db8::1", 22, "tentacle"), parse("tentacle@[2001:db8::1]:22"))
        assertEquals(ServerInfo("2001:db8::1", 2222, "u"), parse("ssh://u@[2001:db8::1]:2222/"))
    }

    @Test
    fun `bare addresses and domain names`() {
        assertEquals(ServerInfo("192.0.2.1"), parse("192.0.2.1"))
        assertEquals(ServerInfo("192.0.2.1", 2222), parse("server is at 192.0.2.1:2222."))
        assertEquals(ServerInfo("2001:db8::1", 22), parse("[2001:db8::1]:22"))
        assertEquals(ServerInfo("2001:db8::5"), parse("address 2001:DB8::5"))
        assertEquals(ServerInfo("2001:db8::ffff:192.0.2.1"), parse("2001:db8::ffff:192.0.2.1"))
        assertEquals(ServerInfo("tunnel.example.com"), parse("tunnel.example.com"))
        // Loopback, timestamps and file names are not hosts.
        assertNull(parse("127.0.0.1 ::1 localhost at 12:30:45 see config.toml and id_ecdsa.pub").host)
    }

    @Test
    fun `public url is separate from the ssh host`() {
        assertEquals(ServerInfo("192.0.2.10", 22, "tentacle", publicUrl = "https://example.com/tentacle"),
            parse("ssh -p 22 tentacle@192.0.2.10\nhttps://example.com/tentacle/"))
        assertEquals(ServerInfo("192.0.2.10", publicUrl = "https://example.com/tentacle"),
            parse("https://example.com/tentacle 192.0.2.10"))
        // Only a URL: its host is offered, flagged so the app can warn about CDNs.
        assertEquals(ServerInfo("example.com", publicUrl = "https://example.com/tentacle", hostFromUrl = true),
            parse("Open https://example.com/tentacle."))
        assertEquals("https://example.com", parse("https://example.com/?a=b#c").publicUrl)
        assertEquals("https://example.com:8443/t", parse("（https://example.com:8443/t），").publicUrl)
        assertNull(parse("https://user:secret@example.com/").publicUrl)
    }

    @Test
    fun `labelled lines in English`() {
        val info = parse("""
            Host: 192.0.2.20
            SSH Port: 2022
            Username = tentacle
            Remote port 9876
            URL example.com/tentacle
        """.trimIndent())
        assertEquals(ServerInfo("192.0.2.20", 2022, "tentacle", 9876, "https://example.com/tentacle"), info)
        assertEquals(ServerInfo("example.com", 22, "bob"), parse("server: example.com, port: 22, user: bob"))
        // Prose is not mistaken for labels.
        assertEquals(ServerInfo(), parse("connect to the server using your user account"))
    }

    @Test
    fun `labelled lines in Chinese with full-width punctuation`() {
        val info = parse("主机：192.0.2.30\n端口： 22\n用户名=tentacle\n远程端口 9876\n访问地址：https://example.com/t")
        assertEquals(ServerInfo("192.0.2.30", 22, "tentacle", 9876, "https://example.com/t"), info)
        assertEquals(ServerInfo("192.0.2.31", 2222, "tentacle"),
            parse("服务器地址：192.0.2.31，SSH端口２２２２，用户：tentacle。"))
        assertEquals(ServerInfo("example.com", null, null, 9000), parse("主机　example.com　转发端口：9000"))
    }

    @Test
    fun `authorized keys line gives the remote port`() {
        val line = "restrict,port-forwarding,permitlisten=\"127.0.0.1:9876\",command=\"/usr/sbin/nologin\" " +
            "ecdsa-sha2-nistp256 AAAAE2VjZHNhLXNoYTItbmlzdHAyNTYAAAAIbmlzdHAyNTY= tentacle-phone"
        assertEquals(ServerInfo(remotePort = 9876), parse(line))
        assertEquals(ServerInfo("192.0.2.40", remotePort = 8800, user = "tentacle"),
            parse("tentacle@192.0.2.40\npermitlisten=“8800”"))
    }

    @Test
    fun `mixed messy text`() {
        val text = """
            Hi! Your tunnel is ready 👍
            Login:  ssh -p 2222 tentacle@192.0.2.50
            Then run ssh -N -R 127.0.0.1:9876:127.0.0.1:8765 tentacle@192.0.2.50
            Public: https://example.com/tentacle
            Fingerprint SHA256:abc/def+ghi
        """.trimIndent()
        assertEquals(ServerInfo("192.0.2.50", 2222, "tentacle", 9876, "https://example.com/tentacle"), parse(text))
    }

    @Test
    fun `invalid values are ignored`() {
        assertEquals(ServerInfo(), parse(""))
        assertEquals(ServerInfo(), parse("port: 70000"))
        assertNull(parse("999.1.1.1").host)
        assertEquals(ServerInfo("example.com"), parse("ssh -p 0 example.com"))
        assertFalse(parse("ssh to the box").let { it.host != null })
    }

    @Test
    fun `never throws on random input`() {
        val random = Random(7)
        val alphabet = "ssh -pR@:[]./=：，主机端口\"'\n 0123456789abcdefhttps://permitlisten=".toCharArray()
        repeat(2000) {
            val text = String(CharArray(random.nextInt(0, 120)) { alphabet[random.nextInt(alphabet.size)] })
            ServerInfoParser.parse(text)
        }
        ServerInfoParser.parse("a".repeat(100_000) + "@" + ".".repeat(50_000))
    }

    @Test
    fun `ip literals and cloudflare ranges`() {
        assertEquals(4, IpAddresses.parse("192.0.2.1")?.size)
        assertEquals(16, IpAddresses.parse("::")?.size)
        assertNull(IpAddresses.parse("01.2.3.4"))
        assertNull(IpAddresses.parse("1:2:3:4:5:6:7:8:9"))
        assertNull(IpAddresses.parse("example.com"))
        assertTrue(IpAddresses.isCloudflare("104.16.0.1"))
        assertTrue(IpAddresses.isCloudflare("172.67.1.2"))
        assertTrue(IpAddresses.isCloudflare("2606:4700::6810:84e5"))
        assertTrue(IpAddresses.isCloudflare("2a06:98c7::1"))
        assertTrue(IpAddresses.isCloudflare("::ffff:104.16.0.1"))
        assertFalse(IpAddresses.isCloudflare("192.0.2.1"))
        assertFalse(IpAddresses.isCloudflare("2a06:98c8::1"))
        assertFalse(IpAddresses.isCloudflare("2001:db8::1"))
        assertFalse(IpAddresses.isCloudflare("not an ip"))
    }
}
