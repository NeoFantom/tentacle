package dev.tentacle.core

import java.io.File
import java.io.ByteArrayOutputStream
import java.io.FileInputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.time.Instant

/** A small synchronous UTF-8 rotating log. File zero is current; higher numbers are older. */
class RotatingLog(
    private val directory: File,
    private val maxBytesPerFile: Long = 512L * 1024,
    private val files: Int = 2,
    private val redactor: Redactor = Redactor(),
    private val now: () -> Instant = Instant::now,
) {
    init {
        require(maxBytesPerFile in MIN_FILE_BYTES..Int.MAX_VALUE.toLong())
        require(files > 0)
    }

    @Synchronized
    fun append(message: String) {
        directory.mkdirs()
        val prefix = "${now()} "
        val suffix = "\n"
        val budget = (maxBytesPerFile - prefix.toByteArray().size - suffix.toByteArray().size).toInt()
        val body = truncateUtf8(redactor.redact(message).trimEnd(), budget)
        val line = prefix + body + suffix
        val bytes = line.toByteArray(Charsets.UTF_8)
        val current = file(0)
        if (current.isFile && current.length() > 0 && current.length() + bytes.size > maxBytesPerFile) rotate()
        Files.write(current.toPath(), bytes, StandardOpenOption.CREATE, StandardOpenOption.APPEND)
    }

    /** Returns retained files oldest first, capped to the newest one MiB. */
    @Synchronized
    fun snapshot(): String {
        val retained = (files - 1 downTo 0).map(::file).filter(File::isFile)
        var skip = (retained.sumOf(File::length) - MAX_SNAPSHOT_BYTES).coerceAtLeast(0)
        val output = ByteArrayOutputStream(MAX_SNAPSHOT_BYTES)
        retained.forEach { source ->
            FileInputStream(source).use { input ->
                val forThisFile = minOf(skip, source.length())
                input.channel.position(forThisFile)
                skip -= forThisFile
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (output.size() < MAX_SNAPSHOT_BYTES) {
                    val read = input.read(buffer, 0, minOf(buffer.size, MAX_SNAPSHOT_BYTES - output.size()))
                    if (read < 0) break
                    output.write(buffer, 0, read)
                }
            }
        }
        val bytes = output.toByteArray()
        var start = 0
        while (start < bytes.size && (bytes[start].toInt() and 0xc0) == 0x80) start++
        return bytes.copyOfRange(start, bytes.size).toString(Charsets.UTF_8)
    }

    @Synchronized
    fun clear() {
        (0 until files).forEach { Files.deleteIfExists(file(it).toPath()) }
    }

    private fun rotate() {
        file(files - 1).delete()
        for (index in files - 1 downTo 1) {
            val source = file(index - 1)
            if (source.isFile) atomicMove(source, file(index))
        }
    }

    private fun atomicMove(source: File, target: File) {
        try {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun file(index: Int) = File(directory, "app.$index.log")

    private fun truncateUtf8(value: String, maxBytes: Int): String {
        val bytes = value.toByteArray(Charsets.UTF_8)
        if (bytes.size <= maxBytes) return value
        val marker = " [TRUNCATED]".toByteArray(Charsets.UTF_8)
        val contentLimit = (maxBytes - marker.size).coerceAtLeast(0)
        var end = contentLimit
        while (end > 0 && end < bytes.size && (bytes[end].toInt() and 0xc0) == 0x80) end--
        return bytes.copyOfRange(0, end).toString(Charsets.UTF_8) +
            marker.copyOfRange(0, minOf(marker.size, maxBytes - end)).toString(Charsets.UTF_8)
    }

    private companion object {
        const val MAX_SNAPSHOT_BYTES = 1024 * 1024
        const val MIN_FILE_BYTES = 64L
    }
}
