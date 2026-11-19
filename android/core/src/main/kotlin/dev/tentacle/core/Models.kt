package dev.tentacle.core

import java.io.File

data class StorageRoot(val id: String, val path: File, val label: String)

class ApiException(
    val status: Int,
    val code: String,
    override val message: String = code,
    val headers: Map<String, String> = emptyMap(),
) : RuntimeException(message)

data class Entry(val name: String, val path: String, val type: String, val size: Long, val mtimeMs: Long) {
    fun toJson(): Map<String, Any> = linkedMapOf(
        "name" to name, "path" to path, "type" to type, "size" to size, "mtime_ms" to mtimeMs,
    )
}

data class Listing(val path: String, val entries: List<Entry>) {
    fun toJson(): Map<String, Any> = linkedMapOf("path" to path, "entries" to entries.map(Entry::toJson))
}

data class FindQuery(
    val root: String = "",
    val ext: List<String> = emptyList(),
    val name: String? = null,
    val sinceMs: Long? = null,
    val untilMs: Long? = null,
    val type: String = "file",
    val maxDepth: Int = 12,
    val limit: Int = 500,
    val sort: String = "mtime_desc",
    val hidden: Boolean = false,
)

data class FindResult(val root: String, val entries: List<Entry>, val truncated: Boolean) {
    fun toJson(): Map<String, Any> = linkedMapOf(
        "root" to root, "entries" to entries.map(Entry::toJson), "truncated" to truncated,
    )
}

data class PingInfo(
    val version: String,
    val device: String,
    val androidSdk: Int,
    val allFilesAccess: Boolean,
    val roots: List<StorageRoot>,
    val timezone: String,
) {
    fun toJson(timeMs: Long = System.currentTimeMillis()): Map<String, Any> = linkedMapOf(
        "app" to "tentacle", "api" to 1, "version" to version, "device" to device,
        "android_sdk" to androidSdk,
        "roots" to roots.map {
            linkedMapOf("id" to it.id, "path" to it.path.canonicalPath, "label" to it.label)
        },
        "all_files_access" to allFilesAccess, "time_ms" to timeMs, "timezone" to timezone,
    )
}
