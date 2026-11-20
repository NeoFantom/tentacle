package dev.tentacle.notify

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import dev.tentacle.MainActivity

/** Only a system-held pending intent can enter this unexported acknowledgement boundary. */
class NotificationOpenActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        intent.getStringExtra(PhoneNotifications.EXTRA_ID)?.let {
            PhoneNotifications.respond(applicationContext, it, "acknowledged")
        }
        startActivity(Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        finish()
    }
}
