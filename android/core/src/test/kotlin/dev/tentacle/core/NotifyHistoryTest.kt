package dev.tentacle.core

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException
import java.nio.file.Path

class NotifyHistoryTest {
    @TempDir lateinit var directory: Path
    private val file get() = directory.resolve("history.json").toFile()
    private fun record(history: NotifyHistory, id: String, level: String = "normal", text: String = "Full text\n中文 😀") {
        history.record(NotifyMessage(id, "Title $id", text, level), NotifyDelivery(level))
    }

    @Test fun `opening one of three alerts expands all three and keeps old history separate`() {
        val history = NotifyHistory(file) { 1234L }
        record(history, "old")
        history.open()
        repeat(3) { record(history, "new$it") }

        val view = history.open(listOf("new1"))
        assertEquals(listOf("new2", "new1", "new0"), view.current.map { it.id })
        assertEquals(listOf("old"), view.history.map { it.id })
        assertEquals("Full text\n中文 😀", view.current[0].text)
        assertEquals(1234L, view.current[0].receivedMs)
        assertTrue(view.current.all { it.viewed })
        assertEquals(view, history.open(view.current.map { it.id }))
        assertTrue(history.open().current.isEmpty())
    }

    @Test fun `restart preserves text and viewed flags and rotation preserves the current batch`() {
        record(NotifyHistory(file), "first")
        val restored = NotifyHistory(file)
        val firstVisit = restored.open()
        record(restored, "second")
        val recreated = NotifyHistory(file).open(firstVisit.current.map { it.id })
        assertEquals(listOf("second", "first"), recreated.current.map { it.id })
        assertTrue(recreated.history.isEmpty())
        assertTrue(NotifyHistory(file).open().current.isEmpty())
        assertEquals(2, NotifyHistory(file).open().history.size)
    }

    @Test fun `tapped viewed alert can be read again and missing tap does not hide new alerts`() {
        val history = NotifyHistory(file)
        record(history, "old")
        history.open()
        record(history, "new")
        val view = history.open(listOf("old", "missing"))
        assertEquals(listOf("new", "old"), view.current.map { it.id })
        assertTrue(view.history.isEmpty())
    }

    @Test fun `duplicate delivery preserves read state and failed delivery is not added`() {
        val history = NotifyHistory(file)
        record(history, "same")
        history.open()
        record(history, "same")
        history.record(NotifyMessage("failed", "Title", "Text", "normal"), NotifyDelivery("normal", shown = false))
        assertTrue(history.open().current.isEmpty())
        assertEquals(listOf("same"), history.open().history.map { it.id })
    }

    @Test fun `viewing forced and ring alerts never acknowledges or stops them`() {
        val history = NotifyHistory(file)
        val store = NotifyStore(prepare = { NotifyDelivery(it.requestedLevel) }, deliver = { message, delivery ->
            history.record(message, delivery)
            delivery
        })
        store.post(null, "Forced", "forced", "forced")
        store.post(null, "Ring", "urgent", "ring")
        val view = history.open()
        assertEquals(listOf("urgent", "forced"), view.current.map { it.level })
        assertEquals("shown", store.get("forced").state)
        assertEquals("shown", store.get("ring").state)
    }

    @Test fun `acknowledging or swiping system notification leaves its full text in history`() {
        val history = NotifyHistory(file)
        val store = NotifyStore(prepare = { NotifyDelivery(it.requestedLevel) }, deliver = { message, delivery ->
            history.record(message, delivery)
            delivery
        })
        store.post(null, "Text from tapped alert", "normal", "tapped")
        store.post(null, "Text from swiped alert", "normal", "swiped")
        store.transition("tapped", "acknowledged")
        store.transition("swiped", "dismissed")

        val view = NotifyHistory(file).open(listOf("tapped"))
        assertEquals(listOf("Text from swiped alert", "Text from tapped alert"), view.current.map { it.text })
    }

    @Test fun `failed viewed-state write leaves messages available to read and retries safely`() {
        val history = NotifyHistory(file)
        record(history, "saved")
        val temporary = directory.resolve(".history.json.tmp").toFile()
        assertTrue(temporary.mkdir())
        val blocker = File(temporary, "blocker").apply { writeText("prevent replacement") }
        assertThrows(IOException::class.java) { history.open() }
        assertFalse(history.snapshot().current.single().viewed)
        assertFalse(NotifyHistory(file).snapshot().current.single().viewed)
        assertTrue(blocker.delete())
        assertTrue(temporary.delete())
        assertEquals("saved", history.open().current.single().id)
        assertTrue(NotifyHistory(file).open().current.isEmpty())
    }

    @Test fun `retention bounds disk history and preserves newest receipt order`() {
        val history = NotifyHistory(file) { 1L }
        repeat(NotifyHistory.MAX_ENTRIES + 3) { record(history, "id$it") }
        val restored = NotifyHistory(file).open()
        assertEquals(NotifyHistory.MAX_ENTRIES, restored.current.size)
        assertEquals("id${NotifyHistory.MAX_ENTRIES + 2}", restored.current.first().id)
        assertEquals("id3", restored.current.last().id)
        assertFalse(File(directory.toFile(), ".history.json.tmp").exists())
    }

    @Test fun `corrupt file does not block new deliveries`() {
        file.writeText("incomplete JSON")
        val history = NotifyHistory(file)
        assertTrue(history.open().current.isEmpty())
        record(history, "recovered")
        assertEquals("recovered", NotifyHistory(file).open().current.single().id)
    }
}
