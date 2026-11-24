package dev.tentacle.notify

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowInsets
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import dev.tentacle.AppLanguage
import dev.tentacle.R
import dev.tentacle.core.NotifyHistory
import dev.tentacle.core.NotifyHistoryEntry
import dev.tentacle.core.NotifyHistoryView
import java.text.DateFormat
import java.util.Date

/** A reading surface only: viewed history never controls acknowledgement or alarm resources. */
class NotificationHistoryActivity : Activity() {
    private val currentIds = linkedSetOf<String>()
    private var historyExpanded = false
    private var lastView: NotifyHistoryView? = null
    private lateinit var scroll: ScrollView
    private lateinit var body: LinearLayout
    private val refresh = object : Runnable {
        override fun run() {
            refreshHistory()
            scroll.postDelayed(this, 1000)
        }
    }

    override fun attachBaseContext(base: Context) = super.attachBaseContext(AppLanguage.wrap(base))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        currentIds.addAll(savedInstanceState?.getStringArrayList("current_ids").orEmpty())
        intent.getStringExtra(PhoneNotifications.EXTRA_ID)?.let(currentIds::add)
        historyExpanded = savedInstanceState?.getBoolean("history_expanded") ?: false
        body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val padding = dp(20)
            setPadding(padding, padding, padding, padding)
        }
        scroll = ScrollView(this).apply {
            id = View.generateViewId()
            setBackgroundColor(getColor(R.color.bg))
            addView(body)
            setOnApplyWindowInsetsListener { view, insets ->
                if (Build.VERSION.SDK_INT >= 30) {
                    val bars = insets.getInsets(WindowInsets.Type.systemBars())
                    view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                } else {
                    @Suppress("DEPRECATION")
                    view.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                        insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
                }
                insets
            }
        }
        setContentView(scroll)
        refreshHistory()
        val position = savedInstanceState?.getInt("scroll_y") ?: 0
        scroll.post { scroll.scrollTo(0, position) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // A new tap starts a new batch: unviewed alerts plus the tapped one, not the previous visit's.
        currentIds.clear()
        intent.getStringExtra(PhoneNotifications.EXTRA_ID)?.let(currentIds::add)
        historyExpanded = false
        lastView = null
        refreshHistory()
        scroll.post { scroll.smoothScrollTo(0, 0) }
    }

    override fun onResume() {
        super.onResume()
        scroll.post(refresh)
    }

    override fun onPause() {
        scroll.removeCallbacks(refresh)
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putStringArrayList("current_ids", ArrayList(currentIds))
        outState.putBoolean("history_expanded", historyExpanded)
        outState.putInt("scroll_y", scroll.scrollY)
        super.onSaveInstanceState(outState)
    }

    private fun refreshHistory() {
        val history = PhoneNotifications.history(this)
        val view = try {
            history.open(currentIds)
        } catch (_: Exception) {
            if (lastView == null) Toast.makeText(this, R.string.notify_history_save_failed, Toast.LENGTH_LONG).show()
            history.snapshot(currentIds)
        }
        currentIds.clear()
        currentIds.addAll(view.current.map { it.id })
        if (view == lastView) return
        lastView = view
        val position = scroll.scrollY
        body.removeAllViews()
        body.addView(Button(this).apply {
            setText(R.string.notify_inbox_back)
            isAllCaps = false
            setOnClickListener { finish() }
        })
        label(body, getString(R.string.notify_inbox), 26f, true)
        label(body, getString(R.string.notify_history_help, NotifyHistory.MAX_ENTRIES), 14f)
        label(body, getString(R.string.notify_current, view.current.size), 20f, true)
        if (view.current.isEmpty()) label(body, getString(R.string.notify_current_empty), 15f)
        view.current.forEach { entry(body, it) }
        val header = label(body, "", 18f, true).apply {
            minHeight = dp(48)
            gravity = android.view.Gravity.CENTER_VERTICAL
            background = getDrawable(R.drawable.bg_row)
            isFocusable = true
        }
        val older = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        body.addView(older)
        fun showHistory() {
            header.text = getString(R.string.notify_history_disclosure, if (historyExpanded) "▾" else "▸",
                getString(R.string.notify_history, view.history.size))
            header.contentDescription = getString(if (historyExpanded) R.string.section_collapse else R.string.section_expand,
                getString(R.string.notify_history, view.history.size))
            older.visibility = if (historyExpanded) View.VISIBLE else View.GONE
            if (historyExpanded && older.childCount == 0) {
                if (view.history.isEmpty()) label(older, getString(R.string.notify_history_empty), 15f)
                view.history.forEach { entry(older, it) }
            }
        }
        header.setOnClickListener { historyExpanded = !historyExpanded; showHistory() }
        showHistory()
        scroll.post { scroll.scrollTo(0, position) }
    }

    private fun entry(parent: LinearLayout, entry: NotifyHistoryEntry) {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = getDrawable(R.drawable.bg_card)
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        parent.addView(card, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10); bottomMargin = dp(10) })
        label(card, entry.title, 18f, true).setTextIsSelectable(true)
        val levelName = when (entry.level) {
            "forced" -> R.string.notify_inbox_forced
            "urgent" -> R.string.notify_inbox_ring
            else -> R.string.notify_inbox_normal
        }
        label(card, getString(R.string.notify_history_metadata,
            DateFormat.getDateTimeInstance().format(Date(entry.receivedMs)), getString(levelName)), 13f)
        label(card, entry.text, 17f).setTextIsSelectable(true)
    }

    private fun label(parent: LinearLayout, value: String, size: Float, bold: Boolean = false): TextView =
        TextView(this).apply {
            text = value
            textSize = size
            setTextColor(getColor(R.color.text_primary))
            setPadding(0, dp(6), 0, dp(6))
            setLineSpacing(0f, 1.1f)
            if (bold) setTypeface(typeface, Typeface.BOLD)
            parent.addView(this)
        }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
