package dev.tentacle.core

import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.file.Files
import java.util.ArrayDeque
import java.util.Locale
import java.util.PriorityQueue

class FileApi(
    roots: List<StorageRoot>,
    deniedPrefixes: List<String> = listOf("Android/data", "Android/obb"),
) {
    private val declaredPaths = roots.map { it.path.absoluteFile.toPath().normalize().toFile() }
    val roots: List<StorageRoot> = roots.map { it.copy(path = it.path.canonicalFile) }
    private val primary = this.roots.firstOrNull { it.id == "primary" } ?: this.roots.firstOrNull()
        ?: throw IllegalArgumentException("At least one storage root is required")
    private val denied = deniedPrefixes.map {
        require(it.isNotBlank() && !File(it).isAbsolute && it.split('/').none { part -> part == ".." })
        it.trimEnd('/')
    }

    fun resolve(raw: String): File = fileOperation {
        if ('\u0000' in raw) throw ApiException(400, "bad_request", "Invalid path")
        val requested = when (raw) {
            "", "/", "." -> primary.path
            else -> File(raw).let { if (it.isAbsolute) it else File(primary.path, raw) }
        }
        // Check the spelling as well as the target, so an alias cannot expose a denied prefix.
        checkDenied(requested.toPath().normalize().toFile().absoluteFile, declaredPaths + roots.map { it.path })
        requested.canonicalFile.also(::checkAllowed)
    }

    private fun checkAllowed(file: File) {
        val path = file.toPath()
        if (roots.none { path.startsWith(it.path.toPath()) }) throw ApiException(403, "forbidden")
        checkDenied(file, roots.map { it.path })
    }

    private fun checkDenied(file: File, rootPaths: List<File>) {
        if (rootPaths.any { root -> denied.any { file.toPath().startsWith(File(root, it).toPath()) } }) {
            throw ApiException(403, "forbidden")
        }
    }

    fun ls(raw: String = "", hidden: Boolean = false): Listing = fileOperation {
        val directory = existing(raw)
        if (!directory.isDirectory) throw ApiException(400, "bad_request", "Path is not a directory")
        val children = children(directory)
        val entries = children.asSequence().filter { hidden || !it.name.startsWith('.') }
            .mapNotNull(::visibleEntry)
            .sortedWith(compareBy<Entry> { it.type != "dir" }.thenBy { it.name }.thenBy { it.path })
            .toList()
        Listing(directory.path, entries)
    }

    fun stat(raw: String): Entry = fileOperation { entry(existing(raw)) }

    /** Root is depth zero and is not itself returned. Time bounds are inclusive. */
    fun find(query: FindQuery = FindQuery()): FindResult = fileOperation {
        if (query.maxDepth < 0 || query.limit !in 1..5000 || query.type !in setOf("file", "dir", "any") ||
            query.sort !in setOf("mtime_desc", "mtime_asc", "name") ||
            query.ext.any { it.isBlank() || it.any { char -> char in ".,\\/" } } ||
            (query.sinceMs != null && query.untilMs != null && query.sinceMs > query.untilMs)
        ) throw ApiException(400, "bad_request", "Invalid find filters")
        val root = existing(query.root)
        if (!root.isDirectory) throw ApiException(400, "bad_request", "Root is not a directory")
        val initialChildren = children(root) // Explicitly requested unreadable roots are errors.
        val limit = query.limit
        val extensions = query.ext.map { it.lowercase(Locale.ROOT) }.toSet()
        val name = query.name?.lowercase(Locale.ROOT)
        val order = when (query.sort) {
            "mtime_asc" -> compareBy<Entry> { it.mtimeMs }.thenBy { it.name }.thenBy { it.path }
            "name" -> compareBy<Entry> { it.name }.thenBy { it.path }
            else -> compareByDescending<Entry> { it.mtimeMs }.thenBy { it.name }.thenBy { it.path }
        }
        // Keep only the best limit matches, even when a later subtree has newer files.
        val best = PriorityQueue(limit, order.reversed())
        var truncated = false
        data class Directory(val files: Array<File>, val depth: Int)
        val stack = ArrayDeque<Directory>()
        if (query.maxDepth > 0) stack.addLast(Directory(initialChildren, 1))
        while (stack.isNotEmpty()) {
            val (files, depth) = stack.removeLast()
            for (file in files) {
                if (!query.hidden && file.name.startsWith('.')) continue
                val item = walkEntry(file) ?: continue
                val matches = (query.type == "any" || item.type == query.type) &&
                    (extensions.isEmpty() || (item.type == "file" &&
                        item.name.substringAfterLast('.', "").lowercase(Locale.ROOT) in extensions)) &&
                    (name == null || name in item.name.lowercase(Locale.ROOT)) &&
                    (query.sinceMs == null || item.mtimeMs >= query.sinceMs) &&
                    (query.untilMs == null || item.mtimeMs <= query.untilMs)
                if (matches) {
                    if (best.size < limit) best.add(item)
                    else {
                        truncated = true
                        if (order.compare(item, best.peek()) < 0) {
                            best.poll()
                            best.add(item)
                        }
                    }
                }
                if (item.type == "dir" && depth < query.maxDepth && !Files.isSymbolicLink(file.toPath())) {
                    try {
                        stack.addLast(Directory(children(file), depth + 1))
                    } catch (e: ApiException) {
                        if (e.status != 404) throw e
                    }
                }
            }
        }
        FindResult(root.path, best.sortedWith(order), truncated)
    }

    /**
     * Entries reached by find are children of a root that was canonicalised once
     * in [existing]. Descending only through non-symlink directories preserves
     * that invariant, so metadata reads do not need a canonical-path syscall for
     * every file in a large directory.
     */
    private fun walkEntry(file: File): Entry? {
        return try {
            // Preserve canonical metadata for allowed links without making
            // every ordinary entry pay for canonical resolution.
            if (Files.isSymbolicLink(file.toPath())) {
                visibleEntry(file)
            } else {
                val absolute = file.absoluteFile.toPath().normalize().toFile()
                checkAllowed(absolute)
                if (!absolute.exists()) throw ApiException(404, "not_found")
                entry(absolute)
            }
        } catch (e: ApiException) {
            if (e.status == 403 || e.status == 404) null else throw e
        }
    }

    fun openFile(raw: String, range: String? = null): FileContent = fileOperation {
        val file = existing(raw)
        if (!file.isFile) throw ApiException(400, "bad_request", "Path is not a regular file")
        val input = FileInputStream(file)
        try {
            val size = input.channel.size()
            val (offset, length) = parseRange(range, size)
            input.channel.position(offset)
            FileContent(input, size, offset, length, file.lastModified(), range != null, contentType(file))
        } catch (e: Exception) {
            input.close()
            throw e
        }
    }

    private fun parseRange(range: String?, size: Long): Pair<Long, Long> {
        if (range == null) return 0L to size
        fun unsatisfiable(): Nothing = throw ApiException(
            416, "range_not_satisfiable", "Range is not satisfiable", mapOf("Content-Range" to "bytes */$size"),
        )
        val match = Regex("bytes=([0-9]*)-([0-9]*)").matchEntire(range.trim()) ?: unsatisfiable()
        val (startText, endText) = match.destructured
        if (size == 0L || (startText.isEmpty() && endText.isEmpty())) unsatisfiable()
        if (startText.isEmpty()) {
            val suffix = endText.toLongOrNull() ?: unsatisfiable()
            if (suffix == 0L) unsatisfiable()
            val length = suffix.coerceAtMost(size)
            return size - length to length
        }
        val start = startText.toLongOrNull() ?: unsatisfiable()
        val end = if (endText.isEmpty()) size - 1 else (endText.toLongOrNull() ?: unsatisfiable()).coerceAtMost(size - 1)
        if (start >= size || start > end) unsatisfiable()
        return start to (end - start + 1)
    }

    private fun existing(raw: String): File {
        val file = resolve(raw)
        if (!file.exists()) throw ApiException(404, "not_found")
        if (!file.canRead()) throw ApiException(403, "forbidden")
        return file
    }

    private fun children(directory: File): Array<File> = fileOperation {
        if (!directory.canRead() || !directory.canExecute()) throw ApiException(403, "forbidden")
        directory.listFiles() ?: throw ApiException(403, "forbidden", "Directory cannot be read")
    }

    private fun visibleEntry(file: File): Entry? = try {
        // Metadata does not require reading the child. A walk reports unreadable
        // directories when opening them; denied targets are skipped here.
        val resolved = resolve(file.path)
        if (!resolved.exists()) throw ApiException(404, "not_found")
        entry(resolved)
    } catch (e: ApiException) {
        if (e.status == 403 || e.status == 404) null else throw e
    }

    private fun entry(file: File): Entry = Entry(
        file.name, file.path,
        when { file.isDirectory -> "dir"; file.isFile -> "file"; else -> "other" },
        if (file.isDirectory) 0 else file.length(), file.lastModified(),
    )

    private fun contentType(file: File): String = when (file.extension.lowercase(Locale.ROOT)) {
        "aac" -> "audio/aac"
        "m4a", "mp4" -> if (file.extension.equals("m4a", true)) "audio/mp4" else "video/mp4"
        "mp3" -> "audio/mpeg"
        "wav" -> "audio/wav"
        "ogg", "opus" -> "audio/ogg"
        "flac" -> "audio/flac"
        "txt", "log" -> "text/plain; charset=utf-8"
        "json" -> "application/json; charset=utf-8"
        "html", "htm" -> "text/html; charset=utf-8"
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "pdf" -> "application/pdf"
        else -> "application/octet-stream"
    }

    private inline fun <T> fileOperation(operation: () -> T): T = try {
        operation()
    } catch (e: SecurityException) {
        throw ApiException(403, "forbidden")
    } catch (e: IOException) {
        throw ApiException(403, "forbidden", "Path cannot be read")
    }
}

/** An opened, bounded view of a file; close it even for HEAD responses. */
class FileContent internal constructor(
    private val input: FileInputStream,
    val size: Long,
    val offset: Long,
    val length: Long,
    val mtimeMs: Long,
    val partial: Boolean,
    val contentType: String,
) : Closeable {
    fun writeTo(output: OutputStream) {
        val buffer = ByteArray(32 * 1024)
        var remaining = length
        while (remaining > 0) {
            val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (read < 0) throw IOException("File changed while being read")
            output.write(buffer, 0, read)
            remaining -= read
        }
    }

    override fun close() = input.close()
}
