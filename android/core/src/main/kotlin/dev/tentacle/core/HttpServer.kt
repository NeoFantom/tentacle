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
import java.util.concurrent.Semaphore
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

class HttpServer(
    private val port: Int,
    private val bind: String = "0.0.0.0",
    private val tokenProvider: () -> String,
    private val api: FileApi,
    private val info: () -> PingInfo,
    private val requestStore: RequestStore? = null,
    private val codeStore: CodeStore? = null,
    private val notifyStore: NotifyStore? = null,
    private val phoneNotificationStore: PhoneNotificationStore? = null,
    private val phoneNotificationStoreProvider: (() -> PhoneNotificationStore)? = null,
) {
    @Volatile var logger: (String) -> Unit = {}
    @Volatile var accessLogger: (String, String, Int, Long, Long) -> Unit = { _, _, _, _, _ -> }
    private val longPollSlots = Semaphore(2)
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
        val started = System.nanoTime()
        var method = ""
        var route = ""
        var response: ResponseOutput? = null
        var head = false
        var committed = false
        try {
            val input = BufferedInputStream(socket.getInputStream())
            val output = ResponseOutput(socket.getOutputStream().buffered())
            response = output
            try {
                val request = readRequest(input)
                method = request.method
                route = request.target.substringBefore('?')
                head = request.method == "HEAD"
                authenticate(request.headers["authorization"])
                val updateId = REQUEST_UPDATE.matchEntire(route)?.groupValues?.get(1)
                val logId = REQUEST_LOG.matchEntire(route)?.groupValues?.get(1)
                val notifyId = NOTIFY_ITEM.matchEntire(route)?.groupValues?.get(1)
                if (request.method != "GET" && !(head && route == "/v1/file") &&
                    !(request.method == "POST" && (updateId != null || route == "/v1/notify"))) {
                    throw ApiException(405, "method_not_allowed", "Method is not allowed", mapOf(
                        "Allow" to when {
                            route == "/v1/file" -> "GET, HEAD"
                            updateId != null || route == "/v1/notify" -> "POST"
                            else -> "GET"
                        },
                    ))
                }
                if (route == "/v1/notify" && request.method != "POST") {
                    throw ApiException(405, "method_not_allowed", "Method is not allowed", mapOf("Allow" to "POST"))
                }
                if ((route !in setOf("/v1/ping", "/v1/ls", "/v1/stat", "/v1/find", "/v1/file", "/v1/requests", "/v1/codes", "/v1/notifications", "/v1/notify") &&
                    updateId == null && logId == null && notifyId == null) ||
                    (updateId != null && request.method != "POST")) {
                    throw ApiException(404, "not_found")
                }
                val query = parseQuery(request.target.substringAfter('?', ""))
                if (route == "/v1/notify" || notifyId != null) {
                    val store = notifyStore ?: throw ApiException(404, "not_found")
                    val result = if (route == "/v1/notify") {
                        val body = readJsonBody(input, request.headers) as? Map<*, *>
                            ?: throw ApiException(400, "bad_request", "Expected a JSON object")
                        val text = body["text"] as? String
                            ?: throw ApiException(400, "bad_request", "Provide notification text")
                        val title = body.optionalString("title")
                        val level = body.optionalString("level")
                        val id = body.optionalString("id")
                        store.post(title, text, level, id).postJson()
                    } else {
                        val wait = query.integer("wait", 0)
                        if (wait !in 0..60) {
                            throw ApiException(400, "bad_request", "Wait must be between 0 and 60 seconds")
                        }
                        val entry = store.get(notifyId!!)
                        if (wait > 0 && entry.state == "shown" && longPollSlots.tryAcquire()) {
                            try { store.awaitChange(notifyId, wait * 1000L) } finally { longPollSlots.release() }
                        } else {
                            entry
                        }.stateJson()
                    }
                    committed = true
                    writeJson(output, 200, Json.stringify(result).toByteArray(Charsets.UTF_8), false)
                } else if (route == "/v1/notifications") {
                    // Persistent feed state may touch disk, so resolve it lazily on this HTTP worker.
                    val store = phoneNotificationStore ?: phoneNotificationStoreProvider?.invoke()
                        ?: throw ApiException(404, "not_found")
                    val since = query.number("since") ?: 0L
                    if (since < 0) throw ApiException(400, "bad_request", "Since must not be negative")
                    val wait = query.integer("wait", 0)
                    if (wait !in 0..60) {
                        throw ApiException(400, "bad_request", "Wait must be between 0 and 60 seconds")
                    }
                    val limit = query.integer("limit", 500)
                    if (limit !in 1..500) {
                        throw ApiException(400, "bad_request", "Limit must be between 1 and 500")
                    }
                    val app = query["app"]
                    val initiallyEnabled = store.isEnabled
                    val found = if (!initiallyEnabled) {
                        emptyList()
                    } else if (wait > 0 && longPollSlots.tryAcquire()) {
                        try {
                            store.awaitNewerThan(since, wait * 1000L, app, limit)
                        } finally {
                            longPollSlots.release()
                        }
                    } else {
                        store.query(since, app, limit)
                    }
                    val enabled = store.isEnabled
                    val notifications = if (enabled) found else emptyList()
                    val result = linkedMapOf(
                        "notifications" to notifications.map { it.toJson() },
                        "now_ms" to System.currentTimeMillis(),
                        "enabled" to enabled,
                    )
                    committed = true
                    writeJson(output, 200, Json.stringify(result).toByteArray(Charsets.UTF_8), false)
                } else if (route == "/v1/codes") {
                    val store = codeStore ?: throw ApiException(404, "not_found")
                    val since = query.number("since") ?: 0L
                    if (since < 0) throw ApiException(400, "bad_request", "Since must not be negative")
                    val wait = query.integer("wait", 0)
                    if (wait !in 0..60) throw ApiException(400, "bad_request", "Wait must be between 0 and 60 seconds")
                    val initiallyEnabled = store.isEnabled
                    val found = if (!initiallyEnabled) {
                        emptyList()
                    } else if (wait > 0 && longPollSlots.tryAcquire()) {
                        try { store.awaitAfter(since, wait * 1000L) } finally { longPollSlots.release() }
                    } else {
                        store.listAfter(since)
                    }
                    val enabled = store.isEnabled
                    val codes = if (enabled) found else emptyList()
                    val result = linkedMapOf(
                        "codes" to codes.map { it.toJson() },
                        "now_ms" to System.currentTimeMillis(),
                        "enabled" to enabled,
                    )
                    committed = true
                    writeJson(output, 200, Json.stringify(result).toByteArray(Charsets.UTF_8), false)
                } else if (route == "/v1/requests" || updateId != null || logId != null) {
                    val store = requestStore ?: throw ApiException(404, "not_found")
                    if (logId != null) {
                        val body = store.log(logId).toByteArray(Charsets.UTF_8)
                        committed = true
                        writeHeaders(output, 200, linkedMapOf(
                            "Content-Type" to "text/plain; charset=utf-8", "Content-Length" to body.size.toString(),
                        ))
                        output.write(body)
                    } else {
                        val result = if (updateId != null) {
                            val body = readJsonBody(input, request.headers) as? Map<*, *>
                                ?: throw ApiException(400, "bad_request", "Expected a JSON object")
                            val status = body["status"] as? String
                                ?: throw ApiException(400, "bad_request", "Provide a request status")
                            val message = body["message"] as? String
                                ?: throw ApiException(400, "bad_request", "Provide a plain text message")
                            store.update(updateId, status, message).toJson()
                        } else {
                            val wait = query.integer("wait", 0)
                            if (wait !in 0..60) throw ApiException(400, "bad_request", "Wait must be between 0 and 60 seconds")
                            store.recordPoll()
                            // At most two workers wait; file requests and updates always retain capacity.
                            if (wait > 0 && longPollSlots.tryAcquire()) {
                                try { store.awaitNew(wait * 1000L) } finally { longPollSlots.release() }
                            }
                            linkedMapOf("requests" to store.list().map { it.toJson() }, "now_ms" to System.currentTimeMillis())
                        }
                        committed = true
                        writeJson(output, 200, Json.stringify(result).toByteArray(Charsets.UTF_8), false)
                    }
                } else if (route == "/v1/file") {
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
            try {
                accessLogger(method, route, response?.status ?: 400, response?.bodyBytes ?: 0,
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started))
            } catch (_: Exception) { /* Logging must not interrupt requests. */ }
        }
    }

    private fun readJsonBody(input: InputStream, headers: Map<String, String>): Any? {
        if (headers.containsKey("transfer-encoding")) throw ApiException(400, "bad_request", "Chunked bodies are not supported")
        val rawLength = headers["content-length"] ?: throw ApiException(411, "bad_request", "Provide Content-Length")
        if (!rawLength.matches(Regex("[0-9]+"))) throw ApiException(400, "bad_request", "Invalid Content-Length")
        val length = rawLength.toLongOrNull() ?: throw ApiException(413, "bad_request", "Body exceeds 16 KiB")
        if (length > 16 * 1024) throw ApiException(413, "bad_request", "Body exceeds 16 KiB")
        if (headers["content-type"]?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT) != "application/json") {
            throw ApiException(400, "bad_request", "Use application/json")
        }
        val bytes = ByteArray(length.toInt())
        var offset = 0
        while (offset < bytes.size) {
            val read = input.read(bytes, offset, bytes.size - offset)
            if (read < 0) throw ApiException(400, "bad_request", "Incomplete request body")
            offset += read
        }
        return try {
            val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
            Json.parse(text)
        } catch (_: java.nio.charset.CharacterCodingException) {
            throw ApiException(400, "bad_request", "Invalid UTF-8 body")
        } catch (_: Json.ParseException) {
            throw ApiException(400, "bad_request", "Invalid JSON body")
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

    private fun Map<*, *>.optionalString(key: String): String? {
        if (!containsKey(key)) return null
        return this[key] as? String ?: throw ApiException(400, "bad_request", "$key must be a string")
    }

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
            409 -> "Conflict"
            411 -> "Length Required"
            413 -> "Payload Too Large"
            416 -> "Range Not Satisfiable"
            429 -> "Too Many Requests"
            431 -> "Request Header Fields Too Large"
            else -> "Internal Server Error"
        }
        val text = buildString {
            append("HTTP/1.1 $status $reason\r\n")
            headers.forEach { (name, value) -> append("$name: $value\r\n") }
            append("Connection: close\r\n\r\n")
        }
        if (output is ResponseOutput) output.status = status
        output.write(text.toByteArray(Charsets.US_ASCII))
        if (output is ResponseOutput) output.headerBytes = output.count
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

    private class ResponseOutput(private val delegate: OutputStream) : OutputStream() {
        var status = 400
        var count = 0L
        var headerBytes = 0L
        val bodyBytes: Long get() = (count - headerBytes).coerceAtLeast(0)
        override fun write(value: Int) { delegate.write(value); count++ }
        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            delegate.write(bytes, offset, length)
            count += length
        }
        override fun flush() = delegate.flush()
    }

    private companion object {
        val REQUEST_UPDATE = Regex("/v1/requests/([A-Za-z0-9_-]{1,64})")
        val REQUEST_LOG = Regex("/v1/requests/([A-Za-z0-9_-]{1,64})/log")
        val NOTIFY_ITEM = Regex("/v1/notify/([A-Za-z0-9_-]{1,64})")
        val TOKEN = Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]+")
    }
}
