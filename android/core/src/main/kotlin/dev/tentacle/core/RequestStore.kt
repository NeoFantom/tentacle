package dev.tentacle.core

import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.SecureRandom
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

data class AppRequest(
    val id: String,
    val kind: String,
    val scope: String,
    val text: String,
    val createdMs: Long,
    val updatedMs: Long,
    val status: String,
    val message: String,
    val hasLog: Boolean,
) {
    fun toJson(): Map<String, Any> = linkedMapOf(
        "id" to id,
        "kind" to kind,
        "scope" to scope,
        "text" to text,
        "created_ms" to createdMs,
        "updated_ms" to updatedMs,
        "status" to status,
        "message" to message,
        "has_log" to hasLog,
    )
}

class RequestStore(
    private val directory: File,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val lock = ReentrantLock()
    private val changed = lock.newCondition()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val random = SecureRandom()
    private val stateFile = File(directory, "requests.json")
    private var requests = mutableListOf<AppRequest>()

    @Volatile
    var lastPolledMs: Long = 0
        private set

    init {
        directory.mkdirs()
        require(directory.isDirectory) { "Request storage is not a directory" }
        load()
    }

    fun create(kind: String, text: String, logSnapshot: String? = null): AppRequest {
        if (kind !in KINDS) throw ApiException(400, "bad_request", "Invalid request kind")
        RequestText.validate(text, allowEmpty = kind == "bug")
        val request = lock.withLock {
            val now = clock()
            val created = AppRequest(
                id = newId(now),
                kind = kind,
                scope = RequestText.SCOPE,
                text = text,
                createdMs = now,
                updatedMs = now,
                status = "new",
                message = "",
                hasLog = logSnapshot != null,
            )
            val next = (listOf(created) + requests).sortedByDescending { it.createdMs }.take(MAX_REQUESTS)
            val removed = requests.filter { old -> next.none { it.id == old.id } }
            try {
                logSnapshot?.let { writeAtomic(logFile(created.id), it) }
                persist(next, lastPolledMs)
            } catch (e: Exception) {
                if (logSnapshot != null) logFile(created.id).delete()
                throw e
            }
            requests = next.toMutableList()
            removed.forEach { logFile(it.id).delete() }
            if (next.none { it.id == created.id }) logFile(created.id).delete()
            changed.signalAll()
            created
        }
        notifyListeners()
        return request
    }

    fun list(): List<AppRequest> = lock.withLock { requests.toList() }

    fun update(id: String, status: String, message: String): AppRequest {
        if (status !in STATUSES) throw ApiException(400, "bad_request", "Invalid request status")
        if (message.length > MAX_MESSAGE_CHARS) {
            throw ApiException(400, "bad_request", "Request message is too long")
        }
        val updated = lock.withLock {
            val index = requests.indexOfFirst { it.id == id }
            if (index < 0) throw ApiException(404, "not_found", "Request was not found")
            val old = requests[index]
            if (status !in TRANSITIONS.getValue(old.status)) {
                throw ApiException(409, "conflict", "Request status transition is not allowed")
            }
            val result = old.copy(
                status = status,
                message = if (old.status == "failed" && status == "new") "" else message,
                updatedMs = clock(),
            )
            val next = requests.toMutableList().apply { this[index] = result }
            persist(next, lastPolledMs)
            requests = next
            changed.signalAll()
            result
        }
        notifyListeners()
        return updated
    }

    fun log(id: String): String = lock.withLock {
        val request = requests.firstOrNull { it.id == id }
            ?: throw ApiException(404, "not_found", "Request was not found")
        if (!request.hasLog) throw ApiException(404, "not_found", "Request has no log")
        try {
            logFile(id).readText(Charsets.UTF_8)
        } catch (_: IOException) {
            throw ApiException(404, "not_found", "Request log was not found")
        }
    }

    fun awaitNew(timeoutMs: Long): List<AppRequest> {
        require(timeoutMs >= 0) { "timeoutMs must not be negative" }
        return lock.withLock {
            var remaining = TimeUnit.MILLISECONDS.toNanos(timeoutMs)
            while (requests.none { it.status == "new" } && remaining > 0) {
                remaining = changed.awaitNanos(remaining)
            }
            requests.toList()
        }
    }

    fun recordPoll() {
        lock.withLock {
            val polled = clock()
            persist(requests, polled)
            lastPolledMs = polled
        }
        notifyListeners()
    }

    fun observe(listener: () -> Unit): AutoCloseable {
        listeners += listener
        return AutoCloseable { listeners -= listener }
    }

    private fun load() {
        if (!stateFile.exists()) return
        try {
            val root = Json.parse(readStateBounded()) as? Map<*, *>
                ?: throw Json.ParseException("Root must be an object")
            lastPolledMs = root.long("last_polled_ms")
            val loaded = root["requests"] as? List<*> ?: throw Json.ParseException("requests must be an array")
            val parsed = loaded.map { parseRequest(it) }.sortedByDescending { it.createdMs }
            if (parsed.map { it.id }.toSet().size != parsed.size) throw Json.ParseException("Duplicate request id")
            requests = parsed.take(MAX_REQUESTS).toMutableList()
            parsed.drop(MAX_REQUESTS).forEach { logFile(it.id).delete() }
        } catch (_: Exception) {
            val bad = File(directory, "requests.json.bad")
            runCatching { Files.copy(stateFile.toPath(), bad.toPath(), StandardCopyOption.REPLACE_EXISTING) }
            requests.clear()
            lastPolledMs = 0
        }
    }

    private fun parseRequest(value: Any?): AppRequest {
        val item = value as? Map<*, *> ?: throw Json.ParseException("request must be an object")
        val request = AppRequest(
            id = item.string("id"),
            kind = item.string("kind"),
            scope = item.string("scope"),
            text = item.string("text"),
            createdMs = item.long("created_ms"),
            updatedMs = item.long("updated_ms"),
            status = item.string("status"),
            message = item.string("message"),
            hasLog = item.boolean("has_log"),
        )
        if (!ID.matches(request.id) || request.kind !in KINDS || request.scope != RequestText.SCOPE ||
            request.status !in STATUSES || request.message.length > MAX_MESSAGE_CHARS
        ) throw Json.ParseException("Invalid persisted request")
        RequestText.validate(request.text, allowEmpty = request.kind == "bug")
        return request
    }

    private fun readStateBounded(): String = stateFile.bufferedReader(Charsets.UTF_8).use { reader ->
        val content = StringBuilder()
        val buffer = CharArray(8192)
        while (true) {
            val count = reader.read(buffer)
            if (count < 0) break
            if (content.length + count > Json.MAX_INPUT_CHARS) {
                throw Json.ParseException("JSON input is too large")
            }
            content.append(buffer, 0, count)
        }
        content.toString()
    }

    private fun persist(nextRequests: List<AppRequest>, nextLastPolledMs: Long) {
        val root = linkedMapOf<String, Any>(
            "last_polled_ms" to nextLastPolledMs,
            "requests" to nextRequests.map(AppRequest::toJson),
        )
        writeAtomic(stateFile, Json.stringify(root))
    }

    private fun newId(now: Long): String {
        repeat(256) {
            val suffix = random.nextInt(0x10000).toString(16).padStart(4, '0')
            val candidate = "r-$now-$suffix"
            if (requests.none { it.id == candidate }) return candidate
        }
        throw IllegalStateException("Could not allocate a request id")
    }

    private fun logFile(id: String): File = File(directory, "$id.log")

    private fun writeAtomic(target: File, content: String) {
        val temp = File(directory, ".${target.name}.tmp")
        temp.writeText(content, Charsets.UTF_8)
        try {
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun notifyListeners() {
        listeners.forEach { listener -> runCatching(listener) }
    }

    private fun Map<*, *>.string(key: String): String = this[key] as? String
        ?: throw Json.ParseException("$key must be a string")

    private fun Map<*, *>.long(key: String): Long = (this[key] as? Number)?.let {
        val long = it.toLong()
        if (it.toDouble() == long.toDouble()) long else null
    } ?: throw Json.ParseException("$key must be an integer")

    private fun Map<*, *>.boolean(key: String): Boolean = this[key] as? Boolean
        ?: throw Json.ParseException("$key must be a boolean")

    companion object {
        const val MAX_MESSAGE_CHARS = 1000
        private const val MAX_REQUESTS = 20
        private val ID = Regex("[A-Za-z0-9_-]{1,64}")
        private val KINDS = setOf("feature", "bug")
        private val STATUSES = setOf("new", "working", "done", "failed")
        private val TRANSITIONS = mapOf(
            "new" to setOf("working", "failed"),
            "working" to setOf("done", "failed", "new"),
            "done" to setOf("done", "failed"),
            "failed" to setOf("done", "failed", "new"),
        )
    }
}
