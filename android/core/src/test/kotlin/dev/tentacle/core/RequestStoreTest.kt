package dev.tentacle.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

class RequestStoreTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `create validates requests and attaches logs`() {
        var now = 100L
        val store = RequestStore(tempDir.toFile()) { now++ }

        val feature = store.create("feature", "Add a setting")
        val bug = store.create("bug", "", "private diagnostic log")

        assertEquals(RequestText.SCOPE, feature.scope)
        assertEquals("new", feature.status)
        assertFalse(feature.hasLog)
        assertTrue(bug.hasLog)
        assertEquals("private diagnostic log", store.log(bug.id))
        assertEquals(listOf(bug.id, feature.id), store.list().map { it.id })
        assertNotEquals(feature.id, bug.id)
        assertApi(400) { store.create("feature", "") }
        assertApi(400) { store.create("other", "text") }
        assertApi(404) { store.log(feature.id) }
        assertApi(404) { store.log("r-missing") }
    }

    @Test
    fun `update validates status message and request id`() {
        var now = 1_000L
        val store = RequestStore(tempDir.toFile()) { now++ }
        val first = store.create("feature", "First")
        assertApi(400) { store.update(first.id, "invalid", "") }
        assertApi(400) { store.update(first.id, "working", "x".repeat(1001)) }
        assertApi(404) { store.update("r-missing", "working", "") }
    }

    @Test
    fun `update allows exactly the contract transition matrix`() {
        var now = 2_000L
        val store = RequestStore(tempDir.toFile()) { now++ }
        val statuses = listOf("new", "working", "done", "failed")
        val allowed = setOf(
            "new" to "working", "new" to "failed",
            "working" to "done", "working" to "failed", "working" to "new",
            "done" to "done", "done" to "failed",
            "failed" to "done", "failed" to "failed", "failed" to "new",
        )

        statuses.forEach { from ->
            statuses.forEach { to ->
                val request = requestInStatus(store, from)
                if (from to to in allowed) {
                    val result = store.update(request.id, to, "message for $from to $to")
                    assertEquals(to, result.status, "$from to $to")
                    assertEquals(
                        if (from == "failed" && to == "new") "" else "message for $from to $to",
                        result.message,
                        "$from to $to",
                    )
                    assertTrue(result.updatedMs > request.updatedMs, "$from to $to")
                } else {
                    assertApi(409, "$from to $to") { store.update(request.id, to, "forbidden") }
                }
            }
        }
    }

    @Test
    fun `final follow-up updates persist and notify listeners`() {
        var now = 3_000L
        val store = RequestStore(tempDir.toFile()) { now++ }
        val request = requestInStatus(store, "done")
        val observed = mutableListOf<AppRequest>()
        store.observe { observed += store.list().first { it.id == request.id } }

        val failed = store.update(request.id, "failed", "The maintainer closed it")
        val done = store.update(request.id, "done", "The maintainer merged it")

        assertEquals("failed", failed.status)
        assertEquals("The maintainer closed it", failed.message)
        assertEquals("done", done.status)
        assertEquals("The maintainer merged it", done.message)
        assertTrue(done.updatedMs > failed.updatedMs)
        assertEquals(listOf(failed, done), observed)
        assertEquals(done, RequestStore(tempDir.toFile()).list().first { it.id == request.id })
    }

    @Test
    fun `store persists requests poll time and recovers corrupt state`() {
        var now = 2_000L
        val store = RequestStore(tempDir.toFile()) { now++ }
        val request = store.create("feature", "Persist me")
        store.recordPoll()
        val polled = store.lastPolledMs
        store.update(request.id, "working", "Running")

        val restored = RequestStore(tempDir.toFile()) { now++ }
        assertEquals(polled, restored.lastPolledMs)
        assertEquals("working", restored.list().single().status)
        assertEquals("Running", restored.list().single().message)

        Files.writeString(tempDir.resolve("requests.json"), "not-json")
        val recovered = RequestStore(tempDir.toFile()) { now++ }
        assertTrue(recovered.list().isEmpty())
        assertTrue(Files.readString(tempDir.resolve("requests.json.bad")).contains("not-json"))
    }

    @Test
    fun `load rejects duplicate ids and trims stale request logs`() {
        val requests = (0..20).map { index -> persistedRequest("r-$index", index.toLong()) }
        Files.writeString(tempDir.resolve("requests.json"), Json.stringify(mapOf(
            "last_polled_ms" to 0,
            "requests" to requests,
        )))
        Files.writeString(tempDir.resolve("r-0.log"), "stale")

        val store = RequestStore(tempDir.toFile())
        assertEquals(20, store.list().size)
        assertFalse(Files.exists(tempDir.resolve("r-0.log")))

        val duplicate = listOf(persistedRequest("same", 2), persistedRequest("same", 1))
        Files.writeString(tempDir.resolve("requests.json"), Json.stringify(mapOf(
            "last_polled_ms" to 0,
            "requests" to duplicate,
        )))
        val recovered = RequestStore(tempDir.toFile())
        assertTrue(recovered.list().isEmpty())
        assertTrue(Files.exists(tempDir.resolve("requests.json.bad")))
    }

    @Test
    fun `store keeps newest twenty requests and removes their old logs`() {
        var now = 10_000L
        val store = RequestStore(tempDir.toFile()) { now++ }
        val oldest = store.create("bug", "oldest", "old log")
        repeat(20) { store.create("feature", "request $it") }

        assertEquals(20, store.list().size)
        assertFalse(store.list().any { it.id == oldest.id })
        assertFalse(Files.exists(tempDir.resolve("${oldest.id}.log")))
        assertApi(404) { store.log(oldest.id) }
    }

    @Test
    fun `create remains newest first when the clock moves backward`() {
        val times = ArrayDeque(listOf(200L, 100L, 300L))
        val store = RequestStore(tempDir.toFile()) { times.removeFirst() }
        val first = store.create("feature", "first")
        val second = store.create("feature", "second")
        val third = store.create("feature", "third")

        assertEquals(listOf(third.id, first.id, second.id), store.list().map { it.id })
        assertEquals(listOf(third.id, first.id, second.id), RequestStore(tempDir.toFile()).list().map { it.id })
    }

    @Test
    fun `awaitNew wakes on creation and times out without a new request`() {
        val store = RequestStore(tempDir.toFile())
        val started = CountDownLatch(1)
        var result: List<AppRequest> = emptyList()
        val waiter = thread {
            started.countDown()
            result = store.awaitNew(2_000)
        }
        assertTrue(started.await(1, TimeUnit.SECONDS))
        Thread.sleep(50)
        store.create("feature", "Wake up")
        waiter.join(1_000)
        assertFalse(waiter.isAlive)
        assertEquals("Wake up", result.single().text)

        val request = result.single()
        store.update(request.id, "working", "")
        val before = System.nanoTime()
        val timedOut = store.awaitNew(80)
        val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - before)
        assertTrue(timedOut.none { it.status == "new" })
        assertTrue(elapsedMs >= 50, "elapsed $elapsedMs ms")
    }

    @Test
    fun `retry clears the failure and wakes awaitNew`() {
        var now = 4_000L
        val store = RequestStore(tempDir.toFile()) { now++ }
        val request = requestInStatus(store, "failed")
        val started = CountDownLatch(1)
        var result: List<AppRequest> = emptyList()
        val observed = mutableListOf<AppRequest>()
        store.observe { observed += store.list().first { it.id == request.id } }
        val waiter = thread {
            started.countDown()
            result = store.awaitNew(2_000)
        }
        assertTrue(started.await(1, TimeUnit.SECONDS))
        Thread.sleep(50)
        assertEquals(Thread.State.TIMED_WAITING, waiter.state)

        val retried = store.update(request.id, "new", "must be discarded")

        waiter.join(1_000)
        assertFalse(waiter.isAlive)
        assertEquals("new", retried.status)
        assertEquals("", retried.message)
        assertTrue(retried.updatedMs > request.updatedMs)
        assertEquals(retried, result.first { it.id == request.id })
        assertEquals(listOf(retried), observed)
    }

    @Test
    fun `listeners run outside the store lock and can be removed`() {
        val store = RequestStore(tempDir.toFile())
        val calls = AtomicInteger()
        val subscription = store.observe {
            calls.incrementAndGet()
            store.list()
        }
        store.create("feature", "Notify")
        store.recordPoll()
        assertEquals(2, calls.get())
        subscription.close()
        store.update(store.list().single().id, "working", "")
        assertEquals(2, calls.get())
    }

    @Test
    fun `concurrent creates remain complete and uniquely identified`() {
        val store = RequestStore(tempDir.toFile()) { 50_000L }
        val start = CountDownLatch(1)
        val threads = (1..20).map { index ->
            thread {
                start.await()
                store.create("feature", "request $index")
            }
        }
        start.countDown()
        threads.forEach { it.join() }
        assertEquals(20, store.list().size)
        assertEquals(20, store.list().map { it.id }.toSet().size)
        assertEquals(20, RequestStore(tempDir.toFile()).list().size)
    }

    @Test
    fun `failed persistence leaves in-memory and disk state unchanged`() {
        var now = 70_000L
        val store = RequestStore(tempDir.toFile()) { now++ }
        val request = store.create("feature", "Original")
        val persisted = Files.readString(tempDir.resolve("requests.json"))
        val tempPath = tempDir.resolve(".requests.json.tmp")
        Files.createDirectory(tempPath)

        assertThrows(Exception::class.java) { store.create("bug", "New", "new log") }
        assertEquals(listOf(request), store.list())
        assertEquals(persisted, Files.readString(tempDir.resolve("requests.json")))
        assertEquals(0, Files.list(tempDir).use { files -> files.filter { it.fileName.toString().endsWith(".log") }.count() })

        assertThrows(Exception::class.java) { store.update(request.id, "working", "Claim") }
        assertEquals("new", store.list().single().status)
        assertEquals(persisted, Files.readString(tempDir.resolve("requests.json")))

        assertThrows(Exception::class.java) { store.recordPoll() }
        assertEquals(0, store.lastPolledMs)
    }

    @Test
    fun `clock rollback does not leave a log for a request outside newest twenty`() {
        var now = 100L
        val store = RequestStore(tempDir.toFile()) { now++ }
        repeat(20) { store.create("feature", "request $it") }
        now = 1L
        val discarded = store.create("bug", "", "discarded log")
        assertEquals(20, store.list().size)
        assertFalse(store.list().any { it.id == discarded.id })
        assertFalse(Files.exists(tempDir.resolve("${discarded.id}.log")))
    }

    private fun requestInStatus(store: RequestStore, status: String): AppRequest {
        val request = store.create("feature", "request for $status")
        return when (status) {
            "new" -> request
            "working" -> store.update(request.id, "working", "Working")
            "done" -> store.update(request.id, "working", "Working").let {
                store.update(request.id, "done", "Done")
            }
            "failed" -> store.update(request.id, "failed", "Failed")
            else -> error("Unknown status $status")
        }
    }

    private fun assertApi(status: Int, message: String? = null, block: () -> Unit) {
        val error = assertThrows(ApiException::class.java, block)
        assertEquals(status, error.status, message)
    }

    private fun persistedRequest(id: String, time: Long): Map<String, Any> = mapOf(
        "id" to id,
        "kind" to "feature",
        "scope" to RequestText.SCOPE,
        "text" to "request $id",
        "created_ms" to time,
        "updated_ms" to time,
        "status" to "new",
        "message" to "",
        "has_log" to false,
    )
}
