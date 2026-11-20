package dev.tentacle.notify

import dev.tentacle.core.Redactor
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class NotifyPolicyTest {
    private val allowed = NotifyPermissions(true, true, true, true, true)

    @Test fun `urgent requires owner opt in and downgrade uses normal channel permissions`() {
        val result = NotifyPolicy.decide("urgent", allowed.copy(urgentAllowed = false, urgentChannelAllowed = false))
        assertEquals(NotifyDecision("normal", true, true, "urgent_not_allowed"), result)
        assertEquals("channel_blocked", NotifyPolicy.decide("urgent",
            allowed.copy(urgentAllowed = false, normalChannelAllowed = false)).reason)
    }

    @Test fun `full screen denial keeps urgent level and notification permission denial prevents delivery`() {
        assertEquals(NotifyDecision("urgent", false, true, "full_screen_not_allowed"),
            NotifyPolicy.decide("urgent", allowed.copy(fullScreenAllowed = false)))
        assertEquals(NotifyDecision("urgent", false, false, "notifications_disabled"),
            NotifyPolicy.decide("urgent", allowed.copy(notificationsAllowed = false, fullScreenAllowed = false)))
        assertFalse(NotifyPolicy.decide("urgent", allowed.copy(urgentChannelAllowed = false)).shown)
        assertTrue(NotifyPolicy.decide("normal", allowed.copy(fullScreenAllowed = false, urgentChannelAllowed = false)).shown)
    }

    @Test fun `alarm budget cannot restart through recreation or delayed delivery`() {
        var now = 1_000L
        val deadline = AlertDeadline { now }
        deadline.register("first")
        now += 90_000
        assertEquals(30_000L, deadline.remaining("first"))
        deadline.register("second")
        now += 30_000
        assertEquals(0L, deadline.remaining("first"))
        assertEquals(90_000L, deadline.remaining("second"))
        now += 500_000
        assertEquals(0L, deadline.remaining("second"))
        assertEquals(0L, deadline.remaining("unknown"))
        deadline.register("first")
        assertEquals(120_000L, deadline.remaining("first"))
    }

    @Test fun `notification log redacts credentials before preview truncation`() {
        val secret = "private-secret-with-a-long-suffix"
        val redactor = Redactor(currentSecrets = { listOf(secret) }, currentCodes = { listOf("123456") })
        val message = NotifyLog.delivery("normal", true, false, "x".repeat(45) + secret + " code: 123456", redactor)
        assertFalse(message.contains("private-secret"))
        assertFalse(message.contains("123456"))
        assertTrue(message.contains("[REDACTED]"))
        val unicode = NotifyLog.delivery("normal", true, false, "😀".repeat(80), redactor).substringAfter("text=")
        assertEquals(60, unicode.codePointCount(0, unicode.length))
    }

    @Test fun `activity recreation re arms stop callback for only the remaining alarm budget`() {
        var now = 1_000L
        val deadlines = AlertDeadline { now }
        val scheduled = linkedMapOf<Runnable, Long>()
        val timer = AlarmTimeouts(deadlines, { task, delay -> scheduled[task] = delay }, scheduled::remove)
        var ringing = true
        deadlines.register("alert")
        timer.arm("alert") { ringing = false }
        assertEquals(120_000L, scheduled.values.single())
        now += 90_000L
        timer.cancel("alert")
        assertTrue(scheduled.isEmpty())
        // The new Activity uses startAlarm, which re-arms this timer without registering again.
        timer.arm("alert") { ringing = false }
        assertEquals(30_000L, scheduled.values.single())
        now += 30_000L
        scheduled.keys.single().run()
        assertFalse(ringing)
        assertTrue(timer.ids.isEmpty())
    }
}
