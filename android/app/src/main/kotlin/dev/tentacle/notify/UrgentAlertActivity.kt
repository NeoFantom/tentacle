package dev.tentacle.notify

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import dev.tentacle.AppLanguage
import dev.tentacle.R

/** A small lock-screen alarm surface with one clear way to stop it. */
class UrgentAlertActivity : Activity() {
    private var alertId: String? = null
    private var stopSubscription: AutoCloseable? = null

    override fun attachBaseContext(base: Context) = super.attachBaseContext(AppLanguage.wrap(base))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        stopSubscription = PhoneNotifications.observeStops { if (it == alertId) finish() }
        showAlert(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        showAlert(intent)
    }

    private fun showAlert(intent: Intent) {
        val id = intent.getStringExtra(PhoneNotifications.EXTRA_ID) ?: run { finish(); return }
        val entry = runCatching { PhoneNotifications.get(this).get(id) }.getOrNull()
        if (entry == null || entry.state != "shown" || entry.delivery.level != "urgent") {
            finish()
            return
        }
        // Change the ID first: stopping the previous alert must not finish this new one.
        val previous = alertId
        alertId = id
        if (previous != null && previous != id) PhoneNotifications.stopAlarm(this, previous, quiet = true)
        val padding = (24 * resources.displayMetrics.density).toInt()
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(padding, padding, padding, padding)
        }
        // Same visual language as the app's "needs attention" surfaces: warm background, bold text,
        // one large primary button.
        body.setBackgroundColor(getColor(R.color.warn_container))
        body.addView(TextView(this).apply {
            text = entry.message.title
            textSize = 30f
            setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL))
            setTextColor(getColor(R.color.on_warn_container))
            setPadding(0, padding, 0, padding / 2)
        })
        body.addView(TextView(this).apply {
            text = entry.message.text
            textSize = 22f
            setLineSpacing(0f, 1.15f)
            setTextColor(getColor(R.color.on_warn_container))
            setPadding(0, 0, 0, padding * 2)
        })
        body.addView(Button(this).apply {
            setText(R.string.notify_acknowledge)
            textSize = 22f
            isAllCaps = false
            stateListAnimator = null
            background = getDrawable(R.drawable.bg_button_primary)
            setTextColor(getColor(R.color.on_accent))
            minHeight = padding * 3
            setOnClickListener {
                PhoneNotifications.respond(this@UrgentAlertActivity, id, "acknowledged")
                finish()
            }
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        setContentView(ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(getColor(R.color.warn_container))
            addView(body)
        })
        PhoneNotifications.startAlarm(this, id)
    }

    override fun onDestroy() {
        stopSubscription?.close()
        stopSubscription = null
        alertId?.let { PhoneNotifications.stopAlarm(applicationContext, it, quiet = true) }
        super.onDestroy()
    }
}
