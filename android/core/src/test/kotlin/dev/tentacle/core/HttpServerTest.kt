package dev.tentacle.core

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.Socket
import java.net.SocketException
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale

class HttpServerTest {
    @TempDir
    lateinit var tempDir: Path

    private lateinit var root: Path
    private lateinit var server: HttpServer
    private val loopback: String = InetAddress.getLoopbackAddress().hostAddress
    private var token = "test-token"

    @BeforeEach
    fun startServer() {
        root = Files.createDirectory(tempDir.resolve("primary"))
        server = HttpServer(
            port = 0,
            bind = loopback,
            tokenProvider = { token },
            api = FileApi(listOf(StorageRoot("primary", root.toFile(), "Internal"))),
            info = {
                PingInfo(
                    version = "0.1.0-test",
                    device = "Test Device",
                    androidSdk = 35,
                    allFilesAccess = true,
                    roots = listOf(StorageRoot("primary", root.toFile(), "Internal")),
                    timezone = "Asia/Shanghai",
                )
            },
        )
        server.start()
    }

    @AfterEach
    fun stopServer() {
        server.stop()
    }

    @Test
    fun `server lifecycle exposes an ephemeral port`() {
        assertTrue(server.isRunning)
        assertTrue(server.boundPort in 1..65535)

        val originalPort = server.boundPort
        server.start()
        assertTrue(server.isRunning)
        assertEquals(originalPort, server.boundPort)

        server.stop()
        assertFalse(server.isRunning)

        server.start()
        assertTrue(server.isRunning)
        assertTrue(server.boundPort in 1..65535)
        assertEquals(200, request("GET", "/v1/ping").status)
    }

    @Test
    fun `stop closes a client blocked on a partial request`() {
        Socket(loopback, server.boundPort).use { socket ->
            socket.soTimeout = 2_000
            socket.getOutputStream().apply {
                write("GET /v1/ping HTTP/1.1\r\nHost: localhost\r\n".toByteArray(StandardCharsets.US_ASCII))
                flush()
            }

            server.stop()
            val closed = try {
                socket.getInputStream().read() == -1
            } catch (_: SocketException) {
                true
            }
            assertTrue(closed)
            assertFalse(server.isRunning)
        }
    }

    @Test
    fun `every endpoint requires the current nonempty bearer token`() {
        Files.writeString(root.resolve("one.txt"), "one")
        val targets = listOf(
            "/v1/ping",
            "/v1/ls",
            "/v1/stat?path=one.txt",
            "/v1/find",
            "/v1/file?path=one.txt",
        )

        targets.forEach { target ->
            val missing = request("GET", target, authenticate = false)
            assertEquals(401, missing.status, target)
            assertTrue(missing.text.contains("\"error\":\"unauthorized\""), target)
        }

        assertEquals(401, request("GET", "/v1/ping", bearer = "wrong").status)
        assertEquals(200, request("GET", "/v1/ping").status)

        token = "rotated-token"
        assertEquals(401, request("GET", "/v1/ping", bearer = "test-token").status)
        assertEquals(200, request("GET", "/v1/ping", bearer = "rotated-token").status)

        token = ""
        assertEquals(401, request("GET", "/v1/ping", bearer = "").status)
        assertEquals(401, request("GET", "/v1/ping", bearer = "rotated-token").status)
    }

    @Test
    fun `ping contains the fixed protocol fields and supplied app information`() {
        val response = request("GET", "/v1/ping")

        assertEquals(200, response.status)
        assertEquals("application/json; charset=utf-8", response.headers["content-type"])
        assertTrue(response.text.contains("\"app\":\"tentacle\""))
        assertTrue(response.text.contains("\"api\":1"))
        assertTrue(response.text.contains("\"version\":\"0.1.0-test\""))
        assertTrue(response.text.contains("\"device\":\"Test Device\""))
        assertTrue(response.text.contains("\"android_sdk\":35"))
        assertTrue(response.text.contains("\"all_files_access\":true"))
        assertTrue(response.text.contains("\"id\":\"primary\""))
        assertTrue(Regex("\\\"time_ms\\\":\\d+").containsMatchIn(response.text))
        assertTrue(response.text.contains("\"timezone\":\"Asia/Shanghai\""))
    }

    @Test
    fun `ls stat and find return contract JSON`() {
        Files.createDirectory(root.resolve("Calls"))
        val audio = Files.write(root.resolve("Calls/voice.AAC"), byteArrayOf(1, 2, 3))
        Files.setLastModifiedTime(audio, java.nio.file.attribute.FileTime.fromMillis(1_763_371_721_000))

        val listing = request("GET", "/v1/ls?path=Calls")
        assertEquals(200, listing.status)
        assertTrue(listing.text.contains("\"path\":\"${jsonEscaped(root.resolve("Calls").toFile().canonicalPath)}\""))
        assertTrue(listing.text.contains("\"name\":\"voice.AAC\""))
        assertTrue(listing.text.contains("\"size\":3"))

        val stat = request("GET", "/v1/stat?path=Calls%2Fvoice.AAC")
        assertEquals(200, stat.status)
        assertTrue(stat.text.contains("\"type\":\"file\""))
        assertTrue(stat.text.contains("\"mtime_ms\":1763371721000"))

        val find = request("GET", "/v1/find?root=Calls&ext=aac&type=file&sort=name&limit=1")
        assertEquals(200, find.status)
        assertTrue(find.text.contains("\"root\":\"${jsonEscaped(root.resolve("Calls").toFile().canonicalPath)}\""))
        assertTrue(find.text.contains("\"name\":\"voice.AAC\""))
        assertTrue(find.text.contains("\"truncated\":false"))
    }

    @Test
    fun `query decoding accepts UTF-8 percent escapes plus spaces and escaped plus signs`() {
        val name = "微信录音 张三+测试_20261117172841.aac"
        val bytes = "你好".toByteArray(StandardCharsets.UTF_8)
        Files.write(root.resolve(name), bytes)
        val encoded = URLEncoder.encode(name, StandardCharsets.UTF_8)

        val stat = request("GET", "/v1/stat?path=$encoded")
        assertEquals(200, stat.status)
        assertTrue(stat.text.contains("\"name\":\"$name\""))

        val file = request("GET", "/v1/file?path=$encoded")
        assertEquals(200, file.status)
        assertTrue(file.body.contentEquals(bytes))

        val find = request(
            "GET",
            "/v1/find?name=${URLEncoder.encode("张三+测试", StandardCharsets.UTF_8)}",
        )
        assertEquals(200, find.status)
        assertTrue(find.text.contains(name))
    }

    @Test
    fun `file endpoint streams complete content with metadata`() {
        val file = Files.writeString(root.resolve("sample.aac"), "0123456789")
        Files.setLastModifiedTime(file, java.nio.file.attribute.FileTime.fromMillis(1_700_000_000_000))

        val response = request("GET", "/v1/file?path=sample.aac")

        assertEquals(200, response.status)
        assertEquals("0123456789", response.text)
        assertEquals("10", response.headers["content-length"])
        assertEquals("bytes", response.headers["accept-ranges"])
        assertEquals("\"10-1700000000000\"", response.headers["etag"])
        assertEquals("audio/aac", response.headers["content-type"])
        assertTrue(response.headers.containsKey("last-modified"))
    }

    @Test
    fun `file endpoint streams content larger than its internal buffer`() {
        val content = ByteArray(128 * 1024) { index -> (index % 251).toByte() }
        Files.write(root.resolve("large.bin"), content)

        val response = request("GET", "/v1/file?path=large.bin")

        assertEquals(200, response.status)
        assertEquals(content.size.toString(), response.headers["content-length"])
        assertTrue(response.body.contentEquals(content))
    }

    @Test
    fun `zero byte files support full GET and HEAD while every range is unsatisfiable`() {
        Files.createFile(root.resolve("empty.bin"))

        val full = request("GET", "/v1/file?path=empty.bin")
        assertEquals(200, full.status)
        assertEquals("0", full.headers["content-length"])
        assertTrue(full.body.isEmpty())

        val head = request("HEAD", "/v1/file?path=empty.bin")
        assertEquals(200, head.status)
        assertEquals("0", head.headers["content-length"])
        assertTrue(head.body.isEmpty())

        listOf("bytes=0-", "bytes=-1", "bytes=0-0").forEach { range ->
            val response = request(
                "GET",
                "/v1/file?path=empty.bin",
                headers = mapOf("Range" to range),
            )
            assertEquals(416, response.status, range)
            assertEquals("bytes */0", response.headers["content-range"], range)
        }
    }

    @Test
    fun `file endpoint supports closed open and suffix ranges`() {
        Files.writeString(root.resolve("sample.bin"), "0123456789")

        assertRange("bytes=2-5", 2, 5, "2345")
        assertRange("bytes=6-", 6, 9, "6789")
        assertRange("bytes=-3", 7, 9, "789")
        assertRange("bytes=0-0", 0, 0, "0")
        assertRange("bytes=-99", 0, 9, "0123456789")
        assertRange("bytes=7-99", 7, 9, "789")
    }

    @Test
    fun `unsatisfiable and malformed ranges return 416 with total size`() {
        Files.writeString(root.resolve("sample.bin"), "0123456789")

        listOf("bytes=10-", "bytes=8-4", "bytes=-0", "bytes=abc", "bytes=0-1,3-4").forEach { range ->
            val response = request(
                "GET",
                "/v1/file?path=sample.bin",
                headers = mapOf("Range" to range),
            )
            assertEquals(416, response.status, range)
            assertEquals("bytes */10", response.headers["content-range"], range)
            assertTrue(response.text.contains("\"error\":\"range_not_satisfiable\""), range)
        }
    }

    @Test
    fun `HEAD returns file headers and no body for full and ranged requests`() {
        Files.writeString(root.resolve("sample.bin"), "0123456789")

        val full = request("HEAD", "/v1/file?path=sample.bin")
        assertEquals(200, full.status)
        assertEquals("10", full.headers["content-length"])
        assertTrue(full.body.isEmpty())

        val partial = request(
            "HEAD",
            "/v1/file?path=sample.bin",
            headers = mapOf("Range" to "bytes=2-5"),
        )
        assertEquals(206, partial.status)
        assertEquals("4", partial.headers["content-length"])
        assertEquals("bytes 2-5/10", partial.headers["content-range"])
        assertTrue(partial.body.isEmpty())
    }

    @Test
    fun `unknown paths unsupported methods and bad query values use JSON errors`() {
        assertEquals(404, request("GET", "/v1/unknown").status)
        assertEquals(405, request("POST", "/v1/ping", body = "ignored").status)

        val bad = request("GET", "/v1/find?limit=not-a-number")
        assertEquals(400, bad.status)
        assertTrue(bad.text.contains("\"error\":\"bad_request\""))
    }

    @Test
    fun `invalid query encoding and find filter values return bad request`() {
        val invalidQueries = listOf(
            "/v1/stat?path=%GG",
            "/v1/stat?path=%C3%28",
            "/v1/ls?hidden=2",
            "/v1/find?type=socket",
            "/v1/find?max_depth=-1",
            "/v1/find?limit=0",
            "/v1/find?limit=5001",
            "/v1/find?sort=random",
            "/v1/find?since_ms=2&until_ms=1",
            "/v1/find?ext=.aac",
            "/v1/find?ext=aac,,mp3",
            "/v1/find?ext=aac%2Fmp3",
            "/v1/find?ext=aac%5Cmp3",
            "/v1/find?ext=aac,%2Fslash",
        )

        invalidQueries.forEach { target ->
            val response = request("GET", target)
            assertEquals(400, response.status, target)
            assertTrue(response.text.contains("\"error\":\"bad_request\""), target)
        }
    }

    @Test
    fun `request line and headers have bounded size`() {
        val longPath = "/v1/ping?padding=" + "x".repeat(9_000)
        assertTrue(request("GET", longPath).status in setOf(400, 431))

        val oversizedHeader = request("GET", "/v1/ping", headers = mapOf("X-Padding" to "x".repeat(9_000)))
        assertTrue(oversizedHeader.status in setOf(400, 431))
    }

    @Test
    fun `logger never receives bearer token`() {
        val messages = mutableListOf<String>()
        server.logger = { synchronized(messages) { messages += it } }

        request("GET", "/v1/ping", bearer = "wrong-secret-value")
        request("GET", "/v1/missing")

        assertFalse(messages.joinToString("\n").contains("test-token"))
        assertFalse(messages.joinToString("\n").contains("wrong-secret-value"))
    }

    private fun assertRange(range: String, start: Int, end: Int, expected: String) {
        val response = request(
            "GET",
            "/v1/file?path=sample.bin",
            headers = mapOf("Range" to range),
        )
        assertEquals(206, response.status, range)
        assertEquals(expected, response.text, range)
        assertEquals("bytes $start-$end/10", response.headers["content-range"], range)
        assertEquals(expected.toByteArray().size.toString(), response.headers["content-length"], range)
    }

    private fun request(
        method: String,
        target: String,
        authenticate: Boolean = true,
        bearer: String? = null,
        headers: Map<String, String> = emptyMap(),
        body: String = "",
    ): HttpResponse {
        Socket(loopback, server.boundPort).use { socket ->
            socket.soTimeout = 5_000
            val bodyBytes = body.toByteArray(StandardCharsets.UTF_8)
            val request = buildString {
                append(method).append(' ').append(target).append(" HTTP/1.1\r\n")
                append("Host: localhost\r\n")
                append("Connection: close\r\n")
                if (authenticate) append("Authorization: Bearer ").append(bearer ?: token).append("\r\n")
                headers.forEach { (name, value) -> append(name).append(": ").append(value).append("\r\n") }
                if (bodyBytes.isNotEmpty()) append("Content-Length: ").append(bodyBytes.size).append("\r\n")
                append("\r\n")
            }.toByteArray(StandardCharsets.ISO_8859_1)
            socket.getOutputStream().apply {
                write(request)
                write(bodyBytes)
                flush()
            }

            val received = ByteArrayOutputStream()
            socket.getInputStream().copyTo(received)
            return parseResponse(received.toByteArray())
        }
    }

    private fun parseResponse(bytes: ByteArray): HttpResponse {
        val separator = byteArrayOf(13, 10, 13, 10)
        val headerEnd = bytes.indexOfSubsequence(separator)
        assertNotEquals(-1, headerEnd, "response did not contain a header terminator")
        val headerText = String(bytes, 0, headerEnd, StandardCharsets.ISO_8859_1)
        val lines = headerText.split("\r\n")
        val status = lines.first().split(' ')[1].toInt()
        val headers = lines.drop(1).associate { line ->
            val colon = line.indexOf(':')
            line.substring(0, colon).lowercase(Locale.ROOT) to line.substring(colon + 1).trim()
        }
        val bodyStart = headerEnd + separator.size
        return HttpResponse(status, headers, bytes.copyOfRange(bodyStart, bytes.size))
    }

    private fun ByteArray.indexOfSubsequence(needle: ByteArray): Int {
        for (start in 0..size - needle.size) {
            if (needle.indices.all { this[start + it] == needle[it] }) return start
        }
        return -1
    }

    private fun jsonEscaped(value: String): String = value.replace("\\", "\\\\").replace("\"", "\\\"")

    private data class HttpResponse(
        val status: Int,
        val headers: Map<String, String>,
        val body: ByteArray,
    ) {
        val text: String get() = body.toString(StandardCharsets.UTF_8)
    }
}
