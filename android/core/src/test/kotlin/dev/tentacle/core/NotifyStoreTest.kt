package dev.tentacle.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class NotifyStoreTest {
    private fun store(
        clock: () -> Long = System::currentTimeMillis,
        prepare: (NotifyMessage) -> NotifyDelivery = { NotifyDelivery(it.requestedLevel) },
        deliver: (NotifyMessage, NotifyDelivery) -> NotifyDelivery = { _, delivery -> delivery },
    ) = NotifyStore(prepare, deliver, clock, idGenerator = { "generated_id" })

    @Test
    fun `validates and normalizes notification content`() {
        val store = store()
        val entry = store.post(null, "Line one\nLine two", null, null)
        assertEquals("generated_id", entry.message.id)
        assertEquals("Tentacle", entry.message.title)
        assertEquals("normal", entry.delivery.level)
        assertEquals(false, entry.postJson()["downgraded"])
        assertEquals(true, entry.postJson()["shown"])

        listOf(
            { store.post(null, "", null, "empty") },
            { store.post(null, "x".repeat(1001), null, "long") },
            { store.post("x".repeat(101), "text", null, "title") },
            { store.post(null, "bad\ttext", null, "control") },
            { store.post(null, "text", "loud", "level") },
            { store.post(null, "text", null, "bad id") },
        ).forEach { invalid ->
            assertEquals(400, assertThrows(ApiException::class.java) { invalid(); Unit }.status)
        }

        assertEquals(100, "😀".repeat(100).codePointCount(0, "😀".repeat(100).length))
        assertEquals(200, store.post("😀".repeat(100), "text", null, "unicode").message.title.length)
    }

    @Test
    fun `same id is idempotent while different content conflicts`() {
        var deliveries = 0
        val store = store(deliver = { _, delivery -> deliveries++; delivery })
        val first = store.post("Title", "Text", "normal", "stable")
        val retry = store.post("Title", "Text", "normal", "stable")
        assertEquals(first, retry)
        assertEquals(1, deliveries)
        val conflict = assertThrows(ApiException::class.java) {
            store.post("Title", "Different", "normal", "stable")
        }
        assertEquals(409, conflict.status)
    }

    @Test
    fun `rate limits use actual level and sliding windows`() {
        var now = 1_000_000L
        val normal = store(clock = { now })
        repeat(10) { normal.post(null, "message $it", null, "n$it") }
        assertEquals(429, assertThrows(ApiException::class.java) {
            normal.post(null, "one too many", null, "n10")
        }.status)
        now += NotifyStore.NORMAL_WINDOW_MS
        normal.post(null, "allowed at next window", null, "n11")

        val urgent = store(clock = { now })
        repeat(3) { urgent.post(null, "urgent $it", "urgent", "u$it") }
        assertEquals(429, assertThrows(ApiException::class.java) {
            urgent.post(null, "urgent excess", "urgent", "u3")
        }.status)

        val downgraded = store(clock = { now }, prepare = {
            NotifyDelivery("normal", downgraded = true, reason = "urgent_not_allowed")
        })
        repeat(10) { downgraded.post(null, "downgraded $it", "urgent", "d$it") }
        assertEquals(429, assertThrows(ApiException::class.java) {
            downgraded.post(null, "downgraded excess", "urgent", "d10")
        }.status)
    }

    @Test
    fun `sliding windows expire only deliveries outside each actual level window`() {
        var now = 0L
        val normal = store(clock = { now })
        repeat(5) { normal.post(null, "first $it", "normal", "nf$it") }
        now = 30_000L
        repeat(5) { normal.post(null, "second $it", "normal", "ns$it") }
        assertEquals(429, assertThrows(ApiException::class.java) {
            normal.post(null, "normal blocked", "normal", "nb")
        }.status)
        now = NotifyStore.NORMAL_WINDOW_MS
        repeat(5) { normal.post(null, "third $it", "normal", "nt$it") }
        assertEquals(429, assertThrows(ApiException::class.java) {
            normal.post(null, "still blocked by second half", "normal", "nb2")
        }.status)
        now = 90_000L
        repeat(5) { normal.post(null, "fourth $it", "normal", "n4$it") }

        now = 0L
        val urgent = store(clock = { now })
        repeat(2) { urgent.post(null, "early urgent $it", "urgent", "ue$it") }
        now = 9 * 60_000L
        urgent.post(null, "late urgent", "urgent", "ul")
        assertEquals(429, assertThrows(ApiException::class.java) {
            urgent.post(null, "urgent blocked", "urgent", "ub")
        }.status)
        now = NotifyStore.URGENT_WINDOW_MS
        repeat(2) { urgent.post(null, "boundary urgent $it", "urgent", "ux$it") }
        assertEquals(429, assertThrows(ApiException::class.java) {
            urgent.post(null, "late urgent still counts", "urgent", "ub2")
        }.status)
        now = 19 * 60_000L
        urgent.post(null, "late urgent expired", "urgent", "uf")
    }

    @Test
    fun `idempotent retry bypasses exhausted limit and preserves acknowledged terminal result`() {
        var now = 1_000L
        var deliveries = 0
        val store = store(clock = { now }, deliver = { _, delivery -> deliveries++; delivery })
        val original = store.post(null, "Stable", "normal", "stable")
        repeat(9) { store.post(null, "fill $it", "normal", "fill$it") }
        assertEquals(429, assertThrows(ApiException::class.java) {
            store.post(null, "blocked", "normal", "blocked")
        }.status)
        assertEquals(original, store.post(null, "Stable", "normal", "stable"))
        assertEquals(10, deliveries)

        now = 2_000L
        val acknowledged = store.transition("stable", "acknowledged")
        assertEquals(2_000L, acknowledged.acknowledgedMs)
        val retry = store.post(null, "Stable", "normal", "stable")
        assertEquals("acknowledged", retry.state)
        assertEquals(2_000L, retry.acknowledgedMs)
        assertEquals(10, deliveries)
        now = 3_000L
        assertEquals(acknowledged, store.transition("stable", "dismissed"))
        assertEquals(2_000L, store.get("stable").acknowledgedMs)
    }

    @Test
    fun `delivery result is retained and only final transitions change shown`() {
        var now = 42L
        val store = store(clock = { now }, prepare = {
            NotifyDelivery("urgent", reason = "full_screen_not_allowed")
        }, deliver = { _, prepared -> prepared.copy(shown = false, reason = "notifications_disabled") })
        val entry = store.post(null, "Wake up", "urgent", "alert")
        assertEquals(false, entry.postJson()["shown"])
        assertEquals("notifications_disabled", entry.postJson()["reason"])
        assertEquals("expired", entry.state)
        now = 84L
        val acknowledged = store.transition("alert", "acknowledged")
        assertEquals("expired", acknowledged.state)
        assertEquals(null, acknowledged.acknowledgedMs)
        assertEquals(acknowledged, store.transition("alert", "dismissed"))
        assertEquals(404, assertThrows(ApiException::class.java) { store.get("missing") }.status)
    }

    @Test
    fun `awaitChange wakes on acknowledgement and times out while shown`() {
        val store = store()
        store.post(null, "Wait", null, "wait")
        val started = CountDownLatch(1)
        lateinit var result: NotifyEntry
        val thread = Thread {
            started.countDown()
            result = store.awaitChange("wait", 2_000)
        }.apply { start() }
        assertTrue(started.await(1, TimeUnit.SECONDS))
        Thread.sleep(30)
        assertTrue(thread.isAlive)
        store.transition("wait", "acknowledged")
        thread.join(1_000)
        assertFalse(thread.isAlive)
        assertEquals("acknowledged", result.state)

        store.post(null, "No response", null, "timeout")
        assertEquals("shown", store.awaitChange("timeout", 20).state)
    }

    @Test
    fun `keeps only the newest fifty notifications`() {
        var next = 0
        val store = NotifyStore(
            prepare = { NotifyDelivery(it.requestedLevel) },
            deliver = { _, delivery -> delivery },
            clock = { next.toLong() * NotifyStore.NORMAL_WINDOW_MS },
            idGenerator = { "generated_${next++}" },
        )
        repeat(51) { index -> store.post(null, "message $index", null, null) }
        assertEquals(404, assertThrows(ApiException::class.java) { store.get("generated_0") }.status)
        assertEquals("message 50", store.get("generated_50").message.text)
    }
}
