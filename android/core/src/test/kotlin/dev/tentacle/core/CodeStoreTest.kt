package dev.tentacle.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class CodeStoreTest {
    @Test
    fun `disabled store neither parses nor retains and disabling clears`() {
        val store = CodeStore()
        assertEquals(CodeCaptureStatus.DISABLED, store.capture("验证码123456", "Sender", "sms").status)
        store.setEnabled(true)
        assertEquals(CodeCaptureStatus.RELAYED, store.capture("验证码123456", "Sender", "sms").status)
        store.setEnabled(false)
        assertTrue(store.listAfter(0).isEmpty())
        assertNull(store.latest())
        assertTrue(store.codesForRedaction().isEmpty())
    }

    @Test
    fun `deduplicates by code and sender for two minutes and expires after ten`() {
        var now = 1_000_000L
        val store = CodeStore(true) { now }
        assertEquals(CodeCaptureStatus.RELAYED, store.capture("验证码123456", "A", "notification").status)
        now += CodeStore.DEDUPLICATE_MS
        assertEquals(CodeCaptureStatus.DUPLICATE, store.capture("code 123456", "A", "sms").status)
        assertEquals(CodeCaptureStatus.RELAYED, store.capture("code 123456", "B", "sms").status)
        now += 1
        assertEquals(CodeCaptureStatus.RELAYED, store.capture("code 123456", "A", "sms").status)
        now += CodeStore.EXPIRY_MS
        assertTrue(store.listAfter(0).isEmpty())
    }

    @Test
    fun `deduplicates a recent code after its raw entry is evicted`() {
        var now = 1_000_000L
        val store = CodeStore(true) { now }
        assertEquals(CodeCaptureStatus.RELAYED, store.capture("code 3000", "First", "notification").status)
        repeat(10) { index ->
            now++
            assertEquals(
                CodeCaptureStatus.RELAYED,
                store.capture("code ${4000 + index}", "Sender$index", "notification").status,
            )
        }
        assertTrue(store.listAfter(0).none { it.sender == "First" })
        assertEquals(CodeCaptureStatus.DUPLICATE, store.capture("code 3000", "First", "sms").status)
        now = 1_000_000L + CodeStore.DEDUPLICATE_MS + 1
        assertEquals(CodeCaptureStatus.RELAYED, store.capture("code 3000", "First", "sms").status)
    }

    @Test
    fun `keeps the newest ten entries oldest first and truncates snippets`() {
        var now = 2_000_000L
        val store = CodeStore(true) { now++ }
        repeat(12) { index ->
            val code = (3000 + index).toString()
            val capture = store.capture("验证码$code，" + "x".repeat(200), "Sender$index", "notification")
            assertEquals(CodeCaptureStatus.RELAYED, capture.status)
        }
        val entries = store.listAfter(0)
        assertEquals(10, entries.size)
        assertEquals("3002", entries.first().code)
        assertEquals("3011", entries.last().code)
        assertEquals(160, entries.last().snippet.length)
    }

    @Test
    fun `payment match is reported without retaining its code`() {
        val store = CodeStore(true) { 3_000_000L }
        val result = store.capture("支付验证码112233", "Bank 112233", "sms")
        assertEquals(CodeCaptureStatus.BLOCKED_PAYMENT, result.status)
        assertEquals("Bank", result.sender)
        assertNull(result.entry)
        assertTrue(store.listAfter(0).isEmpty())
        assertTrue(store.codesForRedaction().isEmpty())
    }

    @Test
    fun `awaitAfter wakes for a newer code`() {
        val store = CodeStore(true)
        val started = CountDownLatch(1)
        var result = emptyList<CodeEntry>()
        val thread = Thread {
            started.countDown()
            result = store.awaitAfter(System.currentTimeMillis() - 1, 2_000)
        }.apply { start() }
        assertTrue(started.await(1, TimeUnit.SECONDS))
        store.capture("OTP 998877", "Service", "notification")
        thread.join(2_000)
        assertEquals("998877", result.single().code)
    }

    @Test
    fun `awaitAfter times out and disabling wakes a waiter`() {
        val store = CodeStore(true)
        val startedAt = System.nanoTime()
        assertTrue(store.awaitAfter(System.currentTimeMillis(), 50).isEmpty())
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt) >= 30)

        val started = CountDownLatch(1)
        var result: List<CodeEntry>? = null
        val thread = Thread {
            started.countDown()
            result = store.awaitAfter(System.currentTimeMillis(), 2_000)
        }.apply { start() }
        assertTrue(started.await(1, TimeUnit.SECONDS))
        store.setEnabled(false)
        thread.join(1_000)
        assertEquals(emptyList<CodeEntry>(), result)
    }
}
