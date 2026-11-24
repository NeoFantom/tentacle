package dev.tentacle.notify

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/** Only a system-held pending intent can enter this unexported acknowledgement boundary. */
class NotificationOpenActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        intent.getStringExtra(PhoneNotifications.EXTRA_ID)?.let {
            PhoneNotifications.respond(applicationContext, it, "acknowledged")
        }
        startActivity(Intent(this, NotificationHistoryActivity::class.java)
            .putExtra(PhoneNotifications.EXTRA_ID, intent.getStringExtra(PhoneNotifications.EXTRA_ID))
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        finish()
    }
}
