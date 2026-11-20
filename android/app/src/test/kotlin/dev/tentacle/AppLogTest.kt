package dev.tentacle

import dev.tentacle.core.RotatingLog
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class AppLogTest {
    @TempDir lateinit var directory: File

    @Test
    fun `crash handler writes trace then delegates`() {
        val entries = mutableListOf<String>()
        var delegated = false
        val delegate = Thread.UncaughtExceptionHandler { _, _ -> delegated = true }
        val error = RuntimeException("test crash")

        CrashLoggingHandler(entries::add, delegate).uncaughtException(Thread.currentThread(), error)

        assertEquals(1, entries.size)
        assertTrue(entries.single().contains("CRASH thread="))
        assertTrue(entries.single().contains("RuntimeException: test crash"))
        assertTrue(delegated)
    }

    @Test
    fun `crash handler delegates when log write fails`() {
        var delegated = false
        val handler = CrashLoggingHandler({ throw IllegalStateException("disk full") }) { _, _ -> delegated = true }

        try {
            handler.uncaughtException(Thread.currentThread(), RuntimeException("test crash"))
        } catch (_: IllegalStateException) {
            // The platform handler still receives the crash even when persistent logging fails.
        }

        assertTrue(delegated)
    }

    @Test
    fun `crash handler writes through rotating log`() {
        val log = RotatingLog(directory)
        CrashLoggingHandler(log::append, null).uncaughtException(
            Thread("crashing-thread"), RuntimeException("test crash"),
        )

        assertTrue(log.snapshot().contains("CRASH thread=crashing-thread"))
        assertTrue(log.snapshot().contains("RuntimeException: test crash"))
    }
}
