package dev.tentacle.notify

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.WindowInsets
import android.view.WindowManager
import android.window.OnBackInvokedDispatcher
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import dev.tentacle.AppLanguage
import dev.tentacle.R
import dev.tentacle.core.AlertQuestionPicker

/** Lock-screen challenge for a forced alert; alarm ownership remains in PhoneNotifications. */
class ForcedAlertActivity : Activity() {
    private var alertId: String? = null
    private var stopSubscription: AutoCloseable? = null
    private lateinit var picker: AlertQuestionPicker
    private lateinit var prompt: TextView
    private lateinit var input: EditText
    private lateinit var feedback: TextView

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
        if (Build.VERSION.SDK_INT >= 33) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT) { }
        }
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
        if (entry == null || entry.delivery.level != "forced" || entry.state !in setOf("shown", "timeout")) {
            finish()
            return
        }
        alertId = id
        val configuredTag = AppLanguage.currentTag(this)
        val activeLanguage = configuredTag.takeIf { it.isNotEmpty() }
            ?: resources.configuration.locales[0].language
        val lang = if (activeLanguage.startsWith("zh", ignoreCase = true)) "zh" else "en"
        picker = AlertQuestionPicker(PhoneNotifications.questions(this).questionsFor(lang))
        val padding = (24 * resources.displayMetrics.density).toInt()
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            // Top-aligned so the answer field and Submit stay above the on-screen keyboard.
            gravity = Gravity.TOP
            setPadding(padding, padding * 2, padding, padding)
            setBackgroundColor(getColor(R.color.warn_container))
        }
        fun label(value: CharSequence, size: Float, bottom: Int = padding): TextView = TextView(this).apply {
            text = value
            textSize = size
            setLineSpacing(0f, 1.15f)
            setTextColor(getColor(R.color.on_warn_container))
            setPadding(0, 0, 0, bottom)
        }
        body.addView(label(entry.message.title, 30f, padding / 2).apply {
            setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL))
        })
        body.addView(label(entry.message.text, 22f, padding))
        body.addView(label(getString(R.string.notify_forced_instruction), 18f, padding / 2))
        prompt = label(picker.current().prompt, 22f, padding / 2).also(body::addView)
        input = EditText(this).apply {
            isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_DONE
            setTextColor(getColor(R.color.on_warn_container))
            setOnEditorActionListener { _, action, _ ->
                if (action == EditorInfo.IME_ACTION_DONE) { submit(); true } else false
            }
        }.also { body.addView(it, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT)) }
        feedback = label("", 16f, padding / 2).also(body::addView)
        body.addView(Button(this).apply {
            setText(R.string.notify_forced_submit)
            textSize = 20f
            isAllCaps = false
            stateListAnimator = null
            background = getDrawable(R.drawable.bg_button_primary)
            setTextColor(getColor(R.color.on_accent))
            minHeight = padding * 3
            setOnClickListener { submit() }
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        val scroller = ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(getColor(R.color.warn_container))
            addView(body)
        }
        // Edge-to-edge windows (target SDK 35) are not resized for the keyboard; pad by its height instead.
        scroller.setOnApplyWindowInsetsListener { view, insets ->
            val bottom = if (Build.VERSION.SDK_INT >= 30) {
                insets.getInsets(WindowInsets.Type.ime() or WindowInsets.Type.systemBars()).bottom
            } else {
                @Suppress("DEPRECATION") insets.systemWindowInsetBottom
            }
            view.setPadding(0, 0, 0, bottom)
            insets
        }
        setContentView(scroller)
        input.requestFocus()
    }

    private fun submit() {
        val id = alertId ?: return
        if (picker.current().matches(input.text.toString())) {
            PhoneNotifications.answerForced(applicationContext, id)
            finish()
            return
        }
        val result = picker.submitWrong()
        feedback.setText(if (result.changed) R.string.notify_forced_new_question_note else R.string.notify_forced_wrong)
        if (result.changed) prompt.text = result.question.prompt
        input.text.clear()
        input.requestFocus()
    }

    @Deprecated("Forced alerts deliberately consume Back")
    override fun onBackPressed() = Unit

    override fun dispatchKeyEvent(event: KeyEvent): Boolean = when (event.keyCode) {
        KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_MUTE -> true
        else -> super.dispatchKeyEvent(event)
    }

    override fun onDestroy() {
        stopSubscription?.close()
        stopSubscription = null
        // Home, screen-off and recreation must leave the process-owned forced alarm running.
        super.onDestroy()
    }
}
