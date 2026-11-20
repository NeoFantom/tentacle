package dev.tentacle.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Explicit immutable pending intents only; the receiver is not exported. */
class NotifyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra(PhoneNotifications.EXTRA_ID) ?: return
        val state = when (intent.action) {
            PhoneNotifications.ACTION_ACK -> "acknowledged"
            PhoneNotifications.ACTION_DISMISS -> "dismissed"
            else -> return
        }
        PhoneNotifications.respond(context.applicationContext, id, state)
    }
}
