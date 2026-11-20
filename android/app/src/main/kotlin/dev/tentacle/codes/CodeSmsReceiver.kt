package dev.tentacle.codes

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Telephony
import dev.tentacle.Settings

/** Primary capture source for incoming SMS on every supported Android version. */
class CodeSmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val settings = Settings(context)
        if (!settings.codesEnabled ||
            context.checkSelfPermission(Manifest.permission.RECEIVE_SMS) != PackageManager.PERMISSION_GRANTED) return
        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        // Framework decoding handles both 3GPP/3GPP2 PDUs, in multipart delivery order.
        messages.groupBy { it.originatingAddress.orEmpty() }.forEach { (sender, parts) ->
            CodeStores.capture(context, parts.joinToString("") { it.messageBody.orEmpty() }, sender, "sms")
        }
    }
}
