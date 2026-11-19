package dev.tentacle.core

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

class HttpServer(
    private val port: Int,
    private val bind: String = "0.0.0.0",
    private val tokenProvider: () -> String,
    private val api: FileApi,
    private val info: () -> PingInfo,
) {
    @Volatile var logger: (String) -> Unit = {}
    @Volatile private var listener: ServerSocket? = null
    private var workers: ThreadPoolExecutor? = null
    private var acceptThread: Thread? = null
    private val clients = ConcurrentHashMap.newKeySet<Socket>()

    val isRunning: Boolean get() = listener?.let { !it.isClosed } ?: false
    val boundPort: Int get() = listener?.localPort ?: -1

    @Synchronized fun start() {
        if (isRunning) return
        val socket = ServerSocket()
        try {
            socket.reuseAddress = true
            bindWithRetry(socket)
            val pool = ThreadPoolExecutor(
                8, 8, 0, TimeUnit.MILLISECONDS, ArrayBlockingQueue(32),
                { task -> Thread(task, "tentacle-http-worker").apply { isDaemon = true } },
            )
            listener = socket
            workers = pool
            acceptThread = Thread({ accept(socket, pool) }, "tentacle-http-accept").apply {
                isDaemon = true
                start()
            }
            log("HTTP server started")
        } catch (e: Exception) {
            socket.close()
            listener = null
            workers?.shutdownNow()
            workers = null
            throw e
        }
    }

    @Synchronized fun stop() {
        listener?.close()
        listener = null
        // Closing clients also interrupts reads and writes, including queued connections.
        clients.forEach { close(it) }
        workers?.shutdownNow()
        workers = null
        // Android releases a listening socket only once the thread blocked in
        // accept() has been woken; wait for it so an immediate restart can bind.
        acceptThread?.let { if (it !== Thread.currentThread()) it.join(2_000) }
        acceptThread = null
        log("HTTP server stopped")
    }

    /** A just-closed listener on the same port can linger briefly (restart); retry for up to 2 s. */
    private fun bindWithRetry(socket: ServerSocket) {
        var attempt = 0
        while (true) {
            try {
                socket.bind(InetSocketAddress(bind, port))
                return
            } catch (e: java.net.BindException) {
                if (port == 0 || ++attempt >= 10) throw e
                Thread.sleep(200)
            }
        }
    }

    private fun accept(socket: ServerSocket, pool: ThreadPoolExecutor) {
        while (!socket.isClosed) {
            val client = try {
                socket.accept()
            } catch (e: IOException) {
                if (!socket.isClosed) log("HTTP accept failed")
                break
            }
            clients.add(client)
            try {
                client.soTimeout = 5000
                if (socket.isClosed) close(client)
                else pool.execute { serve(client) }
            } catch (e: RejectedExecutionException) {
                close(client)
            } catch (e: IOException) {
                close(client)
            }
        }
    }

    private fun serve(socket: Socket) {
        var head = false
        var committed = false
        try {
            val input = BufferedInputStream(socket.getInputStream())
            val output = socket.getOutputStream().buffered()
            try {
                val request = readRequest(input)
                head = request.method == "HEAD"
                authenticate(request.headers["authorization"])
                val route = request.target.substringBefore('?')
                if (request.method != "GET" && !(head && route == "/v1/file")) {
                    throw ApiException(405, "method_not_allowed", "Method is not allowed", mapOf(
                        "Allow" to if (route == "/v1/file") "GET, HEAD" else "GET",
                    ))
                }
                if (route !in setOf("/v1/ping", "/v1/ls", "/v1/stat", "/v1/find", "/v1/file")) {
                    throw ApiException(404, "not_found")
                }
                val query = parseQuery(request.target.substringAfter('?', ""))
                if (route == "/v1/file") {
                    api.openFile(query["path"] ?: "", request.headers["range"]).use { file ->
                        val headers = linkedMapOf(
                            "Content-Type" to file.contentType,
                            "Content-Length" to file.length.toString(),
                            "Last-Modified" to httpDate(file.mtimeMs),
                            "ETag" to "\"${file.size}-${file.mtimeMs}\"",
                            "Accept-Ranges" to "bytes",
                        )
                        if (file.partial) headers["Content-Range"] =
                            "bytes ${file.offset}-${file.offset + file.length - 1}/${file.size}"
                        committed = true
                        writeHeaders(output, if (file.partial) 206 else 200, headers)
                        if (!head) file.writeTo(output)
                    }
                } else {
                    val result = when (route) {
                        "/v1/ping" -> info().toJson()
                        "/v1/ls" -> api.ls(query["path"] ?: "", query.flag("hidden")).toJson()
                        "/v1/stat" -> api.stat(query["path"] ?: "").toJson()
                        else -> api.find(FindQuery(
                            root = query["root"] ?: "",
                            ext = query["ext"]?.split(',') ?: emptyList(),
                            name = query["name"], sinceMs = query.number("since_ms"),
                            untilMs = query.number("until_ms"), type = query["type"] ?: "file",
                            maxDepth = query.integer("max_depth", 12), limit = query.integer("limit", 500),
                            sort = query["sort"] ?: "mtime_desc", hidden = query.flag("hidden"),
                        )).toJson()
                    }
                    val body = Json.stringify(result).toByteArray(Charsets.UTF_8)
                    committed = true
                    writeJson(output, 200, body, head)
                }
            } catch (e: ApiException) {
                if (!committed) {
                    committed = true
                    writeError(output, e, head)
                }
            } catch (e: SocketTimeoutException) {
                if (!committed) {
                    committed = true
                    writeError(output, ApiException(400, "bad_request", "Request timed out"), head)
                }
            } catch (e: Exception) {
                if (!committed) {
                    committed = true
                    writeError(output, ApiException(500, "internal", "Internal server error"), head)
                }
                log("HTTP request failed")
            }
            output.flush()
        } catch (e: IOException) {
            log("HTTP connection closed")
        } finally {
            close(socket)
        }
    }

    private fun authenticate(header: String?) {
        val token = tokenProvider()
        val supplied = header?.takeIf { it.startsWith("Bearer ", ignoreCase = true) }?.substring(7) ?: ""
        val equal = MessageDigest.isEqual(token.toByteArray(Charsets.UTF_8), supplied.toByteArray(Charsets.UTF_8))
        if (!equal || token.isEmpty()) throw ApiException(401, "unauthorized")
    }

    private data class Request(val method: String, val target: String, val headers: Map<String, String>)

    private fun readRequest(input: InputStream): Request {
        val line = readLine(input, 8192, 400)
        val parts = line.split(' ')
        if (parts.size != 3 || !TOKEN.matches(parts[0]) || !parts[1].startsWith('/') || '\t' in parts[1] ||
            '#' in parts[1] || parts[2] !in setOf("HTTP/1.1", "HTTP/1.0")
        ) throw ApiException(400, "bad_request", "Invalid request line")
        var budget = 8192
        val headers = mutableMapOf<String, String>()
        while (true) {
            val header = readLine(input, budget, 431)
            budget -= header.length + 2
            if (header.isEmpty()) break
            val colon = header.indexOf(':')
            if (colon < 1 || !TOKEN.matches(header.substring(0, colon))) {
                throw ApiException(400, "bad_request", "Invalid header")
            }
            val name = header.substring(0, colon).lowercase(Locale.ROOT)
            if (headers.put(name, header.substring(colon + 1).trim()) != null) {
                throw ApiException(400, "bad_request", "Duplicate header")
            }
        }
        return Request(parts[0], parts[1], headers)
    }

    private fun readLine(input: InputStream, limit: Int, exceededStatus: Int): String {
        val line = StringBuilder()
        while (true) {
            if (line.length + 2 > limit) {
                throw ApiException(exceededStatus, "bad_request", "Request is too large")
            }
            val byte = input.read()
            if (byte == '\r'.code) {
                if (input.read() != '\n'.code) throw ApiException(400, "bad_request", "Invalid line ending")
                return line.toString()
            }
            if (byte < 0 || byte > 126 || (byte < 32 && byte != '\t'.code)) {
                throw ApiException(400, "bad_request", "Invalid request character")
            }
            line.append(byte.toChar())
        }
    }

    private fun parseQuery(raw: String): Map<String, String> {
        if (raw.isEmpty()) return emptyMap()
        val result = mutableMapOf<String, String>()
        raw.split('&').filter { it.isNotEmpty() }.forEach { part ->
            val key = decode(part.substringBefore('='))
            val value = decode(part.substringAfter('=', ""))
            if (result.put(key, value) != null) throw ApiException(400, "bad_request", "Duplicate query parameter")
        }
        return result
    }

    private fun decode(raw: String): String {
        val bytes = ByteArrayOutputStream()
        var index = 0
        while (index < raw.length) {
            when (val c = raw[index]) {
                '+' -> bytes.write(' '.code)
                '%' -> {
                    if (index + 2 >= raw.length) throw ApiException(400, "bad_request", "Invalid percent encoding")
                    val high = raw[index + 1].digitToIntOrNull(16)
                    val low = raw[index + 2].digitToIntOrNull(16)
                    if (high == null || low == null) throw ApiException(400, "bad_request", "Invalid percent encoding")
                    bytes.write(high * 16 + low)
                    index += 2
                }
                else -> bytes.write(c.code)
            }
            index++
        }
        return try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes.toByteArray())).toString()
        } catch (e: java.nio.charset.CharacterCodingException) {
            throw ApiException(400, "bad_request", "Invalid UTF-8 query")
        }
    }

    private fun Map<String, String>.flag(key: String): Boolean = when (this[key]) {
        null, "0" -> false
        "1" -> true
        else -> throw ApiException(400, "bad_request", "Invalid $key")
    }

    private fun Map<String, String>.number(key: String): Long? = this[key]?.let {
        it.toLongOrNull() ?: throw ApiException(400, "bad_request", "Invalid $key")
    }

    private fun Map<String, String>.integer(key: String, default: Int): Int = this[key]?.let {
        it.toIntOrNull() ?: throw ApiException(400, "bad_request", "Invalid $key")
    } ?: default

    private fun writeError(output: OutputStream, error: ApiException, head: Boolean) {
        val body = Json.stringify(linkedMapOf("error" to error.code, "message" to error.message)).toByteArray(Charsets.UTF_8)
        writeJson(output, error.status, body, head, error.headers)
    }

    private fun writeJson(
        output: OutputStream, status: Int, body: ByteArray, head: Boolean,
        extra: Map<String, String> = emptyMap(),
    ) {
        writeHeaders(output, status, linkedMapOf(
            "Content-Type" to "application/json; charset=utf-8", "Content-Length" to body.size.toString(),
        ) + extra)
        if (!head) output.write(body)
    }

    private fun writeHeaders(output: OutputStream, status: Int, headers: Map<String, String>) {
        val reason = when (status) {
            200 -> "OK"
            206 -> "Partial Content"
            400 -> "Bad Request"
            401 -> "Unauthorized"
            403 -> "Forbidden"
            404 -> "Not Found"
            405 -> "Method Not Allowed"
            416 -> "Range Not Satisfiable"
            431 -> "Request Header Fields Too Large"
            else -> "Internal Server Error"
        }
        val text = buildString {
            append("HTTP/1.1 $status $reason\r\n")
            headers.forEach { (name, value) -> append("$name: $value\r\n") }
            append("Connection: close\r\n\r\n")
        }
        output.write(text.toByteArray(Charsets.US_ASCII))
        log("HTTP response $status")
    }

    private fun httpDate(timeMs: Long): String = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("GMT")
    }.format(Date(timeMs))

    private fun close(socket: Socket) {
        try { socket.close() } catch (_: IOException) { /* Already disconnected. */ }
        clients.remove(socket)
    }

    private fun log(message: String) {
        try { logger(message) } catch (_: Exception) { /* Logging must not interrupt requests. */ }
    }

    private companion object {
        val TOKEN = Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]+")
    }
}
