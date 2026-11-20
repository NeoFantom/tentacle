package dev.tentacle.notify

import android.app.Notification
import android.app.NotificationManager
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class UrgentAlarmTest {
    @Test fun `urgent channel migrates to a silent high importance channel`() {
        assertEquals("computer_urgent", UrgentChannel.REPLACED_ID)
        assertEquals("computer_urgent_v2", UrgentChannel.ID)
        assertNotEquals(UrgentChannel.REPLACED_ID, UrgentChannel.ID)
        assertNull(UrgentChannel.sound)
        assertFalse(UrgentChannel.VIBRATION_ENABLED)
        assertEquals(NotificationManager.IMPORTANCE_HIGH, UrgentChannel.IMPORTANCE)
        assertEquals(Notification.VISIBILITY_PUBLIC, UrgentChannel.LOCKSCREEN_VISIBILITY)
    }

    @Test fun `urgent delivery uses app alarm with and without full screen access`() {
        for (fullScreen in listOf(true, false)) {
            val decision = NotifyPolicy.decide("urgent", NotifyPermissions(true, true, true, true, fullScreen))
            assertTrue(decision.shown)
            assertTrue(NotifyPolicy.usesAppAlarm(decision.level, quiet = false))
            assertFalse(NotifyPolicy.usesAppAlarm(decision.level, quiet = true))
        }
        val downgraded = NotifyPolicy.decide("urgent", NotifyPermissions(false, true, true, true, false))
        assertFalse(NotifyPolicy.usesAppAlarm(downgraded.level, quiet = false))
        assertFalse(NotifyPolicy.usesAppAlarm("normal", quiet = false))
    }

    @Test fun `only alarm stream volume gates audio regardless of ringer mode`() {
        assertFalse(UrgentAlarm.shouldPlaySound(0))
        assertTrue(UrgentAlarm.shouldPlaySound(1))
        assertTrue(UrgentAlarm.shouldPlaySound(7))
    }

    @Test fun `default alarm sound wins without evaluating fallbacks`() {
        assertEquals("alarm", UrgentAlarm.sound(
            alarm = { "alarm" },
            notification = { fail("Notification fallback must not be evaluated") },
            ringtone = { fail("Ringtone fallback must not be evaluated") },
        ))
    }

    @Test fun `missing alarm sound falls back to notification then ringtone`() {
        assertEquals("notification", UrgentAlarm.sound(
            alarm = { null }, notification = { "notification" },
            ringtone = { fail("Ringtone fallback must not be evaluated") },
        ))
        assertEquals("ringtone", UrgentAlarm.sound(
            alarm = { null }, notification = { null }, ringtone = { "ringtone" },
        ))
        assertNull(UrgentAlarm.sound<String>(alarm = { null }, notification = { null }, ringtone = { null }))
    }
}
