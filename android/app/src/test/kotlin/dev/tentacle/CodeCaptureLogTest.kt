package dev.tentacle

import dev.tentacle.codes.CodeCaptureLog
import dev.tentacle.core.CodeCaptureStatus
import dev.tentacle.core.CodeStore
import dev.tentacle.core.Redactor
import dev.tentacle.core.RotatingLog
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class CodeCaptureLogTest {
    @TempDir lateinit var directory: File

    @Test fun `captured codes and message text never enter app log`() {
        val store = CodeStore(initialEnabled = true)
        val log = RotatingLog(directory, redactor = Redactor(currentCodes = store::codesForRedaction))
        val received = CodeCaptureLog.capture(store, "【Aliyun】Your code is AB12CD. Private message.",
            "Messages", "notification", log::append)
        assertEquals(CodeCaptureStatus.RELAYED, received.status)
        // Even an accidental later caller passing the captured value is masked before disk.
        log.append("accidental value: ${received.entry!!.code}")
        val snapshot = log.snapshot()
        assertTrue(snapshot.contains("verification code received from Aliyun (relayed)"))
        assertFalse(snapshot.contains("AB12CD"))
        assertFalse(snapshot.contains("Private message"))
        directory.listFiles()!!.forEach { assertFalse(it.readText().contains("AB12CD")) }
    }

    @Test fun `payment and sender containing code are logged without secrets`() {
        val store = CodeStore(initialEnabled = true)
        val entries = mutableListOf<String>()
        CodeCaptureLog.capture(store, "Payment code: 112233", "Your code 112233", "sms", entries::add)
        assertEquals(1, entries.size)
        assertTrue(entries.single().endsWith("(blocked: payment)"))
        assertFalse(entries.single().contains("112233"))
        assertNull(store.latest())
        assertFalse(CodeCaptureLog.safeSender("Your code: 123 456").contains("123"))
    }

    @Test fun `disabled and duplicate captures produce no log`() {
        val store = CodeStore()
        val entries = mutableListOf<String>()
        CodeCaptureLog.capture(store, "验证码123456", "Example", "sms", entries::add)
        assertTrue(entries.isEmpty())
        store.setEnabled(true)
        CodeCaptureLog.capture(store, "验证码123456", "Example", "sms", entries::add)
        CodeCaptureLog.capture(store, "验证码123456", "Example", "notification", entries::add)
        assertEquals(1, entries.size)
    }
}
