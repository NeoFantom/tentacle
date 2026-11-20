package dev.tentacle.core

import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

data class NotifyMessage(
    val id: String,
    val title: String,
    val text: String,
    val requestedLevel: String,
)

data class NotifyDelivery(
    val level: String,
    val downgraded: Boolean = false,
    val reason: String? = null,
    val shown: Boolean = true,
)

data class NotifyEntry(
    val message: NotifyMessage,
    val delivery: NotifyDelivery,
    val state: String = "shown",
    val acknowledgedMs: Long? = null,
) {
    fun postJson(): Map<String, Any> = linkedMapOf<String, Any>(
        "id" to message.id,
        "level" to delivery.level,
        "downgraded" to delivery.downgraded,
    ).apply {
        delivery.reason?.let { put("reason", it) }
        put("shown", delivery.shown)
    }

    fun stateJson(): Map<String, Any> = linkedMapOf<String, Any>(
        "id" to message.id,
        "level" to delivery.level,
        "state" to state,
    ).apply { acknowledgedMs?.let { put("acknowledged_ms", it) } }
}

/** Process-memory-only notifications sent from the paired computer. */
class NotifyStore(
    private val prepare: (NotifyMessage) -> NotifyDelivery,
    private val deliver: (NotifyMessage, NotifyDelivery) -> NotifyDelivery,
    private val clock: () -> Long = System::currentTimeMillis,
    private val idGenerator: () -> String = { UUID.randomUUID().toString() },
) {
    private val lock = ReentrantLock()
    private val changed = lock.newCondition()
    private var entries = mutableListOf<NotifyEntry>()
    private val normalDeliveries = ArrayDeque<Long>()
    private val urgentDeliveries = ArrayDeque<Long>()

    fun post(title: String?, text: String, level: String?, id: String?): NotifyEntry {
        val normalizedTitle = title ?: DEFAULT_TITLE
        val normalizedLevel = level ?: NORMAL
        validate(normalizedTitle, text, normalizedLevel, id)
        return lock.withLock {
            val actualId = id ?: allocateId()
            val message = NotifyMessage(actualId, normalizedTitle, text, normalizedLevel)
            entries.firstOrNull { it.message.id == actualId }?.let { old ->
                if (old.message != message) {
                    throw ApiException(409, "conflict", "Notification id was already used with different content")
                }
                return@withLock old
            }

            val prepared = validateDelivery(message, prepare(message))
            checkRateLimit(prepared.level, clock())
            val delivered = validateDelivery(message, deliver(message, prepared))
            if (delivered.level != prepared.level || delivered.downgraded != prepared.downgraded) {
                throw IllegalStateException("Notification delivery changed its prepared level")
            }
            val entry = NotifyEntry(message, delivered, state = if (delivered.shown) "shown" else "expired")
            entries += entry
            if (entries.size > MAX_ENTRIES) entries = entries.takeLast(MAX_ENTRIES).toMutableList()
            changed.signalAll()
            entry
        }
    }

    fun get(id: String): NotifyEntry = lock.withLock { find(id) }

    fun transition(id: String, state: String): NotifyEntry {
        if (state !in TERMINAL_STATES) throw ApiException(400, "bad_request", "Invalid notification state")
        return lock.withLock {
            val index = entries.indexOfFirst { it.message.id == id }
            if (index < 0) throw ApiException(404, "not_found", "Notification was not found")
            val old = entries[index]
            if (old.state != "shown") return@withLock old
            val updated = old.copy(
                state = state,
                acknowledgedMs = if (state == "acknowledged") clock() else null,
            )
            entries[index] = updated
            changed.signalAll()
            updated
        }
    }

    fun awaitChange(id: String, timeoutMs: Long): NotifyEntry {
        require(timeoutMs >= 0) { "timeoutMs must not be negative" }
        return lock.withLock {
            var remaining = TimeUnit.MILLISECONDS.toNanos(timeoutMs)
            while (true) {
                val entry = find(id)
                if (entry.state != "shown" || remaining <= 0) return@withLock entry
                remaining = changed.awaitNanos(remaining)
            }
            error("unreachable")
        }
    }

    private fun find(id: String): NotifyEntry = entries.firstOrNull { it.message.id == id }
        ?: throw ApiException(404, "not_found", "Notification was not found")

    private fun allocateId(): String {
        repeat(256) {
            val candidate = idGenerator()
            if (ID.matches(candidate) && entries.none { it.message.id == candidate }) return candidate
        }
        throw IllegalStateException("Could not allocate a notification id")
    }

    private fun validate(title: String, text: String, level: String, id: String?) {
        if (title.codePointCount(0, title.length) > MAX_TITLE_CHARS) {
            throw ApiException(400, "bad_request", "Notification title is too long")
        }
        val textLength = text.codePointCount(0, text.length)
        if (textLength !in 1..MAX_TEXT_CHARS) {
            throw ApiException(400, "bad_request", "Notification text must be 1 to 1000 characters")
        }
        if (title.hasDisallowedControl() || text.hasDisallowedControl()) {
            throw ApiException(400, "bad_request", "Notification text contains a control character")
        }
        if (level !in LEVELS) throw ApiException(400, "bad_request", "Invalid notification level")
        if (id != null && !ID.matches(id)) throw ApiException(400, "bad_request", "Invalid notification id")
    }

    private fun validateDelivery(message: NotifyMessage, delivery: NotifyDelivery): NotifyDelivery {
        if (delivery.level !in LEVELS) throw IllegalStateException("Invalid delivered notification level")
        if (message.requestedLevel == NORMAL && delivery.level != NORMAL) {
            throw IllegalStateException("A normal notification cannot be upgraded")
        }
        if (delivery.downgraded != (message.requestedLevel == URGENT && delivery.level == NORMAL)) {
            throw IllegalStateException("Notification downgrade metadata does not match its level")
        }
        if (delivery.reason != null && (delivery.reason.isEmpty() || delivery.reason.hasDisallowedControl())) {
            throw IllegalStateException("Invalid notification delivery reason")
        }
        return delivery
    }

    private fun checkRateLimit(level: String, now: Long) {
        val (events, window, maximum) = if (level == URGENT) {
            Triple(urgentDeliveries, URGENT_WINDOW_MS, URGENT_LIMIT)
        } else {
            Triple(normalDeliveries, NORMAL_WINDOW_MS, NORMAL_LIMIT)
        }
        while (events.firstOrNull()?.let { now - it >= window } == true) events.removeFirst()
        if (events.size >= maximum) {
            throw ApiException(429, "rate_limited", "Notification rate limit was reached")
        }
        events.addLast(now)
    }

    private fun String.hasDisallowedControl(): Boolean = codePoints().anyMatch { point ->
        point != '\n'.code && Character.isISOControl(point)
    }

    companion object {
        const val NORMAL = "normal"
        const val URGENT = "urgent"
        const val MAX_TITLE_CHARS = 100
        const val MAX_TEXT_CHARS = 1000
        const val NORMAL_LIMIT = 10
        const val URGENT_LIMIT = 3
        const val NORMAL_WINDOW_MS = 60_000L
        const val URGENT_WINDOW_MS = 10 * 60_000L
        private const val DEFAULT_TITLE = "Tentacle"
        private const val MAX_ENTRIES = 50
        private val LEVELS = setOf(NORMAL, URGENT)
        private val TERMINAL_STATES = setOf("acknowledged", "dismissed", "expired")
        private val ID = Regex("[A-Za-z0-9_-]{1,64}")
    }
}
