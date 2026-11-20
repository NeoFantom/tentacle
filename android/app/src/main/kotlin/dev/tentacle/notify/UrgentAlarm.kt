package dev.tentacle.notify

import android.app.Notification
import android.app.NotificationManager
import android.net.Uri

/** A new channel ID is required because Android preserves an existing channel's sound settings. */
internal object UrgentChannel {
    const val ID = "computer_urgent_v2"
    const val REPLACED_ID = "computer_urgent"
    const val IMPORTANCE = NotificationManager.IMPORTANCE_HIGH
    const val LOCKSCREEN_VISIBILITY = Notification.VISIBILITY_PUBLIC
    val sound: Uri? = null
    const val VIBRATION_ENABLED = false
}

internal object UrgentAlarm {
    // Ringer mode is deliberately irrelevant: only the alarm stream volume controls alarm audio.
    fun shouldPlaySound(alarmVolume: Int): Boolean = alarmVolume > 0

    fun <T> sound(alarm: () -> T?, notification: () -> T?, ringtone: () -> T?): T? =
        alarm() ?: notification() ?: ringtone()
}
