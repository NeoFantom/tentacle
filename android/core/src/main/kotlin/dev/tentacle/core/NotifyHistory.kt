package dev.tentacle.core

import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

data class NotifyHistoryEntry(
    val id: String,
    val title: String,
    val text: String,
    val level: String,
    val receivedMs: Long,
    val viewed: Boolean = false,
)

data class NotifyHistoryView(
    val current: List<NotifyHistoryEntry>,
    val history: List<NotifyHistoryEntry>,
)

/** Local reading history, deliberately independent of API acknowledgement and alarm state. */
class NotifyHistory(private val file: File, private val clock: () -> Long = System::currentTimeMillis) {
    private var entries: List<NotifyHistoryEntry> = load()

    @Synchronized fun record(message: NotifyMessage, delivery: NotifyDelivery) {
        if (!delivery.shown) return
        val old = entries.firstOrNull { it.id == message.id }
        if (old != null && old.title == message.title && old.text == message.text && old.level == delivery.level) return
        entries = (listOf(NotifyHistoryEntry(message.id, message.title, message.text, delivery.level, clock())) +
            entries.filterNot { it.id == message.id }).take(MAX_ENTRIES)
        persist()
    }

    /** Keep this visit's batch expanded, including new arrivals and an explicitly tapped old alert. */
    @Synchronized fun open(currentIds: Collection<String> = emptyList()): NotifyHistoryView {
        val view = snapshot(currentIds)
        if (view.current.any { !it.viewed }) {
            val next = entries.map { if (!it.viewed) it.copy(viewed = true) else it }
            persist(next)
            entries = next
        }
        return view.copy(current = view.current.map { it.copy(viewed = true) })
    }

    @Synchronized fun snapshot(currentIds: Collection<String> = emptyList()): NotifyHistoryView {
        val selected = currentIds.toSet()
        val current = entries.filter { !it.viewed || it.id in selected }
        val history = entries.filter { it.viewed && it.id !in selected }
        return NotifyHistoryView(current, history)
    }

    private fun load(): List<NotifyHistoryEntry> {
        if (!file.isFile) return emptyList()
        return try {
            val root = Json.parse(file.readText(Charsets.UTF_8)) as Map<*, *>
            (root["entries"] as List<*>).map { value ->
                val item = value as Map<*, *>
                NotifyHistoryEntry(item["id"] as String, item["title"] as String,
                    item["text"] as String, item["level"] as String,
                    (item["received_ms"] as Number).toLong(), item["viewed"] as Boolean)
            }.distinctBy { it.id }.take(MAX_ENTRIES)
        } catch (_: Exception) {
            // A corrupt private history must not prevent notification delivery.
            emptyList()
        }
    }

    private fun persist(values: List<NotifyHistoryEntry> = entries) {
        file.absoluteFile.parentFile?.mkdirs()
        val temporary = File(file.absoluteFile.parentFile, ".${file.name}.tmp")
        try {
            temporary.writeText(Json.stringify(mapOf("entries" to values.map {
                mapOf("id" to it.id, "title" to it.title, "text" to it.text,
                    "level" to it.level, "received_ms" to it.receivedMs, "viewed" to it.viewed)
            })), Charsets.UTF_8)
            try {
                Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            temporary.delete()
        }
    }

    companion object {
        const val MAX_ENTRIES = 200
    }
}
