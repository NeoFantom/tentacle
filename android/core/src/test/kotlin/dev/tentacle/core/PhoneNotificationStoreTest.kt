package dev.tentacle.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class PhoneNotificationStoreTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `disabled store ignores input and disabling clears entries and seen apps`() {
        val store = PhoneNotificationStore(tempDir.toFile(), clock = { NOW })
        assertNull(store.capture(input("one")))
        store.setEnabled(true)
        store.capture(input("one"))
        assertEquals(1, store.count())
        assertEquals(mapOf("example.chat" to "Chat"), store.seenApps())

        store.setEnabled(false)

        assertEquals(0, store.count())
        assertTrue(store.seenApps().isEmpty())
        assertTrue(PhoneNotificationStore(tempDir.toFile()).query().isEmpty())
    }

    @Test
    fun `restart while disabled erases a persisted feed before it can be reenabled`() {
        val enabled = PhoneNotificationStore(tempDir.toFile(), true) { NOW }
        enabled.capture(input("private"))
        assertEquals(1, enabled.count())

        val disabledAfterRestart = PhoneNotificationStore(tempDir.toFile(), false) { NOW }
        assertFalse(disabledAfterRestart.isEnabled)
        assertEquals(0, disabledAfterRestart.count())
        assertTrue(disabledAfterRestart.seenApps().isEmpty())

        disabledAfterRestart.setEnabled(true)
        assertTrue(disabledAfterRestart.query().isEmpty())
        val loadedAgain = PhoneNotificationStore(tempDir.toFile(), true) { NOW }
        assertTrue(loadedAgain.query().isEmpty())
        assertTrue(loadedAgain.seenApps().isEmpty())
    }

    @Test
    fun `startup removes an incomplete atomic write even without a main state file`() {
        Files.writeString(tempDir.resolve(".phone-notifications.json.tmp"), "private notification text")

        PhoneNotificationStore(tempDir.toFile(), false) { NOW }

        assertFalse(Files.exists(tempDir.resolve(".phone-notifications.json.tmp")))
    }

    @Test
    fun `filtered apps are remembered but not stored and clear preserves picker history`() {
        val store = PhoneNotificationStore(tempDir.toFile(), true) { NOW }
        assertNull(store.capture(input("ongoing", ongoing = true)))
        assertNull(store.capture(input("excluded", packageName = "example.blocked"), excludedPackages = setOf("example.blocked")))
        assertNull(store.capture(input("summary", groupSummary = true)))
        store.capture(input("kept"))
        store.clear()

        assertEquals(0, store.count())
        assertEquals(setOf("example.chat", "example.blocked"), store.seenApps().keys)
        assertEquals(store.seenApps(), PhoneNotificationStore(tempDir.toFile(), true) { NOW }.seenApps())
    }

    @Test
    fun `query is newest update first filters package and limits results`() {
        var now = NOW
        val store = PhoneNotificationStore(tempDir.toFile(), true) { now }
        val first = store.capture(input("first", packageName = "example.first"))!!
        val second = store.capture(input("second", packageName = "example.second"))!!
        val third = store.capture(input("third", packageName = "example.first"))!!

        assertTrue(first.updatedMs < second.updatedMs && second.updatedMs < third.updatedMs)
        assertEquals(listOf("third", "second"), store.query(limit = 2).map { it.key })
        assertEquals(listOf("third", "first"), store.query(app = "example.first").map { it.key })
        assertEquals(listOf("third", "second"), store.query(sinceMs = first.updatedMs).map { it.key })
    }

    @Test
    fun `removal records removal time persists and advances the cursor`() {
        var now = NOW
        val store = PhoneNotificationStore(tempDir.toFile(), true) { now }
        val posted = store.capture(input("one"))!!
        now += 20

        val removed = store.markRemoved("one")!!

        assertEquals(now, removed.removedMs)
        assertTrue(removed.updatedMs > posted.updatedMs)
        assertNull(store.markRemoved("missing"))
        assertEquals(removed, PhoneNotificationStore(tempDir.toFile(), true) { now }.query().single())
    }

    @Test
    fun `notification identity is preserved for long keys and removal`() {
        val store = PhoneNotificationStore(tempDir.toFile(), true) { NOW }
        val longKey = "key:" + "x".repeat(2_000)

        assertEquals(longKey, store.capture(input(longKey))!!.key)
        assertEquals(longKey, store.markRemoved(longKey)!!.key)
        assertEquals(NOW, store.query().single().removedMs)
    }

    @Test
    fun `store caps at five hundred and expires entries after three days at runtime`() {
        var now = NOW
        val store = PhoneNotificationStore(tempDir.toFile(), true) { now }
        repeat(501) { index -> store.capture(input("key-$index", postTimeMs = now)) }
        assertEquals(PhoneNotificationStore.MAX_ENTRIES, store.count())
        assertFalse(store.query().any { it.key == "key-0" })

        now += PhoneNotificationStore.RETENTION_MS
        assertEquals(0, store.count())
        assertTrue(PhoneNotificationStore(tempDir.toFile(), true) { now }.query().isEmpty())
        assertNull(store.capture(input("too-old", postTimeMs = now - PhoneNotificationStore.RETENTION_MS - 1)))
    }

    @Test
    fun `startup removes expired notification content from persistent state`() {
        var now = NOW
        val store = PhoneNotificationStore(tempDir.toFile(), true) { now }
        store.capture(input("expired-private-text"))
        now += PhoneNotificationStore.RETENTION_MS

        val restarted = PhoneNotificationStore(tempDir.toFile(), true) { now }

        assertTrue(restarted.query().isEmpty())
        assertFalse(Files.readString(tempDir.resolve("phone-notifications.json")).contains("expired-private-text"))
    }

    @Test
    fun `store restores cursor after clear and recovers corrupt state without retaining a backup`() {
        var now = NOW
        val store = PhoneNotificationStore(tempDir.toFile(), true) { now }
        val beforeClear = store.capture(input("one"))!!.updatedMs
        store.clear()
        now -= 1_000
        val restored = PhoneNotificationStore(tempDir.toFile(), true) { now }
        val afterClear = restored.capture(input("two", postTimeMs = now))!!.updatedMs
        assertTrue(afterClear > beforeClear)

        Files.writeString(tempDir.resolve("phone-notifications.json"), "not-json")
        val recovered = PhoneNotificationStore(tempDir.toFile(), true) { now }
        assertTrue(recovered.query().isEmpty())
        assertFalse(Files.exists(tempDir.resolve("phone-notifications.json.bad")))
        assertTrue(Files.readString(tempDir.resolve("phone-notifications.json")).startsWith("{"))
    }

    @Test
    fun `clear and disable fail closed when replacing the state file fails`() {
        val clearDirectory = tempDir.resolve("clear")
        val clearStore = PhoneNotificationStore(clearDirectory.toFile(), true) { NOW }
        clearStore.capture(input("private-clear"))
        blockStateTarget(clearDirectory)

        clearStore.clear()

        assertEquals(0, clearStore.count())
        assertFalse(Files.exists(clearDirectory.resolve("phone-notifications.json")))
        assertFalse(Files.exists(clearDirectory.resolve(".phone-notifications.json.tmp")))

        val disableDirectory = tempDir.resolve("disable")
        val disableStore = PhoneNotificationStore(disableDirectory.toFile(), true) { NOW }
        disableStore.capture(input("private-disable"))
        blockStateTarget(disableDirectory)

        disableStore.setEnabled(false)

        assertFalse(disableStore.isEnabled)
        assertEquals(0, disableStore.count())
        assertTrue(disableStore.seenApps().isEmpty())
        assertFalse(Files.exists(disableDirectory.resolve("phone-notifications.json")))
        assertFalse(Files.exists(disableDirectory.resolve(".phone-notifications.json.tmp")))
    }

    @Test
    fun `await wakes only for a matching newer update and disabling wakes a waiter`() {
        val store = PhoneNotificationStore(tempDir.toFile(), true) { NOW }
        val started = CountDownLatch(1)
        var result = emptyList<PhoneNotificationEntry>()
        val waiter = thread {
            started.countDown()
            result = store.awaitNewerThan(NOW, 2_000, app = "example.wanted")
        }
        assertTrue(started.await(1, TimeUnit.SECONDS))
        store.capture(input("other", packageName = "example.other"))
        Thread.sleep(40)
        assertTrue(waiter.isAlive)
        store.capture(input("wanted", packageName = "example.wanted"))
        waiter.join(1_000)
        assertFalse(waiter.isAlive)
        assertEquals("wanted", result.single().key)

        val disabling = thread { result = store.awaitNewerThan(Long.MAX_VALUE, 2_000) }
        Thread.sleep(40)
        store.setEnabled(false)
        disabling.join(1_000)
        assertFalse(disabling.isAlive)
        assertTrue(result.isEmpty())
    }

    private fun input(
        key: String,
        packageName: String = "example.chat",
        postTimeMs: Long = NOW,
        ongoing: Boolean = false,
        groupSummary: Boolean = false,
    ) = PhoneNotificationInput(
        key = key, packageName = packageName, appLabel = if (packageName == "example.chat") "Chat" else packageName,
        title = "Title", text = "Body", postTimeMs = postTimeMs,
        ongoing = ongoing, groupSummary = groupSummary,
    )

    private fun blockStateTarget(directory: Path) {
        val state = directory.resolve("phone-notifications.json")
        Files.delete(state)
        Files.createDirectory(state)
    }

    private companion object {
        const val NOW = 1_000_000_000L
    }
}
