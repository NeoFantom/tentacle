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
            val decision = NotifyPolicy.decide("urgent", NotifyPermissions(true, true, true, true, true, fullScreen))
            assertTrue(decision.shown)
            assertTrue(NotifyPolicy.usesAppAlarm(decision.level, quiet = false))
            assertFalse(NotifyPolicy.usesAppAlarm(decision.level, quiet = true))
        }
        val downgraded = NotifyPolicy.decide("urgent", NotifyPermissions(false, true, true, true, true, false))
        assertFalse(NotifyPolicy.usesAppAlarm(downgraded.level, quiet = false))
        assertFalse(NotifyPolicy.usesAppAlarm("normal", quiet = false))
    }

    @Test fun `forced alarm volume rounds up and respects the stream minimum`() {
        assertEquals(15, AlarmVolume.targetIndex(15, 1, 100))
        assertEquals(11, AlarmVolume.targetIndex(15, 1, 70))
        assertEquals(2, AlarmVolume.targetIndex(15, 1, 10))
        assertEquals(3, AlarmVolume.targetIndex(15, 3, 10))
    }

    @Test fun `only alarm stream volume gates audio regardless of ringer mode`() {
        assertFalse(UrgentAlarm.shouldPlaySound("both", 0))
        assertTrue(UrgentAlarm.shouldPlaySound("both", 1))
        assertTrue(UrgentAlarm.shouldPlaySound("sound", 7))
        assertFalse(UrgentAlarm.shouldPlaySound("vibrate", 7))
        assertTrue(UrgentAlarm.shouldVibrate("both"))
        assertTrue(UrgentAlarm.shouldVibrate("vibrate"))
        assertFalse(UrgentAlarm.shouldVibrate("sound"))
        assertFalse(UrgentAlarm.shouldVibrate("both", forcedActive = true))
        assertFalse(UrgentAlarm.mayCancelVibrator(forcedActive = true))
        assertTrue(UrgentAlarm.mayCancelVibrator(forcedActive = false))
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
