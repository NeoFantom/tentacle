package dev.tentacle.core

import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** App-private persistent phone-notification feed. */
class PhoneNotificationStore(
    private val directory: File,
    initialEnabled: Boolean = false,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val lock = ReentrantLock()
    private val changed = lock.newCondition()
    private val stateFile = File(directory, STATE_FILE)
    private var entries = mutableListOf<PhoneNotificationEntry>()
    private var apps = linkedMapOf<String, String>()
    private var lastUpdatedMs = 0L

    @Volatile
    var isEnabled: Boolean = initialEnabled
        private set

    init {
        directory.mkdirs()
        require(directory.isDirectory) { "Phone notification storage is not a directory" }
        if (tempFile.exists() && !tempFile.delete()) {
            throw IllegalStateException("Could not erase incomplete phone notification state")
        }
        load()
        if (!initialEnabled) setEnabled(false)
    }

    fun setEnabled(enabled: Boolean) {
        lock.withLock {
            if (isEnabled == enabled && (enabled || (entries.isEmpty() && apps.isEmpty()))) return
            if (enabled) {
                isEnabled = true
            } else {
                isEnabled = false
                entries.clear()
                apps.clear()
                changed.signalAll()
                persistEmptyFailClosed(emptyMap())
            }
            if (enabled) changed.signalAll()
        }
    }

    fun capture(
        input: PhoneNotificationInput,
        includeOngoing: Boolean = false,
        excludedPackages: Set<String> = emptySet(),
        ownPackage: String = PhoneNotifications.OWN_PACKAGE,
    ): PhoneNotificationEntry? = lock.withLock {
        if (!isEnabled) return@withLock null
        val nextApps = LinkedHashMap(apps)
        if (input.packageName.isNotBlank() && input.packageName != ownPackage) {
            nextApps[input.packageName.trim().take(255)] =
                input.appLabel.trim().ifEmpty { input.packageName.trim() }.take(200)
            trimApps(nextApps)
        }
        if (!PhoneNotifications.shouldCapture(input, includeOngoing, excludedPackages, ownPackage)) {
            if (nextApps != apps) {
                persist(entries, nextApps, lastUpdatedMs)
                apps = nextApps
            }
            return@withLock null
        }

        if (input.postTimeMs <= clock() - RETENTION_MS) {
            if (nextApps != apps) {
                persist(entries, nextApps, lastUpdatedMs)
                apps = nextApps
            }
            return@withLock null
        }
        val updated = nextCursor()
        val entry = PhoneNotifications.normalizedEntry(input, updated)
        val cutoff = clock() - RETENTION_MS
        val nextEntries = entries.filter { it.key != entry.key && it.postTimeMs > cutoff }.toMutableList()
            .apply { add(entry) }
            .sortedByDescending { it.updatedMs }
            .take(MAX_ENTRIES)
            .toMutableList()
        persist(nextEntries, nextApps, updated)
        entries = nextEntries
        apps = nextApps
        lastUpdatedMs = updated
        changed.signalAll()
        entry
    }

    fun markRemoved(key: String): PhoneNotificationEntry? = lock.withLock {
        if (!isEnabled) return@withLock null
        purgeExpiredLocked()
        val index = entries.indexOfFirst { it.key == key }
        if (index < 0) return@withLock null
        val removed = clock().coerceAtLeast(0)
        val updated = nextCursor(removed)
        val entry = entries[index].copy(removedMs = removed, updatedMs = updated)
        val nextEntries = entries.toMutableList().apply { this[index] = entry }
            .sortedByDescending { it.updatedMs }.toMutableList()
        persist(nextEntries, apps, updated)
        entries = nextEntries
        lastUpdatedMs = updated
        changed.signalAll()
        entry
    }

    fun query(sinceMs: Long = 0, app: String? = null, limit: Int = MAX_ENTRIES): List<PhoneNotificationEntry> {
        require(limit in 1..MAX_ENTRIES) { "limit must be between 1 and $MAX_ENTRIES" }
        return lock.withLock {
            if (!isEnabled) return@withLock emptyList()
            purgeExpiredLocked()
            matching(sinceMs, app, limit)
        }
    }

    fun awaitNewerThan(
        sinceMs: Long,
        timeoutMs: Long,
        app: String? = null,
        limit: Int = MAX_ENTRIES,
    ): List<PhoneNotificationEntry> {
        require(timeoutMs >= 0) { "timeoutMs must not be negative" }
        require(limit in 1..MAX_ENTRIES) { "limit must be between 1 and $MAX_ENTRIES" }
        return lock.withLock {
            var remaining = TimeUnit.MILLISECONDS.toNanos(timeoutMs)
            while (isEnabled) {
                purgeExpiredLocked()
                val result = matching(sinceMs, app, limit)
                if (result.isNotEmpty() || remaining <= 0) return@withLock result
                remaining = changed.awaitNanos(remaining)
            }
            emptyList()
        }
    }

    fun clear() {
        lock.withLock {
            entries.clear()
            changed.signalAll()
            persistEmptyFailClosed(apps)
        }
    }

    fun count(): Int = lock.withLock {
        purgeExpiredLocked()
        entries.size
    }

    fun seenApps(): Map<String, String> = lock.withLock { LinkedHashMap(apps) }

    private fun matching(sinceMs: Long, app: String?, limit: Int): List<PhoneNotificationEntry> =
        entries.asSequence()
            .filter { it.updatedMs > sinceMs && (app == null || it.packageName == app) }
            .take(limit)
            .toList()

    private fun load() {
        if (!stateFile.exists()) return
        try {
            val root = Json.parse(stateFile.readText(Charsets.UTF_8)) as? Map<*, *>
                ?: throw Json.ParseException("Root must be an object")
            val loadedEntries = (root["notifications"] as? List<*>)
                ?.map(::parseEntry) ?: throw Json.ParseException("notifications must be an array")
            if (loadedEntries.map { it.key }.toSet().size != loadedEntries.size) {
                throw Json.ParseException("Duplicate notification key")
            }
            val cutoff = clock() - RETENTION_MS
            entries = loadedEntries.filter { it.postTimeMs > cutoff }
                .sortedByDescending { it.updatedMs }.take(MAX_ENTRIES).toMutableList()
            apps = linkedMapOf()
            val loadedApps = root["seen_apps"] as? List<*> ?: emptyList<Any?>()
            loadedApps.forEach { value ->
                val item = value as? Map<*, *> ?: throw Json.ParseException("seen app must be an object")
                apps[item.string("package")] = item.string("label")
            }
            trimApps(apps)
            lastUpdatedMs = maxOf(
                root.optionalLong("high_water_ms") ?: 0,
                entries.maxOfOrNull { it.updatedMs } ?: 0,
            )
            if (loadedEntries != entries || loadedApps.size != apps.size) {
                persist(entries, apps, lastUpdatedMs)
            }
        } catch (_: Exception) {
            entries.clear()
            apps.clear()
            lastUpdatedMs = 0
            persistEmptyFailClosed(emptyMap())
        }
    }

    private fun purgeExpiredLocked() {
        val cutoff = clock() - RETENTION_MS
        val retained = entries.filter { it.postTimeMs > cutoff }
        if (retained.size != entries.size) {
            persist(retained, apps, lastUpdatedMs)
            entries = retained.toMutableList()
        }
    }

    private fun parseEntry(value: Any?): PhoneNotificationEntry {
        val item = value as? Map<*, *> ?: throw Json.ParseException("notification must be an object")
        val entry = PhoneNotificationEntry(
            key = item.string("key"), packageName = item.string("package"),
            appLabel = item.string("app"), title = item.string("title"),
            text = item.string("text"), subText = item.string("sub_text"),
            category = item.string("category").takeIf(String::isNotEmpty), postTimeMs = item.long("posted_ms"),
            ongoing = item.boolean("ongoing"), removedMs = item.nullableLong("removed_ms"),
            updatedMs = item.long("updated_ms"),
        )
        if (entry.key.isBlank() || entry.packageName.isBlank() || entry.postTimeMs < 0 || entry.updatedMs < 0) {
            throw Json.ParseException("Invalid persisted notification")
        }
        return entry
    }

    private fun persist(
        nextEntries: List<PhoneNotificationEntry>,
        nextApps: Map<String, String>,
        nextHighWaterMs: Long,
    ) {
        val root = linkedMapOf<String, Any>(
            "high_water_ms" to nextHighWaterMs,
            "notifications" to nextEntries.map(PhoneNotificationEntry::toJson),
            "seen_apps" to nextApps.map { (packageName, label) ->
                linkedMapOf("package" to packageName, "label" to label)
            },
        )
        try {
            tempFile.writeText(Json.stringify(root), Charsets.UTF_8)
            Files.move(tempFile.toPath(), stateFile.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(tempFile.toPath(), stateFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } finally {
            tempFile.delete()
        }
    }

    private fun persistEmptyFailClosed(nextApps: Map<String, String>) {
        runCatching { persist(emptyList(), nextApps, lastUpdatedMs) }.onFailure { cause ->
            deleteOrThrow(stateFile, cause)
            deleteOrThrow(tempFile, cause)
        }
    }

    private fun deleteOrThrow(file: File, cause: Throwable) {
        if (file.exists() && !file.delete()) {
            throw IllegalStateException("Could not erase phone notification state", cause)
        }
    }

    private val tempFile: File get() = File(directory, ".$STATE_FILE.tmp")

    private fun nextCursor(now: Long = clock().coerceAtLeast(0)): Long = maxOf(now, lastUpdatedMs + 1)

    private fun trimApps(values: LinkedHashMap<String, String>) {
        while (values.size > MAX_SEEN_APPS) values.remove(values.keys.first())
    }

    private fun Map<*, *>.string(key: String): String = this[key] as? String
        ?: throw Json.ParseException("$key must be a string")

    private fun Map<*, *>.long(key: String): Long = (this[key] as? Number)?.let {
        val long = it.toLong()
        if (it.toDouble() == long.toDouble()) long else null
    } ?: throw Json.ParseException("$key must be an integer")

    private fun Map<*, *>.nullableLong(key: String): Long? = this[key]?.let {
        val number = it as? Number ?: throw Json.ParseException("$key must be an integer or null")
        val long = number.toLong()
        if (number.toDouble() != long.toDouble()) throw Json.ParseException("$key must be an integer or null")
        long
    }

    private fun Map<*, *>.optionalLong(key: String): Long? = if (containsKey(key)) nullableLong(key) else null

    private fun Map<*, *>.boolean(key: String): Boolean = this[key] as? Boolean
        ?: throw Json.ParseException("$key must be a boolean")

    companion object {
        const val MAX_ENTRIES = 500
        const val RETENTION_MS = 3 * 24 * 60 * 60 * 1000L
        private const val MAX_SEEN_APPS = 500
        private const val STATE_FILE = "phone-notifications.json"
    }
}
