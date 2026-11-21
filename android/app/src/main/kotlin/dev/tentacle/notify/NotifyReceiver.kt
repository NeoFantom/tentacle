package dev.tentacle.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Explicit immutable pending intents only; the receiver is not exported. */
class NotifyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra(PhoneNotifications.EXTRA_ID) ?: return
        when (intent.action) {
            PhoneNotifications.ACTION_ACK -> PhoneNotifications.respond(context.applicationContext, id, "acknowledged")
            PhoneNotifications.ACTION_DISMISS -> PhoneNotifications.onNotificationDismissed(context.applicationContext, id)
        }
    }
}
