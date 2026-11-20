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
    private lateinit var requests: RequestStore
    private val loopback: String = InetAddress.getLoopbackAddress().hostAddress
    private var token = "test-token"

    @BeforeEach
    fun startServer() {
        root = Files.createDirectory(tempDir.resolve("primary"))
        requests = RequestStore(tempDir.resolve("requests").toFile())
        server = HttpServer(
            port = 0,
            bind = loopback,
            tokenProvider = { token },
            api = FileApi(listOf(StorageRoot("primary", root.toFile(), "Internal"))),
            requestStore = requests,
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
        assertFalse(response.text.contains("\"tailnet\""))
    }

    @Test
    fun `a fixed port can be restarted immediately and repeatedly`() {
        val port = java.net.ServerSocket(0).use { it.localPort }
        val fixed = HttpServer(port = port, bind = loopback, tokenProvider = { token },
            api = FileApi(listOf(StorageRoot("primary", root.toFile(), "Internal"))),
            info = { PingInfo("t", "d", 35, true, emptyList(), "UTC") })
        try {
            repeat(20) {
                fixed.start()
                Socket(loopback, port).close()
                fixed.stop()
            }
            fixed.start()
            assertEquals(port, fixed.boundPort)
        } finally {
            fixed.stop()
        }
    }

    @Test
    fun `ping info adds the optional tailnet summary only when supplied`() {
        val info = PingInfo("0.3.0", "Test Device", 35, true, emptyList(), "UTC",
            tailnet = linkedMapOf("state" to "running", "ip" to "100.64.0.1"))
        assertEquals("{\"app\":\"tentacle\",\"api\":1,\"version\":\"0.3.0\",\"device\":\"Test Device\"," +
            "\"android_sdk\":35,\"roots\":[],\"all_files_access\":true,\"time_ms\":5,\"timezone\":\"UTC\"," +
            "\"tailnet\":{\"state\":\"running\",\"ip\":\"100.64.0.1\"}}", Json.stringify(info.toJson(5)))
    }

    @Test
    fun `ping info adds current LAN base URLs only when supplied`() {
        val info = PingInfo("0.3.1", "Test Device", 35, true, emptyList(), "UTC",
            lan = listOf("http://192.168.0.5:8765"))
        assertEquals("{\"app\":\"tentacle\",\"api\":1,\"version\":\"0.3.1\",\"device\":\"Test Device\"," +
            "\"android_sdk\":35,\"roots\":[],\"all_files_access\":true,\"time_ms\":5,\"timezone\":\"UTC\"," +
            "\"lan\":[\"http://192.168.0.5:8765\"]}", Json.stringify(info.toJson(5)))
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

    @Test
    fun `request endpoints require authentication and remain absent without a store`() {
        val item = requests.create("bug", "", "diagnostic log")
        listOf("/v1/requests", "/v1/requests/${item.id}", "/v1/requests/${item.id}/log").forEach { target ->
            assertEquals(401, request(if (target.endsWith(item.id)) "POST" else "GET", target,
                authenticate = false, body = "{}").status)
        }
        val disabled = HttpServer(0, loopback, { token },
            FileApi(listOf(StorageRoot("primary", root.toFile(), "Internal"))),
            { PingInfo("test", "device", 35, true, emptyList(), "UTC") })
        disabled.start()
        try {
            listOf("GET /v1/requests", "POST /v1/requests/${item.id}", "GET /v1/requests/${item.id}/log").forEach { route ->
                Socket(loopback, disabled.boundPort).use { socket ->
                    socket.soTimeout = 2000
                    socket.getOutputStream().write(("$route HTTP/1.1\r\n" +
                        "Authorization: Bearer $token\r\n\r\n").toByteArray())
                    assertEquals(404, parseResponse(socket.getInputStream().readBytes()).status, route)
                }
            }
        } finally { disabled.stop() }
    }

    @Test
    fun `request list status transitions and attached log follow the contract`() {
        val item = requests.create("bug", "Fix the crash", "diagnostic 日志")
        val listing = request("GET", "/v1/requests")
        assertEquals(200, listing.status)
        val list = Json.parse(listing.text) as Map<*, *>
        assertEquals(item.id, ((list["requests"] as List<*>).first() as Map<*, *>)["id"])
        assertTrue((list["now_ms"] as Number).toLong() > 0)
        assertTrue(requests.lastPolledMs > 0)
        val log = request("GET", "/v1/requests/${item.id}/log")
        assertEquals(200, log.status)
        assertEquals("text/plain; charset=utf-8", log.headers["content-type"])
        assertEquals("diagnostic 日志", log.text)
        fun update(status: String) = request("POST", "/v1/requests/${item.id}",
            body = Json.stringify(mapOf("status" to status, "message" to "Result")))
        assertEquals(409, update("done").status)
        assertEquals(200, update("working").status)
        assertEquals(409, update("working").status)
        assertEquals(200, update("new").status)
        assertEquals(200, update("working").status)
        assertEquals(200, update("done").status)
        assertEquals(409, update("failed").status)
        val final = Json.parse(update("new").text) as Map<*, *>
        assertEquals("conflict", final["error"])
        assertEquals(404, request("POST", "/v1/requests/unknown", body =
            "{\"status\":\"working\",\"message\":\"\"}").status)
        assertEquals(404, request("GET", "/v1/requests/unknown/log").status)
        val noLog = requests.create("feature", "A change")
        assertEquals(404, request("GET", "/v1/requests/${noLog.id}/log").status)
    }

    @Test
    fun `request updates reject malformed bodies invalid status and oversized message`() {
        val item = requests.create("feature", "A change")
        listOf("{", "[]", "null", "{}", "{\"status\":42,\"message\":\"\"}",
            "{\"status\":\"other\",\"message\":\"\"}", "{\"status\":\"working\",\"message\":null}",
            Json.stringify(mapOf("status" to "working", "message" to "x".repeat(1001))),
            "[".repeat(500) + "]".repeat(500)).forEach { body ->
            assertEquals(400, request("POST", "/v1/requests/${item.id}", body = body).status, body.take(100))
        }
        assertEquals(413, request("POST", "/v1/requests/${item.id}", body = "x".repeat(16385)).status)
        assertEquals(411, request("POST", "/v1/requests/${item.id}").status)
        assertEquals(400, request("POST", "/v1/requests/${item.id}", headers = mapOf("Transfer-Encoding" to "chunked")).status)
        assertEquals(400, request("POST", "/v1/requests/${item.id}", headers = mapOf("Content-Length" to "-1")).status)
        assertEquals(400, request("POST", "/v1/requests/${item.id}", headers = mapOf("Content-Type" to "text/plain"), body = "{}").status)
        listOf("/v1/ping", "/v1/ls", "/v1/requests", "/v1/requests/${item.id}/log", "/unknown").forEach {
            assertEquals(405, request("POST", it, body = "{}").status, it)
        }
        listOf("-1", "61", "oops").forEach {
            assertEquals(400, request("GET", "/v1/requests?wait=$it").status)
        }
        assertTrue(requests.list().all { it.status == "new" })
    }

    @Test
    fun `request body framing rejects invalid UTF-8 truncated bytes and duplicate lengths`() {
        val item = requests.create("feature", "A change")
        fun exchange(extraHeaders: String, body: ByteArray): HttpResponse {
            Socket(loopback, server.boundPort).use { socket ->
                socket.soTimeout = 2000
                socket.getOutputStream().apply {
                    write(("POST /v1/requests/${item.id} HTTP/1.1\r\n" +
                        "Authorization: Bearer $token\r\nContent-Type: application/json\r\n" +
                        extraHeaders + "\r\n").toByteArray())
                    write(body)
                    flush()
                }
                socket.shutdownOutput()
                return parseResponse(socket.getInputStream().readBytes())
            }
        }
        assertEquals(400, exchange("Content-Length: 1\r\n", byteArrayOf(0xff.toByte())).status)
        assertEquals(400, exchange("Content-Length: 100\r\n", "{}".toByteArray()).status)
        assertEquals(400, exchange("Content-Length: 2\r\nContent-Length: 2\r\n", "{}".toByteArray()).status)
        assertEquals(413, exchange("Content-Length: 99999999999999999999999999\r\n", byteArrayOf()).status)
        val valid = "{\"status\":\"working\",\"message\":\"\"}".padEnd(16 * 1024)
        assertEquals(200, exchange("Content-Length: 16384\r\n", valid.toByteArray()).status)
    }

    @Test
    fun `long poll wakes on create and returns at timeout`() {
        val pool = java.util.concurrent.Executors.newSingleThreadExecutor()
        try {
            val polled = java.util.concurrent.CountDownLatch(1)
            requests.observe { if (requests.lastPolledMs > 0) polled.countDown() }.use {
                val pending = pool.submit<HttpResponse> { request("GET", "/v1/requests?wait=3") }
                assertTrue(polled.await(1, java.util.concurrent.TimeUnit.SECONDS))
                assertFalse(pending.isDone)
                val item = requests.create("feature", "Wake up")
                assertTrue(pending.get(1, java.util.concurrent.TimeUnit.SECONDS).text.contains(item.id))
                requests.update(item.id, "failed", "test finished")
            }
            val start = System.nanoTime()
            assertEquals(200, request("GET", "/v1/requests?wait=1").status)
            val elapsed = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)
            assertTrue(elapsed in 800..2500, "elapsed=$elapsed")
        } finally { pool.shutdownNow() }
    }

    @Test
    fun `many long polls leave workers available for file listing and updates`() {
        val pool = java.util.concurrent.Executors.newFixedThreadPool(12)
        try {
            val polls = (1..12).map { pool.submit<HttpResponse> { request("GET", "/v1/requests?wait=4") } }
            // Extra long polls must return immediately while two calls retain their wait slots.
            val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(2)
            while (polls.count { it.isDone } < 10 && System.nanoTime() < deadline) Thread.sleep(10)
            assertTrue(polls.count { it.isDone } >= 10)
            val listing = pool.submit<HttpResponse> { request("GET", "/v1/ls") }
            assertEquals(200, listing.get(1, java.util.concurrent.TimeUnit.SECONDS).status)
            requests.create("feature", "Release all waiters")
            polls.forEach { assertEquals(200, it.get(1, java.util.concurrent.TimeUnit.SECONDS).status) }
        } finally { pool.shutdownNow() }
    }

    @Test
    fun `access logger receives status body bytes duration and path without query or headers`() {
        val records = java.util.concurrent.LinkedBlockingQueue<List<Any>>()
        server.accessLogger = { method, path, status, bytes, duration ->
            records.add(listOf(method, path, status, bytes, duration))
        }
        val response = request("GET", "/v1/ls?path=missing-secret&token=query-secret")
        val record = records.poll(1, java.util.concurrent.TimeUnit.SECONDS)
        assertEquals(listOf("GET", "/v1/ls", 404, response.body.size.toLong()), record.take(4))
        assertTrue(record[4] as Long >= 0)
        assertFalse(record.toString().contains("secret"))
        request("GET", "/v1/requests", authenticate = false)
        assertEquals(401, records.poll(1, java.util.concurrent.TimeUnit.SECONDS)[2])
        server.accessLogger = { _, _, _, _, _ -> throw IllegalStateException("Logging failed") }
        assertEquals(200, request("GET", "/v1/ls").status)
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
                if (method == "POST" && headers.keys.none { it.equals("Content-Type", true) }) {
                    append("Content-Type: application/json\r\n")
                }
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
