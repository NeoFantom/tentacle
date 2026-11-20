package dev.tentacle

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.NotificationManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings as SystemSettings
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import dev.tentacle.core.IpAddresses
import dev.tentacle.core.AppRequest
import dev.tentacle.core.RequestStore
import dev.tentacle.core.RequestText
import dev.tentacle.core.ServerInfo
import dev.tentacle.core.ServerInfoParser
import dev.tentacle.tailnet.TailnetLogic
import dev.tentacle.tailnet.TailnetStatus
import dev.tentacle.tunnel.TunnelKeys
import dev.tentacle.tunnel.TunnelLogic
import dev.tentacle.tunnel.TunnelStatus
import dev.tentacle.update.AppUpdater
import dev.tentacle.update.ReleaseAsset
import dev.tentacle.update.ReleaseInfo
import dev.tentacle.update.Releases
import dev.tentacle.update.UpdateState
import dev.tentacle.update.UpdatePrompt
import java.net.InetAddress

/**
 * One screen, ordered by importance: update banner (only when an update is
 * ready), status card with the single Start/Stop action and the state of each
 * way in, "needs attention" fixes (only while something is missing), pairing,
 * the optional ways to connect, collapsed settings, and a small update row.
 */
class MainActivity : Activity() {
    private enum class Tone { GOOD, WARN, BAD, NEUTRAL, BUSY }
    private enum class ButtonKind { PRIMARY, TONAL, OUTLINE }

    private lateinit var settings: Settings
    private lateinit var scroll: ScrollView
    private lateinit var content: LinearLayout

    private lateinit var updateBanner: LinearLayout
    private lateinit var updateBannerText: TextView
    private lateinit var installUpdate: Button
    private lateinit var allowUpdates: Button

    private lateinit var hero: LinearLayout
    private lateinit var heroBadge: TextView
    private lateinit var heroTitle: TextView
    private lateinit var heroText: TextView
    private lateinit var startStop: Button
    private lateinit var ways: LinearLayout

    private lateinit var attentionCard: LinearLayout
    private lateinit var attentionItems: LinearLayout

    private lateinit var connectCard: LinearLayout
    private lateinit var connectMore: Expander
    private lateinit var pairs: LinearLayout
    private lateinit var tokenText: TextView
    private lateinit var showToken: Button

    private lateinit var requestStore: RequestStore
    private lateinit var requestsCard: LinearLayout
    private lateinit var requestText: EditText
    private lateinit var requestCounter: TextView
    private lateinit var requestSend: Button
    private lateinit var requestServerNote: TextView
    private lateinit var requestBug: Button
    private lateinit var computerStatus: TextView
    private lateinit var requestHistory: LinearLayout
    private lateinit var requestsMore: Expander

    private lateinit var tailnetCard: LinearLayout
    private lateinit var tailnetSwitch: Switch
    private lateinit var tailnetStatusText: TextView
    private lateinit var tailnetHint: TextView
    private lateinit var tailnetLogin: Button
    private lateinit var tailnetLogout: Button
    private lateinit var tailnetKey: EditText
    private lateinit var tailnetAdvanced: Expander

    private lateinit var serverCard: LinearLayout
    private lateinit var tunnelSwitch: Switch
    private lateinit var tunnelStatusText: TextView
    private lateinit var serverTarget: TextView
    private lateinit var cloudflareWarning: TextView
    private lateinit var serverSetup: Expander
    private lateinit var serverKeys: Expander
    private lateinit var pasteBox: EditText
    private lateinit var recognizedText: TextView
    private lateinit var hostField: EditText
    private lateinit var portField: EditText
    private lateinit var userField: EditText
    private lateinit var remoteField: EditText
    private lateinit var publicUrlField: EditText
    private lateinit var keyText: TextView
    private lateinit var hostFingerprint: TextView
    private lateinit var trustNewKey: Button

    private lateinit var settingsCard: LinearLayout
    private lateinit var settingsBody: Expander
    private lateinit var portText: TextView
    private lateinit var boot: Switch
    private lateinit var codesSwitch: Switch
    private lateinit var codesNotificationsSwitch: Switch
    private lateinit var codesStatus: TextView
    private lateinit var codesAccessHelp: LinearLayout
    private lateinit var codesSmsHelp: LinearLayout
    private var codesXiaomiHelp: TextView? = null
    private lateinit var updateStatusText: TextView
    private lateinit var checkUpdates: Button
    private lateinit var updateSourceField: EditText

    private var statusSubscription: AutoCloseable? = null
    private var tunnelSubscription: AutoCloseable? = null
    private var tailnetSubscription: AutoCloseable? = null
    private var updateSubscription: AutoCloseable? = null
    private var requestSubscription: AutoCloseable? = null
    private var openLoginWhenReady = false
    private var installRequested = false
    private var activityVisible = false
    private var updatePromptDialog: AlertDialog? = null
    private var debugUpdateSourceChanged = false
    private var tokenVisible = false
    private var updating = false
    private var lastTunnelError: TunnelStatus.Error? = null
    private var requestRefreshActive = false
    private val requestRefresh = object : Runnable {
        override fun run() {
            if (!requestRefreshActive) return
            refreshRequests()
            refreshCodes()
            scroll.postDelayed(this, REQUEST_REFRESH_MS)
        }
    }
    @Volatile private var cloudflareCheck = 0

    override fun attachBaseContext(base: Context) = super.attachBaseContext(AppLanguage.wrap(base))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = Settings(this)
        requestStore = RequestStores.get(this)
        buildScreen()
        savedInstanceState?.getString(REQUEST_DRAFT_STATE)?.let { requestText.setText(it) }
        handleDebugIntent(intent)
        AppUpdater.initialize(this)
        handleDebugUpdate(intent)
        handleUpdateIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleDebugIntent(intent)
        handleDebugUpdate(intent)
        handleUpdateIntent(intent)
    }

    private fun handleDebugIntent(intent: Intent) {
        if (!BuildConfig.DEBUG) return
        if (intent.hasExtra("codes_enabled")) {
            settings.codesEnabled = intent.getBooleanExtra("codes_enabled", false)
            intent.removeExtra("codes_enabled")
        }
        intent.getStringExtra("inject_code_text")?.let { text ->
            dev.tentacle.codes.CodeStores.capture(this, text,
                intent.getStringExtra("inject_code_sender").orEmpty(), "notification")
            intent.removeExtra("inject_code_text")
            intent.removeExtra("inject_code_sender")
        }
        // Test hooks for emulator end-to-end runs and screenshots; release builds ignore them.
        if (intent.getBooleanExtra("reset_update_prompt", false)) {
            settings.dismissedUpdateVersion = ""
            shownUpdatePromptVersions.clear()
            intent.removeExtra("reset_update_prompt")
        }
        intent.getStringExtra("update_source")?.let {
            settings.updateSource = it.trim()
            updateSourceField.setText(settings.updateSource)
            debugUpdateSourceChanged = true
            intent.removeExtra("update_source")
        }
        var tailnetChanged = false
        intent.getStringExtra("tailnet_control_url")?.let { settings.tailnetControlUrl = it.trim(); tailnetChanged = true }
        intent.getStringExtra("tailnet_auth_key")?.let { settings.tailnetAuthKey = it.trim(); tailnetChanged = true }
        intent.getStringExtra("tailnet_hostname")?.let {
            settings.tailnetHostname = TailnetLogic.sanitizeHostname(it); tailnetChanged = true
        }
        if (intent.hasExtra("tailnet_enabled")) {
            settings.tailnetEnabled = intent.getBooleanExtra("tailnet_enabled", false); tailnetChanged = true
        }
        var tunnelChanged = false
        intent.getStringExtra("tunnel_host")?.let { settings.tunnelHost = it.trim(); tunnelChanged = true }
        intent.getStringExtra("tunnel_user")?.let { settings.tunnelUser = it.trim(); tunnelChanged = true }
        intent.getStringExtra("public_url")?.let { settings.publicUrl = it.trim(); tunnelChanged = true }
        if (intent.hasExtra("tunnel_port")) { settings.tunnelPort = intent.getIntExtra("tunnel_port", 22); tunnelChanged = true }
        if (intent.hasExtra("tunnel_remote_port")) {
            settings.tunnelRemotePort = intent.getIntExtra("tunnel_remote_port", 8765); tunnelChanged = true
        }
        if (intent.hasExtra("tunnel_enabled")) {
            settings.tunnelEnabled = intent.getBooleanExtra("tunnel_enabled", false); tunnelChanged = true
        }
        val restart = tailnetChanged || tunnelChanged
        if (intent.getBooleanExtra("autostart", false)) ServerService.start(this, restart = restart)
        else if (restart && ServerStatus.value.running) ServerService.start(this, restart = true)
        // Delayed: stopping a service before it reached startForeground() crashes the app.
        if (intent.getBooleanExtra("stop", false)) scroll.postDelayed({ ServerService.stop(this) }, 1500)
        if (restart) loadServerFields()
        updating = true
        tailnetSwitch.isChecked = settings.tailnetEnabled
        tunnelSwitch.isChecked = settings.tunnelEnabled
        updating = false
        intent.getStringExtra("paste_text")?.let {
            serverSetup.open = true
            pasteBox.setText(it)
            recognize()
        }
        intent.getStringExtra("request_text")?.let { requestText.setText(it) }
        intent.getStringExtra("expand")?.split(',')?.map(String::trim)?.forEach { name ->
            when (name) {
                "more" -> connectMore.open = true
                "tailnet" -> tailnetAdvanced.open = true
                "server" -> serverSetup.open = true
                "keys" -> serverKeys.open = true
                "settings" -> settingsBody.open = true
                "requests_more" -> requestsMore.open = true
            }
        }
        intent.getStringExtra("scroll_to")?.let { name ->
            val target = when (name) {
                "connect" -> connectCard
                "requests" -> requestsCard
                "tailnet" -> tailnetCard
                "server" -> serverCard
                "settings" -> settingsCard
                "bottom", "top" -> null
                else -> hero
            }
            scroll.postDelayed({
                when {
                    target != null -> scroll.scrollTo(0, target.top - dp(8))
                    name == "top" -> scroll.scrollTo(0, 0)
                    else -> scroll.fullScroll(View.FOCUS_DOWN)
                }
            }, 400)
        }
        if (intent.getBooleanExtra("request_send", false)) requestSend.performClick()
        if (intent.getBooleanExtra("request_bug", false)) requestBug.performClick()
        if (intent.getBooleanExtra("crash_test", false)) {
            intent.removeExtra("crash_test")
            scroll.post { throw RuntimeException("Crash test") }
        }
        refresh()
    }

    /** Debug only: exercise update checks or inject availability without a release server. */
    private fun handleDebugUpdate(intent: Intent) {
        if (!BuildConfig.DEBUG) return
        if (debugUpdateSourceChanged) {
            debugUpdateSourceChanged = false
            AppUpdater.check(this, force = true)
        }
        val version = intent.getStringExtra("fake_update") ?: return
        AppUpdater.publish(UpdateState.Available(ReleaseInfo(version,
            ReleaseAsset("tentacle-$version.apk", "https://example.com/tentacle-$version.apk", 1), null)))
    }

    private fun handleUpdateIntent(intent: Intent) {
        if (!intent.getBooleanExtra(AppUpdater.EXTRA_INSTALL_UPDATE, false)) return
        intent.removeExtra(AppUpdater.EXTRA_INSTALL_UPDATE)
        installRequested = true
        if (AppUpdater.available != null) {
            installRequested = false
            AppUpdater.downloadAndInstall(this)
        } else AppUpdater.check(this, manual = true)
    }

    override fun onResume() {
        super.onResume()
        activityVisible = true
        ServerService.refreshNotificationChannel(this)
        AppUpdater.refreshNotificationChannel(this)
        val returningFromInstallPermission = AppUpdater.state is UpdateState.PermissionRequired
        AppUpdater.refreshInstallPermission(this)
        if (!returningFromInstallPermission) AppUpdater.check(this)
        statusSubscription = ServerStatus.observe { runOnUiThread { refresh(it) } }
        tunnelSubscription = TunnelStatus.observe { runOnUiThread { refresh() } }
        tailnetSubscription = TailnetStatus.observe { state -> runOnUiThread {
            refresh()
            if (openLoginWhenReady && state.loginUrl != null) {
                openLoginWhenReady = false
                openLoginUrl(state.loginUrl)
            }
        } }
        updateSubscription = AppUpdater.observe { state -> runOnUiThread {
            if (!activityVisible) return@runOnUiThread
            refresh()
            if (installRequested && state is UpdateState.Available) {
                installRequested = false
                AppUpdater.downloadAndInstall(this)
            } else if (state is UpdateState.Error || state is UpdateState.Disabled || state is UpdateState.UpToDate) {
                installRequested = false
            }
            maybeShowUpdatePrompt()
        } }
        requestSubscription = requestStore.observe { runOnUiThread { refreshRequests() } }
        requestRefreshActive = true
        scroll.removeCallbacks(requestRefresh)
        scroll.post(requestRefresh)
        refreshRequests()
    }

    override fun onPause() {
        activityVisible = false
        updatePromptDialog?.dismiss()
        updatePromptDialog = null
        tunnelSubscription?.close()
        tunnelSubscription = null
        tailnetSubscription?.close()
        tailnetSubscription = null
        updateSubscription?.close()
        updateSubscription = null
        statusSubscription?.close()
        statusSubscription = null
        requestSubscription?.close()
        requestSubscription = null
        requestRefreshActive = false
        scroll.removeCallbacks(requestRefresh)
        tokenVisible = false
        refresh()
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        if (::requestText.isInitialized) outState.putString(REQUEST_DRAFT_STATE, requestText.text.toString())
        super.onSaveInstanceState(outState)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == SMS_PERMISSION_REQUEST) {
            if (settings.codesEnabled && !smsCodeAccess()) explainCodeSmsAccess()
        }
        refresh()
    }

    // ---------------------------------------------------------------- screen

    private fun buildScreen() {
        scroll = ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = true
        }
        content = column().apply { setPadding(dp(16), dp(8), dp(16), dp(24)) }
        scroll.addView(content)
        val frame = FrameLayout(this).apply {
            clipToPadding = true
            setBackgroundColor(color(R.color.bg))
            addView(scroll, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        // Keep the scrolling viewport inside the bars, including while its content is moving.
        frame.setOnApplyWindowInsetsListener { view, insets ->
            view.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            insets
        }
        setContentView(frame)

        val header = column().also { content.addView(it) }
        header.setPadding(dp(4), dp(8), dp(4), dp(14))
        text(header, getString(R.string.app_name), 26f, bold = true)
        text(header, getString(R.string.tagline), 14f, secondary = true).setPadding(0, 0, 0, 0)

        updateBannerSection()
        heroSection()
        attentionSection()
        connectSection()
        requestsSection()
        sectionLabel(getString(R.string.ways_title))
        text(content, getString(R.string.ways_lan_note), 14f, secondary = true).setPadding(dp(4), 0, dp(4), dp(10))
        tailnetSection()
        serverSection()
        settingsSection()
        footerSection()
    }

    private fun updateBannerSection() {
        updateBanner = card().apply { background = rounded(color(R.color.accent_container), 20) }
        updateBannerText = text(updateBanner, "", 16f, bold = true).apply { setTextColor(color(R.color.on_accent_container)) }
        installUpdate = button(updateBanner, getString(R.string.update_now), ButtonKind.PRIMARY, fill = true) {
            AppUpdater.downloadAndInstall(this)
        }
        allowUpdates = button(updateBanner, getString(R.string.unknown_apps), ButtonKind.OUTLINE, fill = true) {
            AppUpdater.openUnknownSources(this)
        }
    }

    private fun heroSection() {
        hero = card()
        val top = row(hero).apply { gravity = Gravity.CENTER_VERTICAL }
        heroBadge = badge(top, "", Tone.NEUTRAL, 36)
        heroTitle = text(top, "", 21f, bold = true).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(12) }
        }
        heroText = text(hero, "", 15f).apply { setPadding(0, dp(8), 0, dp(12)) }
        startStop = button(hero, getString(R.string.start), ButtonKind.PRIMARY, fill = true) {
            if (ServerStatus.value.running) ServerService.stop(this) else ServerService.start(this)
            refresh()
        }
        ways = column().also {
            hero.addView(it, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(10) })
        }
    }

    private fun attentionSection() {
        attentionCard = card().apply { background = rounded(color(R.color.warn_container), 20) }
        text(attentionCard, getString(R.string.attention_title), 17f, bold = true).setTextColor(color(R.color.on_warn_container))
        attentionItems = column().also { attentionCard.addView(it) }
    }

    private fun connectSection() {
        connectCard = card()
        cardTitle(connectCard, getString(R.string.connect_title))
        text(connectCard, getString(R.string.connect_step_copy))
        text(connectCard, getString(R.string.connect_step_run))
        code(connectCard, "tentacle pair '<link>'")
        button(connectCard, getString(R.string.copy_all_links), ButtonKind.PRIMARY, fill = true) { copyAllLinks() }
            .also { (it.layoutParams as LinearLayout.LayoutParams).topMargin = dp(10) }
        connectMore = expander(connectCard, getString(R.string.more))
        val more = connectMore.body
        text(more, getString(R.string.single_links), 14f, bold = true)
        pairs = column().also { more.addView(it) }
        text(more, getString(R.string.token_label), 14f, bold = true).setPadding(0, dp(12), 0, dp(2))
        tokenText = code(more, "")
        val tokenActions = buttonRow(more)
        showToken = button(tokenActions, getString(R.string.show), ButtonKind.OUTLINE, fill = true) { tokenVisible = !tokenVisible; refresh() }
        button(tokenActions, getString(R.string.copy_token), ButtonKind.OUTLINE, fill = true) { copy(getString(R.string.token_label), settings.token) }
        button(more, getString(R.string.regenerate), ButtonKind.OUTLINE, fill = true) {
            AlertDialog.Builder(this).setTitle(getString(R.string.regenerate_title))
                .setMessage(getString(R.string.regenerate_message))
                .setNegativeButton(getString(R.string.cancel), null)
                .setPositiveButton(getString(R.string.regenerate)) { _, _ -> settings.regenerateToken(); refresh() }.show()
        }
        text(more, getString(R.string.privacy), 13f, secondary = true).setPadding(0, dp(10), 0, 0)
    }

    private fun copyAllLinks() {
        val tailnet = TailnetStatus.value.takeIf { it.mode == TailnetStatus.Mode.RUNNING }?.ip
        val addresses = DeviceEnvironment.addresses(this)
        if (tailnet == null && addresses.isEmpty() && publicPairingUrl() == null) {
            Toast.makeText(this, R.string.no_network, Toast.LENGTH_LONG).show()
        } else copy(getString(R.string.copy_all_links), Pairing.uriAll(tailnet?.let { "http://$it:${settings.port}" },
            addresses, settings.port, publicPairingUrl(), settings.token, DeviceEnvironment.deviceName))
    }

    private fun requestsSection() {
        requestsCard = card()
        cardTitle(requestsCard, getString(R.string.requests_title))
        text(requestsCard, getString(R.string.requests_scope), 14f, secondary = true)
        requestText = EditText(this).apply {
            hint = getString(R.string.requests_hint)
            contentDescription = getString(R.string.requests_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            minLines = 3
            maxLines = 8
            gravity = Gravity.TOP or Gravity.START
            textSize = 15f
            styleField(this)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(text: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(text: CharSequence?, start: Int, before: Int, count: Int) = updateRequestInput()
                override fun afterTextChanged(text: Editable?) = Unit
            })
            requestsCard.addView(this, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(10) })
        }
        requestCounter = text(requestsCard, "", 13f, secondary = true).apply { gravity = Gravity.END }
        val actions = buttonRow(requestsCard)
        requestSend = button(actions, getString(R.string.requests_send), ButtonKind.PRIMARY, fill = true) {
            createRequest("feature")
        }
        requestBug = button(actions, getString(R.string.requests_bug), ButtonKind.OUTLINE, fill = true) {
            createRequest("bug")
        }
        computerStatus = text(requestsCard, "", 14f).apply { setPadding(0, dp(12), 0, dp(4)) }
        requestServerNote = text(requestsCard, getString(R.string.requests_server_note), 13f, secondary = true)
        requestHistory = column().also { requestsCard.addView(it) }
        requestsMore = expander(requestsCard, getString(R.string.requests_more))
        updateRequestInput()
        refreshRequests()
    }

    private fun updateRequestInput() {
        if (!::requestText.isInitialized || !::requestCounter.isInitialized) return
        val value = requestText.text.toString()
        val units = RequestText.units(value)
        val withinLimits = units <= RequestText.MAX_UNITS && value.length <= RequestText.MAX_CHARS
        requestCounter.text = getString(R.string.requests_counter, units, RequestText.MAX_UNITS)
        requestCounter.setTextColor(color(if (withinLimits) R.color.text_secondary else R.color.bad))
        if (::requestSend.isInitialized) {
            requestSend.isEnabled = value.isNotBlank() && requestTextValid(value, false)
            requestSend.alpha = if (requestSend.isEnabled) 1f else 0.5f
        }
        if (::requestBug.isInitialized) {
            requestBug.isEnabled = requestTextValid(value, true)
            requestBug.alpha = if (requestBug.isEnabled) 1f else 0.5f
        }
    }

    private fun requestTextValid(value: String, allowEmpty: Boolean): Boolean = try {
        RequestText.validate(value, allowEmpty)
        true
    } catch (_: Exception) {
        false
    }

    private fun createRequest(kind: String) {
        val value = requestText.text.toString()
        val allowEmpty = kind == "bug"
        if (!requestTextValid(value, allowEmpty)) {
            Toast.makeText(this, R.string.requests_invalid, Toast.LENGTH_LONG).show()
            return
        }
        try {
            val log = if (kind == "bug") AppLog.snapshot() else null
            requestStore.create(kind, value, log)
            requestText.text.clear()
            Toast.makeText(this, R.string.requests_sent, Toast.LENGTH_SHORT).show()
        } catch (_: Exception) {
            Toast.makeText(this, R.string.requests_save_failed, Toast.LENGTH_LONG).show()
        }
    }

    private fun refreshRequests() {
        if (!::requestHistory.isInitialized) return
        val now = System.currentTimeMillis()
        val lastPoll = requestStore.lastPolledMs
        val (pollText, pollTone) = when {
            lastPoll <= 0L -> getString(R.string.requests_never_checked) to Tone.WARN
            now - lastPoll < 2 * 60_000L -> getString(R.string.requests_checked, relativeAge(now - lastPoll)) to Tone.GOOD
            else -> getString(R.string.requests_last_checked, relativeAge(now - lastPoll)) to Tone.WARN
        }
        computerStatus.text = pollText
        computerStatus.setTextColor(statusColor(pollTone))

        val requests = requestStore.list()
        requestHistory.removeAllViews()
        requests.take(5).forEach { requestRow(requestHistory, it, now) }
        if (requests.isEmpty()) {
            text(requestHistory, getString(R.string.requests_empty), 13f, secondary = true)
        }
        requestsMore.body.removeAllViews()
        requests.drop(5).forEach { requestRow(requestsMore.body, it, now) }
        val hasMore = requests.size > 5
        requestsMore.header.visibility = if (hasMore) View.VISIBLE else View.GONE
        requestsMore.body.visibility = if (hasMore && requestsMore.open) View.VISIBLE else View.GONE
    }

    private fun requestRow(parent: LinearLayout, request: AppRequest, now: Long) {
        val box = column().apply {
            background = rounded(color(R.color.bg), 14)
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        parent.addView(box, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(8) })
        val header = row(box).apply { gravity = Gravity.CENTER_VERTICAL }
        text(header, getString(if (request.kind == "bug") R.string.requests_kind_bug else R.string.requests_kind_change),
            14f, bold = true).apply { layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) }
        text(header, getString(R.string.requests_time_ago, relativeAge(now - request.createdMs)), 12f, secondary = true)
        if (request.text.isNotEmpty()) {
            text(box, request.text, 14f).apply {
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
            }
        }
        val (status, tone) = when (request.status) {
            "working" -> R.string.requests_status_working to Tone.BUSY
            "done" -> R.string.requests_status_done to Tone.GOOD
            "failed" -> R.string.requests_status_failed to Tone.BAD
            else -> R.string.requests_status_waiting to Tone.WARN
        }
        text(box, getString(status), 14f, bold = true).setTextColor(statusColor(tone))
        if (request.message.isNotEmpty()) text(box, request.message, 13f, secondary = true)
    }

    private fun relativeAge(ageMs: Long): String {
        val seconds = ageMs.coerceAtLeast(0L) / 1000L
        return when {
            seconds < 60 -> getString(R.string.requests_age_seconds, seconds)
            seconds < 60 * 60 -> getString(R.string.requests_age_minutes, seconds / 60)
            seconds < 24 * 60 * 60 -> getString(R.string.requests_age_hours, seconds / (60 * 60))
            else -> getString(R.string.requests_age_days, seconds / (24 * 60 * 60))
        }
    }

    private fun tailnetSection() {
        tailnetCard = card()
        tailnetSwitch = cardSwitch(tailnetCard, getString(R.string.tailnet_title), settings.tailnetEnabled) {
            settings.tailnetEnabled = it
            if (ServerStatus.value.running) ServerService.start(this, restart = true)
            refresh()
        }
        tailnetStatusText = text(tailnetCard, "", 15f, bold = true)
        text(tailnetCard, getString(R.string.tailnet_summary), 14f, secondary = true)
        tailnetHint = text(tailnetCard, getString(R.string.tailnet_login_hint), 14f)
        tailnetLogin = button(tailnetCard, getString(R.string.tailnet_login), ButtonKind.PRIMARY, fill = true) {
            val url = TailnetStatus.value.loginUrl
            if (url != null) openLoginUrl(url)
            else {
                openLoginWhenReady = true
                ServerService.tailnetLogin(this)
            }
        }
        tailnetAdvanced = expander(tailnetCard, getString(R.string.tailnet_advanced))
        val advanced = tailnetAdvanced.body
        val controlUrl = field(advanced, R.string.tailnet_control_url, settings.tailnetControlUrl, getString(R.string.tailnet_control_url_hint))
        tailnetKey = field(advanced, R.string.tailnet_auth_key, "", getString(R.string.tailnet_auth_key_hint)).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val hostname = field(advanced, R.string.tailnet_hostname, settings.tailnetHostname, TailnetLogic.defaultHostname(Build.MODEL ?: ""))
        button(advanced, getString(R.string.tailnet_save), ButtonKind.TONAL, fill = true) {
            val url = controlUrl.text.toString().trim().trimEnd('/')
            val name = TailnetLogic.sanitizeHostname(hostname.text.toString())
            if (!TailnetLogic.validControlUrl(url) || name.isEmpty()) {
                Toast.makeText(this, R.string.tailnet_invalid, Toast.LENGTH_LONG).show()
            } else {
                settings.tailnetControlUrl = url
                settings.tailnetHostname = name
                hostname.setText(name)
                val key = tailnetKey.text.toString().trim()
                if (key.isNotEmpty()) settings.tailnetAuthKey = key
                tailnetKey.setText("")
                if (ServerStatus.value.running && settings.tailnetEnabled) ServerService.start(this, restart = true)
                Toast.makeText(this, R.string.connection_saved, Toast.LENGTH_SHORT).show()
                refresh()
            }
        }.also { (it.layoutParams as LinearLayout.LayoutParams).topMargin = dp(12) }
        tailnetLogout = button(advanced, getString(R.string.tailnet_logout), ButtonKind.OUTLINE, fill = true) {
            AlertDialog.Builder(this).setTitle(getString(R.string.tailnet_logout))
                .setMessage(getString(R.string.tailnet_logout_message))
                .setNegativeButton(getString(R.string.cancel), null)
                .setPositiveButton(getString(R.string.tailnet_logout)) { _, _ -> ServerService.tailnetLogout(this) }
                .show()
        }
        text(advanced, getString(R.string.tailnet_proxy_help), 13f, secondary = true).setPadding(0, dp(10), 0, 0)
    }

    private fun serverSection() {
        serverCard = card()
        tunnelSwitch = cardSwitch(serverCard, getString(R.string.server_title), settings.tunnelEnabled) {
            settings.tunnelEnabled = it
            if (it && settings.tunnelHost.isBlank()) serverSetup.open = true
            if (ServerStatus.value.running) ServerService.start(this, restart = true)
            refresh()
        }
        tunnelStatusText = text(serverCard, "", 15f, bold = true)
        text(serverCard, getString(R.string.server_summary), 14f, secondary = true)
        serverTarget = code(serverCard, "")
        cloudflareWarning = text(serverCard, "", 14f).apply {
            background = rounded(color(R.color.bad_container), 12)
            setTextColor(color(R.color.on_bad_container))
            setPadding(dp(12), dp(10), dp(12), dp(10))
            visibility = View.GONE
            (layoutParams as LinearLayout.LayoutParams).topMargin = dp(8)
        }
        serverSetup = expander(serverCard, getString(R.string.server_settings), open = settings.tunnelEnabled && settings.tunnelHost.isBlank())
        val setup = serverSetup.body
        text(setup, getString(R.string.paste_title), 15f, bold = true)
        text(setup, getString(R.string.paste_help), 14f, secondary = true)
        pasteBox = EditText(this).apply {
            hint = getString(R.string.paste_hint)
            contentDescription = getString(R.string.paste_title)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            minLines = 3
            maxLines = 8
            gravity = Gravity.TOP or Gravity.START
            textSize = 14f
            typeface = Typeface.MONOSPACE
            styleField(this)
            setup.addView(this, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(6) })
        }
        val pasteActions = buttonRow(setup)
        button(pasteActions, getString(R.string.paste_from_clipboard), ButtonKind.OUTLINE, fill = true) {
            val clip = getSystemService(ClipboardManager::class.java).primaryClip
            val pasted = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString().orEmpty()
            if (pasted.isNotBlank()) { pasteBox.setText(pasted); recognize() }
        }
        button(pasteActions, getString(R.string.recognize), ButtonKind.TONAL, fill = true) { recognize() }
        recognizedText = text(setup, "", 14f).apply {
            background = rounded(color(R.color.good_container), 12)
            setTextColor(color(R.color.on_good_container))
            setPadding(dp(12), dp(10), dp(12), dp(10))
            visibility = View.GONE
            (layoutParams as LinearLayout.LayoutParams).topMargin = dp(8)
        }
        hostField = field(setup, R.string.ssh_host, settings.tunnelHost, getString(R.string.ssh_host_hint))
        val ports = row(setup)
        portField = field(ports, R.string.ssh_port, settings.tunnelPort.toString(), Settings.DEFAULT_TUNNEL_PORT.toString(), numeric = true)
        remoteField = field(ports, R.string.remote_port, settings.tunnelRemotePort.toString(), Settings.DEFAULT_REMOTE_PORT.toString(), numeric = true)
        userField = field(setup, R.string.ssh_user, settings.tunnelUser, Settings.DEFAULT_TUNNEL_USER)
        publicUrlField = field(setup, R.string.public_url, settings.publicUrl, getString(R.string.public_url_hint))
        button(setup, getString(R.string.save), ButtonKind.PRIMARY, fill = true) { saveServer() }
            .also { (it.layoutParams as LinearLayout.LayoutParams).topMargin = dp(14) }

        serverKeys = expander(serverCard, getString(R.string.server_keys))
        val keys = serverKeys.body
        text(keys, getString(R.string.server_keys_help), 14f)
        button(keys, getString(R.string.copy_authorized), ButtonKind.TONAL, fill = true) {
            val key = keyText.text.toString()
            val remotePort = remoteField.text.toString().trim().ifEmpty { Settings.DEFAULT_REMOTE_PORT.toString() }.toIntOrNull()
            if (remotePort == null || remotePort !in 1..65535) {
                remoteField.error = getString(R.string.connection_invalid)
            } else if (key.startsWith("ecdsa-")) copy(getString(R.string.public_key),
                TunnelLogic.authorizedKeysLine(key, remotePort))
        }
        text(keys, getString(R.string.public_key), 14f, bold = true).setPadding(0, dp(12), 0, dp(2))
        keyText = code(keys, "").apply { setTextIsSelectable(true) }
        // Key generation may block on entropy; keep it off the UI thread.
        Thread({
            val key = runCatching { TunnelKeys.publicKey(applicationContext) }.getOrNull()
            runOnUiThread { keyText.text = key ?: getString(R.string.key_failed) }
        }, "tentacle-key").start()
        button(keys, getString(R.string.copy_key), ButtonKind.OUTLINE, fill = true) {
            val key = keyText.text.toString()
            if (key.startsWith("ecdsa-")) copy(getString(R.string.public_key), key)
        }
        hostFingerprint = text(keys, "", 13f, secondary = true).apply { setPadding(0, dp(10), 0, dp(4)) }
        trustNewKey = button(keys, getString(R.string.trust_new_key), ButtonKind.OUTLINE, fill = true) {
            ServerService.trustNewHostKey(this)
        }
        checkCloudflare(settings.tunnelHost)
    }

    private fun settingsSection() {
        settingsCard = card().apply { setPadding(dp(14), dp(4), dp(18), dp(8)) }
        settingsBody = expander(settingsCard, getString(R.string.settings), large = true)
        val body = settingsBody.body
        codesSection(body)
        val portRow = row(body).apply { gravity = Gravity.CENTER_VERTICAL }
        portText = text(portRow, "", 15f).apply { layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) }
        button(portRow, getString(R.string.change), ButtonKind.OUTLINE) { changePort() }
        boot = switch(body, getString(R.string.boot), settings.startOnBoot) { settings.startOnBoot = it }
        switch(body, getString(R.string.keep_awake), settings.keepAwake) {
            settings.keepAwake = it
            if (ServerStatus.value.running) ServerService.start(this)
        }
        text(body, getString(R.string.awake_help), 13f, secondary = true)
        val languageRow = row(body).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(8), 0, 0) }
        text(languageRow, getString(R.string.language), 15f).layoutParams =
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        button(languageRow, languageLabel(), ButtonKind.OUTLINE) { chooseLanguage() }
        val logRow = row(body).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(8), 0, 0) }
        text(logRow, getString(R.string.app_log), 15f).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        button(logRow, getString(R.string.view), ButtonKind.OUTLINE) { showAppLog() }
        button(logRow, getString(R.string.clear), ButtonKind.OUTLINE) { confirmClearAppLog() }
            .also { (it.layoutParams as LinearLayout.LayoutParams).marginStart = dp(8) }
        updateSourceField = field(body, R.string.update_source, settings.updateSource, getString(R.string.update_source_hint))
        button(body, getString(R.string.save_update_source), ButtonKind.TONAL, fill = true) {
            settings.updateSource = updateSourceField.text.toString().trim()
            AppUpdater.check(this, manual = true)
        }.also { (it.layoutParams as LinearLayout.LayoutParams).topMargin = dp(10) }
    }

    private fun codesSection(body: LinearLayout) {
        codesSwitch = switch(body, getString(R.string.codes_enable), settings.codesEnabled) { enabled ->
            if (!enabled) {
                settings.codesEnabled = false
                refreshCodes()
            } else {
                // A switch is only confirmed after the owner accepts the warning.
                updating = true
                codesSwitch.isChecked = false
                updating = false
                AlertDialog.Builder(this).setTitle(R.string.codes_title)
                    .setMessage(R.string.codes_warning)
                    .setNegativeButton(R.string.cancel, null)
                    .setPositiveButton(R.string.codes_turn_on) { _, _ ->
                        settings.codesEnabled = true
                        refreshCodes()
                        requestCodeSmsAccess()
                    }.show()
            }
        }
        codesStatus = text(body, "", 14f, secondary = true)
        text(body, getString(R.string.codes_hint), 13f, secondary = true)
        codesSmsHelp = column().also { body.addView(it) }
        text(codesSmsHelp, codeSmsAccessMessage(), 13f, secondary = true)
        button(codesSmsHelp, getString(R.string.codes_allow_sms), ButtonKind.OUTLINE, fill = true) {
            requestCodeSmsAccess()
        }
        button(codesSmsHelp, getString(R.string.codes_app_info), ButtonKind.OUTLINE, fill = true) {
            launch(appDetails())
        }
        if (isXiaomi()) codesXiaomiHelp = text(body, getString(R.string.codes_xiaomi_sms_help), 13f, secondary = true)
        codesNotificationsSwitch = switch(body, getString(R.string.codes_notifications), settings.codesNotifications) { enabled ->
            settings.codesNotifications = enabled
            refreshCodes()
            if (enabled && !notificationCodeAccess()) explainCodeAccess()
        }
        text(body, getString(R.string.codes_notifications_hint), 13f, secondary = true)
        codesAccessHelp = column().also { body.addView(it) }
        text(codesAccessHelp, getString(R.string.codes_access_help), 13f, secondary = true)
        button(codesAccessHelp, getString(R.string.codes_open_access), ButtonKind.OUTLINE, fill = true) {
            launch(Intent(SystemSettings.ACTION_NOTIFICATION_LISTENER_SETTINGS), appDetails())
        }
        if (Build.VERSION.SDK_INT >= 33) {
            text(codesAccessHelp, getString(R.string.codes_restricted_help), 13f, secondary = true)
            button(codesAccessHelp, getString(R.string.codes_app_info), ButtonKind.OUTLINE, fill = true) {
                launch(appDetails())
            }
        }
    }

    private fun smsCodeAccess(): Boolean =
        checkSelfPermission(Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED

    private fun requestCodeSmsAccess() {
        if (smsCodeAccess()) return
        if (settings.wasPermissionRequested(Manifest.permission.RECEIVE_SMS) &&
            !shouldShowRequestPermissionRationale(Manifest.permission.RECEIVE_SMS)) {
            explainCodeSmsAccess()
        } else {
            requestRuntimePermission(Manifest.permission.RECEIVE_SMS, SMS_PERMISSION_REQUEST)
        }
    }

    private fun codeSmsAccessMessage(): String = buildList {
        add(getString(R.string.codes_sms_help))
        if (Build.VERSION.SDK_INT >= 33) add(getString(R.string.codes_restricted_help))
        if (isXiaomi()) add(getString(R.string.codes_xiaomi_sms_help))
    }.joinToString("\n\n")

    private fun explainCodeSmsAccess() {
        AlertDialog.Builder(this).setTitle(R.string.codes_needs_sms).setMessage(codeSmsAccessMessage())
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.codes_app_info) { _, _ -> launch(appDetails()) }
            .show()
    }

    private fun notificationCodeAccess(): Boolean {
        val component = ComponentName(this, dev.tentacle.codes.CodeNotificationListener::class.java)
        if (Build.VERSION.SDK_INT >= 27) {
            return getSystemService(NotificationManager::class.java).isNotificationListenerAccessGranted(component)
        }
        return SystemSettings.Secure.getString(contentResolver, "enabled_notification_listeners")
            .orEmpty().split(':').any { ComponentName.unflattenFromString(it) == component }
    }

    private fun explainCodeAccess() {
        val message = getString(R.string.codes_access_help) +
            if (Build.VERSION.SDK_INT >= 33) "\n\n" + getString(R.string.codes_restricted_help) else ""
        AlertDialog.Builder(this).setTitle(R.string.codes_open_access).setMessage(message)
            .setNegativeButton(R.string.cancel, null)
            .setNeutralButton(R.string.codes_app_info) { _, _ -> launch(appDetails()) }
            .setPositiveButton(R.string.codes_open_access) { _, _ ->
                launch(Intent(SystemSettings.ACTION_NOTIFICATION_LISTENER_SETTINGS), appDetails())
            }.show()
    }

    private fun refreshCodes() {
        if (!::codesStatus.isInitialized) return
        val enabled = settings.codesEnabled
        val smsAccess = smsCodeAccess()
        val latest = dev.tentacle.codes.CodeStores.get(this).latest()
        codesStatus.text = when {
            !enabled -> getString(R.string.codes_off)
            !smsAccess -> getString(R.string.codes_needs_sms)
            latest == null -> getString(R.string.codes_waiting)
            else -> getString(R.string.codes_last, dev.tentacle.codes.CodeCaptureLog.safeSender(latest.sender, latest.code),
                relativeAge(System.currentTimeMillis() - latest.receivedMs))
        }
        codesSmsHelp.visibility = if (enabled && !smsAccess) View.VISIBLE else View.GONE
        codesXiaomiHelp?.visibility = if (enabled && smsAccess) View.VISIBLE else View.GONE
        codesAccessHelp.visibility = if (enabled && settings.codesNotifications && !notificationCodeAccess())
            View.VISIBLE else View.GONE
        updating = true
        codesSwitch.isChecked = enabled
        codesNotificationsSwitch.isChecked = settings.codesNotifications
        codesNotificationsSwitch.isEnabled = enabled
        updating = false
    }

    private fun footerSection() {
        val footer = column().apply {
            setPadding(dp(4), dp(4), 0, 0)
            content.addView(this, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        updateStatusText = text(footer, "", 13f, secondary = true)
        checkUpdates = button(footer, getString(R.string.check_updates), ButtonKind.OUTLINE, fill = true) {
            when (AppUpdater.state) {
                is UpdateState.Available -> AppUpdater.downloadAndInstall(this)
                is UpdateState.PermissionRequired -> {
                    if (packageManager.canRequestPackageInstalls()) AppUpdater.downloadAndInstall(this)
                    else AppUpdater.openUnknownSources(this)
                }
                UpdateState.Checking, is UpdateState.Downloading, is UpdateState.Installing -> Unit
                else -> AppUpdater.check(this, manual = true)
            }
        }
    }

    // ---------------------------------------------------------------- state

    private fun refresh(state: ServerStatus.State = ServerStatus.value) {
        if (!::updateStatusText.isInitialized) return
        val tailnet = TailnetStatus.value
        val tunnel = TunnelStatus.value
        // Only worth saying while the server is off: then the computer cannot see new requests.
        if (::requestServerNote.isInitialized) requestServerNote.visibility = if (state.running) View.GONE else View.VISIBLE
        val addresses = DeviceEnvironment.addresses(this)
        val lan = addresses.firstOrNull { it.priority == 0 }
        val filesAllowed = DeviceEnvironment.allFilesAccess(this)
        val tailnetUp = tailnet.mode == TailnetStatus.Mode.RUNNING && tailnet.ip != null
        val tunnelUp = tunnel.mode == TunnelStatus.Mode.CONNECTED

        // Status card: one plain-language state and one action.
        val (tone, title, detail) = when {
            !state.running && state.error != null ->
                Triple(Tone.BAD, R.string.status_failed_title, getString(R.string.status_failed_text))
            !state.running -> Triple(Tone.NEUTRAL, R.string.status_stopped_title, getString(R.string.status_stopped_text))
            !filesAllowed -> Triple(Tone.WARN, R.string.status_attention_title, getString(R.string.status_attention_text))
            lan == null && !tailnetUp && !tunnelUp ->
                Triple(Tone.WARN, R.string.status_no_path_title, getString(R.string.status_no_path_text))
            else -> Triple(Tone.GOOD, R.string.status_ready_title, getString(R.string.status_ready_text, settings.port))
        }
        hero.background = rounded(container(tone), 20, stroke = if (tone == Tone.NEUTRAL) color(R.color.outline) else null)
        setBadge(heroBadge, when (tone) { Tone.GOOD -> "✓"; Tone.WARN -> "!"; Tone.BAD -> "✕"; else -> "■" }, tone)
        heroTitle.text = getString(title)
        heroTitle.setTextColor(onContainer(tone))
        heroText.text = detail
        heroText.setTextColor(onContainer(tone))
        startStop.text = getString(if (state.running) R.string.stop else R.string.start)
        styleButton(startStop, if (state.running) ButtonKind.OUTLINE else ButtonKind.PRIMARY)
        ways.removeAllViews()
        ways.visibility = if (state.running) View.VISIBLE else View.GONE
        if (state.running) {
            wayRow(getString(R.string.way_lan), lan?.host ?: getString(R.string.way_lan_none),
                if (lan != null) Tone.GOOD else Tone.NEUTRAL, null)
            val (tailnetTone, tailnetText) = when {
                !settings.tailnetEnabled -> Tone.NEUTRAL to getString(R.string.way_off)
                tailnetUp -> Tone.GOOD to tailnet.ip!!
                tailnet.mode == TailnetStatus.Mode.NEEDS_LOGIN -> Tone.WARN to tailnet.display(this)
                tailnet.mode == TailnetStatus.Mode.ERROR -> Tone.BAD to getString(R.string.way_problem)
                else -> Tone.BUSY to getString(R.string.tailnet_status_starting)
            }
            wayRow(getString(R.string.tailnet_builtin_short), tailnetText, tailnetTone, tailnetCard)
            val (tunnelTone, tunnelText) = when {
                !settings.tunnelEnabled -> Tone.NEUTRAL to getString(R.string.way_off)
                tunnelUp -> Tone.GOOD to getString(R.string.way_connected)
                tunnel.mode == TunnelStatus.Mode.ERROR -> Tone.BAD to getString(R.string.way_problem)
                else -> Tone.BUSY to getString(R.string.tunnel_status_connecting)
            }
            wayRow(getString(R.string.public_connection), tunnelText, tunnelTone, serverCard)
        }

        refreshAttention(filesAllowed)
        refreshPairs(addresses, tailnet)
        refreshTailnet(state, tailnet)
        refreshTunnel(state, tunnel)
        refreshUpdates()
        refreshCodes()
        portText.text = getString(R.string.port_value, settings.port)
        updating = true
        boot.isChecked = settings.startOnBoot
        updating = false
    }

    private fun refreshAttention(filesAllowed: Boolean) {
        attentionItems.removeAllViews()
        if (!filesAllowed) attentionItem(R.string.attention_files, R.string.attention_files_help, R.string.fix_allow) { requestStorage() }
        val notificationsAllowed = getSystemService(NotificationManager::class.java).areNotificationsEnabled() &&
            (Build.VERSION.SDK_INT < 33 || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)
        if (!notificationsAllowed) {
            attentionItem(R.string.attention_notifications, R.string.attention_notifications_help, R.string.fix_allow) { requestNotifications() }
        }
        if (!getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)) {
            attentionItem(R.string.attention_battery, R.string.attention_battery_help, R.string.fix_allow) {
                launch(Intent(SystemSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, packageUri()),
                    Intent(SystemSettings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS), appDetails())
            }
        }
        if (isXiaomi() && !settings.autostartOpened) {
            attentionItem(R.string.attention_autostart, R.string.attention_autostart_help, R.string.fix_open) {
                // Cannot be detected; opening the page once is the best signal there is.
                settings.autostartOpened = true
                launch(Intent().setComponent(ComponentName("com.miui.securitycenter",
                    "com.miui.permcenter.autostart.AutoStartManagementActivity")), appDetails())
            }
        }
        attentionCard.visibility = if (attentionItems.childCount > 0) View.VISIBLE else View.GONE
    }

    private fun refreshPairs(addresses: List<Pairing.Address>, tailnet: TailnetStatus.State) {
        tokenText.text = if (tokenVisible) settings.token else "••••••••••••••••"
        showToken.text = getString(if (tokenVisible) R.string.hide else R.string.show)
        pairs.removeAllViews()
        if (tailnet.mode == TailnetStatus.Mode.RUNNING && tailnet.ip != null) {
            pairingRow(getString(R.string.tailnet_builtin_short), "http://${tailnet.ip}:${settings.port}")
        } else if (addresses.isEmpty()) text(pairs, getString(R.string.no_network), 14f, secondary = true)
        addresses.forEach { address ->
            val label = getString(when (address.priority) {
                0 -> R.string.lan
                1 -> R.string.tailscale
                else -> R.string.other
            })
            pairingRow(label, "http://${address.host}:${settings.port}")
        }
        publicPairingUrl()?.let { pairingRow(getString(R.string.public_connection), it) }
    }

    private fun refreshTailnet(state: ServerStatus.State, tailnet: TailnetStatus.State) {
        val enabled = settings.tailnetEnabled
        tailnetStatusText.text = if (enabled && !state.running) getString(R.string.needs_server) else tailnet.display(this)
        tailnetStatusText.setTextColor(statusColor(when {
            !enabled -> Tone.NEUTRAL
            !state.running -> Tone.WARN
            tailnet.mode == TailnetStatus.Mode.RUNNING -> Tone.GOOD
            tailnet.mode == TailnetStatus.Mode.NEEDS_LOGIN -> Tone.WARN
            tailnet.mode == TailnetStatus.Mode.ERROR -> Tone.BAD
            else -> Tone.BUSY
        }))
        val needsLogin = tailnet.mode == TailnetStatus.Mode.NEEDS_LOGIN && tailnet.error == null
        tailnetLogin.visibility = if (needsLogin) View.VISIBLE else View.GONE
        tailnetHint.visibility = tailnetLogin.visibility
        tailnetLogout.visibility = if (tailnet.mode == TailnetStatus.Mode.RUNNING) View.VISIBLE else View.GONE
        tailnetKey.hint = getString(if (settings.tailnetAuthKey.isEmpty()) R.string.tailnet_auth_key_hint
            else R.string.tailnet_auth_key_saved)
        updating = true
        tailnetSwitch.isChecked = enabled
        updating = false
    }

    private fun refreshTunnel(state: ServerStatus.State, tunnel: TunnelStatus.State) {
        val enabled = settings.tunnelEnabled
        val configured = settings.tunnelHost.isNotBlank() && settings.tunnelUser.isNotBlank()
        tunnelStatusText.text = when {
            enabled && !configured -> getString(R.string.server_not_set_up)
            enabled && !state.running -> getString(R.string.needs_server)
            else -> tunnel.display(this)
        }
        tunnelStatusText.setTextColor(statusColor(when {
            !enabled -> Tone.NEUTRAL
            !configured || !state.running -> Tone.WARN
            tunnel.mode == TunnelStatus.Mode.CONNECTED -> Tone.GOOD
            tunnel.mode == TunnelStatus.Mode.ERROR -> Tone.BAD
            else -> Tone.BUSY
        }))
        serverTarget.visibility = if (configured) View.VISIBLE else View.GONE
        if (configured) {
            serverTarget.text = getString(R.string.server_target, settings.tunnelUser, settings.tunnelHost,
                settings.tunnelPort, settings.tunnelRemotePort) + settings.publicUrl.takeIf { it.isNotBlank() }?.let { "\n$it" }.orEmpty()
        }
        hostFingerprint.text = getString(R.string.host_fingerprint,
            (tunnel.hostKeyFingerprint ?: settings.trustedHostFingerprint).ifEmpty { getString(R.string.not_trusted) })
        trustNewKey.visibility = if (tunnel.error == TunnelStatus.Error.HOST_KEY_CHANGED) View.VISIBLE else View.GONE
        if (tunnel.error != lastTunnelError &&
            (tunnel.error == TunnelStatus.Error.HOST_KEY_CHANGED || tunnel.error == TunnelStatus.Error.KEY_REJECTED)) {
            serverKeys.open = true
        }
        lastTunnelError = tunnel.error
        updating = true
        tunnelSwitch.isChecked = enabled
        updating = false
    }

    private fun refreshUpdates() {
        val updateState = AppUpdater.state
        val release = AppUpdater.available
        updateBanner.visibility = if (release != null) View.VISIBLE else View.GONE
        if (release != null) {
            updateBannerText.text = if (updateState is UpdateState.Available || updateState is UpdateState.Idle)
                getString(R.string.update_status_available, release.version) else AppUpdater.stateText(this)
        }
        installUpdate.isEnabled = release != null &&
            updateState !is UpdateState.Downloading && updateState !is UpdateState.Installing
        installUpdate.alpha = if (installUpdate.isEnabled) 1f else 0.5f
        // One action at a time: "Allow installing updates" replaces Install only once Android asked for it.
        val needsPermission = updateState is UpdateState.PermissionRequired && !packageManager.canRequestPackageInstalls()
        allowUpdates.visibility = if (needsPermission) View.VISIBLE else View.GONE
        installUpdate.visibility = if (needsPermission) View.GONE else View.VISIBLE
        updateStatusText.text = getString(R.string.version, BuildConfig.VERSION_NAME) + " · " + AppUpdater.stateText(this)
        // Without an update source there is nothing to check.
        checkUpdates.visibility = if (settings.updateSource.isBlank()) View.GONE else View.VISIBLE
        checkUpdates.text = when (updateState) {
            UpdateState.Checking -> getString(R.string.update_checking)
            is UpdateState.Available -> getString(R.string.update_now_version, updateState.release.version)
            is UpdateState.Downloading -> updateState.percent?.let { getString(R.string.update_downloading_percent, it) }
                ?: getString(R.string.update_downloading)
            is UpdateState.PermissionRequired -> if (needsPermission) getString(R.string.update_allow_installs)
                else getString(R.string.update_now_version, updateState.release.version)
            is UpdateState.Installing -> getString(R.string.update_installing)
            else -> getString(R.string.check_updates)
        }
        styleButton(checkUpdates, if (updateState is UpdateState.Available || updateState is UpdateState.PermissionRequired ||
            updateState is UpdateState.Downloading || updateState is UpdateState.Installing) ButtonKind.PRIMARY else ButtonKind.OUTLINE)
        checkUpdates.isEnabled = updateState !is UpdateState.Checking && updateState !is UpdateState.Downloading &&
            updateState !is UpdateState.Installing
        checkUpdates.alpha = if (checkUpdates.isEnabled) 1f else 0.5f
    }

    private fun maybeShowUpdatePrompt() {
        val state = AppUpdater.state
        if (state !is UpdateState.Available) {
            updatePromptDialog?.dismiss()
            updatePromptDialog = null
            return
        }
        if (!activityVisible || isFinishing || isDestroyed || installRequested || settings.updateSource.isBlank()) return
        val version = state.release.version
        if (!UpdatePrompt.shouldShow(version, settings.dismissedUpdateVersion, state.manual,
                updatePromptDialog != null || shownUpdatePromptVersions.any { Releases.compareVersions(it, version) == 0 },
                busy = false)) return
        shownUpdatePromptVersions += version
        updatePromptDialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.update_prompt_title, version))
            .setMessage(R.string.update_prompt_message)
            .setPositiveButton(R.string.update_now) { _, _ -> AppUpdater.downloadAndInstall(this) }
            .setNegativeButton(R.string.update_later) { _, _ -> settings.dismissedUpdateVersion = version }
            .setOnCancelListener { settings.dismissedUpdateVersion = version }
            .create().also { dialog ->
                dialog.setOnDismissListener { if (updatePromptDialog === dialog) updatePromptDialog = null }
                dialog.show()
            }
    }

    private fun publicPairingUrl(): String? = settings.publicUrl.takeIf {
        settings.tunnelHost.isNotBlank() && settings.tunnelUser.isNotBlank() && it.isNotBlank()
    }

    // ---------------------------------------------------------------- own server

    private fun loadServerFields() {
        hostField.setText(settings.tunnelHost)
        portField.setText(settings.tunnelPort.toString())
        userField.setText(settings.tunnelUser)
        remoteField.setText(settings.tunnelRemotePort.toString())
        publicUrlField.setText(settings.publicUrl)
        checkCloudflare(settings.tunnelHost)
    }

    private fun recognize() {
        val info = ServerInfoParser.parse(pasteBox.text.toString())
        recognizedText.visibility = View.VISIBLE
        if (info.isEmpty) {
            recognizedText.text = getString(R.string.recognized_nothing)
            recognizedText.background = rounded(color(R.color.warn_container), 12)
            recognizedText.setTextColor(color(R.color.on_warn_container))
            return
        }
        info.host?.let { hostField.setText(it) }
        info.sshPort?.let { portField.setText(it.toString()) }
        info.user?.let { userField.setText(it) }
        info.remotePort?.let { remoteField.setText(it.toString()) }
        info.publicUrl?.let { publicUrlField.setText(it) }
        val found = mutableListOf<String>()
        val defaults = mutableListOf<String>()
        fun item(label: Int, value: Any?, fallback: String?) {
            when {
                value != null -> found += getString(R.string.item_value, getString(label), value.toString())
                fallback != null -> defaults += getString(R.string.item_value, getString(label), fallback)
            }
        }
        item(R.string.ssh_host, info.host, null)
        item(R.string.ssh_port, info.sshPort, portField.text.toString().ifBlank { Settings.DEFAULT_TUNNEL_PORT.toString() })
        item(R.string.ssh_user, info.user, userField.text.toString().ifBlank { Settings.DEFAULT_TUNNEL_USER })
        item(R.string.remote_port, info.remotePort, remoteField.text.toString().ifBlank { Settings.DEFAULT_REMOTE_PORT.toString() })
        item(R.string.public_url, info.publicUrl, null)
        val separator = getString(R.string.list_separator)
        val lines = mutableListOf(getString(R.string.recognized, found.joinToString(separator)))
        if (defaults.isNotEmpty()) lines += getString(R.string.recognized_defaults, defaults.joinToString(separator))
        if (hostField.text.isBlank()) lines += getString(R.string.recognized_missing, getString(R.string.ssh_host))
        if (info.hostFromUrl) lines += getString(R.string.host_from_url_note)
        lines += getString(R.string.recognized_check)
        recognizedText.text = lines.joinToString("\n")
        val complete = hostField.text.isNotBlank() && !info.hostFromUrl
        recognizedText.background = rounded(color(if (complete) R.color.good_container else R.color.warn_container), 12)
        recognizedText.setTextColor(color(if (complete) R.color.on_good_container else R.color.on_warn_container))
        checkCloudflare(hostField.text.toString().trim())
    }

    private fun saveServer() {
        val host = hostField.text.toString().trim().removePrefix("[").removeSuffix("]")
        val user = userField.text.toString().trim().ifEmpty { Settings.DEFAULT_TUNNEL_USER }
        val sshPort = portField.text.toString().trim().ifEmpty { Settings.DEFAULT_TUNNEL_PORT.toString() }.toIntOrNull()
        val remotePort = remoteField.text.toString().trim().ifEmpty { Settings.DEFAULT_REMOTE_PORT.toString() }.toIntOrNull()
        val url = publicUrlField.text.toString().trim().trimEnd('/')
        val validUrl = url.isEmpty() || runCatching {
            val parsed = java.net.URI(url)
            parsed.scheme in listOf("http", "https") && !parsed.host.isNullOrEmpty() && parsed.rawQuery == null &&
                parsed.rawFragment == null && parsed.userInfo == null
        }.getOrDefault(false)
        if (host.isEmpty() || host.any { it.isWhitespace() } || sshPort == null || sshPort !in 1..65535 ||
            remotePort == null || remotePort !in 1..65535 || !validUrl) {
            Toast.makeText(this, R.string.connection_invalid, Toast.LENGTH_LONG).show()
            if (host.isEmpty()) hostField.error = getString(R.string.connection_invalid)
            return
        }
        settings.tunnelHost = host
        settings.tunnelPort = sshPort
        settings.tunnelUser = user
        settings.tunnelRemotePort = remotePort
        settings.publicUrl = url
        loadServerFields()
        if (ServerStatus.value.running) ServerService.start(this, restart = true)
        Toast.makeText(this, R.string.connection_saved, Toast.LENGTH_SHORT).show()
        refresh()
    }

    /** SSH cannot pass an HTTP CDN; warn when the host resolves into Cloudflare's ranges. */
    private fun checkCloudflare(host: String) {
        val generation = ++cloudflareCheck
        cloudflareWarning.visibility = View.GONE
        val name = host.trim().removePrefix("[").removeSuffix("]")
        if (name.isEmpty()) return
        Thread({
            val proxied = runCatching {
                IpAddresses.parse(name)?.let { listOf(it) } ?: InetAddress.getAllByName(name).map { it.address }
            }.getOrDefault(emptyList()).any(IpAddresses::isCloudflare)
            runOnUiThread {
                if (generation != cloudflareCheck || isDestroyed) return@runOnUiThread
                cloudflareWarning.text = getString(R.string.cloudflare_warning, name)
                cloudflareWarning.visibility = if (proxied) View.VISIBLE else View.GONE
            }
        }, "tentacle-dns").start()
    }

    // ---------------------------------------------------------------- actions

    private fun openLoginUrl(url: String) {
        val uri = Uri.parse(url)
        if (uri.scheme != "http" && uri.scheme != "https") return
        launch(Intent(Intent.ACTION_VIEW, uri))
    }

    private fun requestStorage() {
        if (Build.VERSION.SDK_INT >= 30) {
            launch(Intent(SystemSettings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, packageUri()),
                Intent(SystemSettings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION), appDetails())
        } else {
            @Suppress("DEPRECATION")
            requestRuntimePermission(Manifest.permission.READ_EXTERNAL_STORAGE, 1)
        }
    }

    private fun requestNotifications() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestRuntimePermission(Manifest.permission.POST_NOTIFICATIONS, 2)
        } else {
            launch(Intent(SystemSettings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(SystemSettings.EXTRA_APP_PACKAGE, packageName), appDetails())
        }
    }

    private fun requestRuntimePermission(permission: String, requestCode: Int) {
        if (checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED ||
            (settings.wasPermissionRequested(permission) && !shouldShowRequestPermissionRationale(permission))) {
            launch(appDetails())
        } else {
            settings.markPermissionRequested(permission)
            requestPermissions(arrayOf(permission), requestCode)
        }
    }

    private fun changePort() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(settings.port.toString())
            selectAll()
            contentDescription = getString(R.string.server_port)
        }
        val box = FrameLayout(this).apply {
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(input)
        }
        val dialog = AlertDialog.Builder(this).setTitle(getString(R.string.port_title))
            .setView(box).setNegativeButton(getString(R.string.cancel), null).setPositiveButton(getString(R.string.save), null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val port = input.text.toString().toIntOrNull()
                if (port == null || port !in 1024..65535) input.error = getString(R.string.port_error)
                else {
                    val changed = port != settings.port
                    settings.port = port
                    if (changed && ServerStatus.value.running) ServerService.start(this, restart = true)
                    refresh()
                    dialog.dismiss()
                }
            }
        }
        dialog.show()
    }

    private fun languageLabel(): String = when (AppLanguage.currentTag(this)) {
        "en" -> getString(R.string.language_english)
        "zh-CN" -> getString(R.string.language_chinese)
        else -> getString(R.string.language_system)
    }

    private fun chooseLanguage() {
        val labels = arrayOf(getString(R.string.language_system), getString(R.string.language_english), getString(R.string.language_chinese))
        val tags = arrayOf("", "en", "zh-CN")
        AlertDialog.Builder(this).setTitle(R.string.language)
            .setSingleChoiceItems(labels, tags.indexOf(AppLanguage.currentTag(this))) { dialog, index ->
                AppLanguage.set(this, tags[index])
                dialog.dismiss()
                if (ServerStatus.value.running) ServerService.start(this)
                if (Build.VERSION.SDK_INT < 33) recreate()
            }.setNegativeButton(R.string.cancel, null).show()
    }

    private fun showAppLog() {
        val value = try {
            AppLog.snapshot().lineSequence().toList().takeLast(300).joinToString("\n")
                .ifEmpty { getString(R.string.app_log_empty) }
        } catch (_: Exception) {
            Toast.makeText(this, R.string.app_log_failed, Toast.LENGTH_LONG).show()
            return
        }
        val logText = TextView(this).apply {
            text = value
            typeface = Typeface.MONOSPACE
            textSize = 11f
            setTextColor(color(R.color.text_primary))
            setTextIsSelectable(true)
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        val logScroll = ScrollView(this).apply { addView(logText) }
        // Newest lines are what matter: open at the end.
        logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
        AlertDialog.Builder(this)
            .setTitle(R.string.app_log)
            .setView(logScroll)
            .setNegativeButton(R.string.close, null)
            .setPositiveButton(R.string.copy) { _, _ -> copy(getString(R.string.app_log), value) }
            .show()
    }

    private fun confirmClearAppLog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.app_log_clear_title)
            .setMessage(R.string.app_log_clear_message)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.clear) { _, _ ->
                try {
                    AppLog.clear()
                    Toast.makeText(this, R.string.app_log_cleared, Toast.LENGTH_SHORT).show()
                } catch (_: Exception) {
                    Toast.makeText(this, R.string.app_log_failed, Toast.LENGTH_LONG).show()
                }
            }
            .show()
    }

    private fun copy(label: String, value: String) {
        val clip = ClipData.newPlainText(label, value)
        if (Build.VERSION.SDK_INT >= 33) {
            clip.description.extras = android.os.PersistableBundle().apply {
                putBoolean(android.content.ClipDescription.EXTRA_IS_SENSITIVE, true)
            }
        }
        getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
        Toast.makeText(this, getString(R.string.copied), Toast.LENGTH_SHORT).show()
    }

    private fun launch(vararg intents: Intent) {
        for (intent in intents) {
            try { startActivity(intent); return } catch (_: Exception) { /* Try the next settings page. */ }
        }
        Toast.makeText(this, getString(R.string.settings_unavailable), Toast.LENGTH_LONG).show()
    }

    private fun packageUri(): Uri = Uri.parse("package:$packageName")
    private fun appDetails(): Intent = Intent(SystemSettings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri())
    private fun isXiaomi(): Boolean = listOf(Build.MANUFACTURER, Build.BRAND).any {
        it.equals("Xiaomi", true) || it.equals("Redmi", true) || it.equals("POCO", true)
    }

    // ---------------------------------------------------------------- widgets

    /** A collapsible block: a 48dp header row with a chevron, and a body shown on demand. */
    private inner class Expander(parent: LinearLayout, private val title: String, open: Boolean, large: Boolean) {
        val header: TextView = TextView(this@MainActivity)
        val body: LinearLayout = column()
        var open: Boolean = open
            set(value) {
                field = value
                body.visibility = if (value) View.VISIBLE else View.GONE
                header.text = (if (value) "▾  " else "▸  ") + title
            }

        init {
            header.apply {
                setTextAppearance(android.R.style.TextAppearance_DeviceDefault)
                textSize = if (large) 18f else 15f
                setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL))
                setTextColor(if (large) color(R.color.text_primary) else color(R.color.accent))
                gravity = Gravity.CENTER_VERTICAL
                minHeight = dp(48)
                background = getDrawable(R.drawable.bg_row)
                isClickable = true
                setOnClickListener { this@Expander.open = !this@Expander.open }
            }
            parent.addView(header, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = if (large) 0 else dp(4) })
            parent.addView(body, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            this.open = open
        }
    }

    private fun expander(parent: LinearLayout, title: String, open: Boolean = false, large: Boolean = false) =
        Expander(parent, title, open, large)

    private fun card(): LinearLayout = column().apply {
        background = getDrawable(R.drawable.bg_card)
        setPadding(dp(18), dp(16), dp(18), dp(16))
        content.addView(this, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { bottomMargin = dp(12) })
    }

    private fun sectionLabel(title: String) {
        text(content, title, 17f, bold = true).setPadding(dp(4), dp(12), 0, dp(2))
    }

    private fun cardTitle(parent: LinearLayout, title: String): TextView =
        text(parent, title, 18f, bold = true).apply { setPadding(0, 0, 0, dp(6)) }

    private fun cardSwitch(parent: LinearLayout, title: String, checked: Boolean, action: (Boolean) -> Unit): Switch =
        switch(parent, title, checked, action).apply {
            textSize = 18f
            setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL))
            setPadding(0, 0, 0, dp(4))
        }

    private fun switch(parent: LinearLayout, title: String, checked: Boolean, action: (Boolean) -> Unit): Switch = Switch(this).apply {
        text = title
        textSize = 15f
        setTextColor(color(R.color.text_primary))
        isChecked = checked
        minHeight = dp(48)
        setOnCheckedChangeListener { _, value -> if (!updating) action(value) }
        parent.addView(this, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private fun wayRow(label: String, status: String, tone: Tone, target: View?) {
        val line = row(ways).apply {
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(44)
            setPadding(dp(4), 0, dp(4), 0)
            if (target != null) {
                background = getDrawable(R.drawable.bg_row)
                setOnClickListener { scroll.smoothScrollTo(0, target.top - dp(8)) }
            }
        }
        badge(line, when (tone) { Tone.GOOD -> "✓"; Tone.WARN -> "!"; Tone.BAD -> "✕"; Tone.BUSY -> "…"; else -> "–" }, tone, 24)
        text(line, label, 15f).apply {
            setTextColor(onContainer(Tone.NEUTRAL))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(12) }
        }
        text(line, status, 14f).apply {
            setTextColor(if (tone == Tone.NEUTRAL) color(R.color.text_secondary) else statusColor(tone))
            gravity = Gravity.END
            maxLines = 2
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { marginStart = dp(8) }
        }
    }

    private fun attentionItem(title: Int, help: Int, action: Int, onClick: () -> Unit) {
        val line = row(attentionItems).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(8), 0, 0) }
        val texts = column()
        line.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        text(texts, getString(title), 15f, bold = true).setTextColor(color(R.color.on_warn_container))
        text(texts, getString(help), 13f).apply { setTextColor(color(R.color.on_warn_container)); setPadding(0, 0, 0, 0) }
        button(line, getString(action), ButtonKind.PRIMARY, onClick = onClick)
            .also { (it.layoutParams as LinearLayout.LayoutParams).marginStart = dp(10) }
    }

    private fun pairingRow(label: String, baseUrl: String) {
        val link = Pairing.uri(baseUrl, settings.token, DeviceEnvironment.deviceName)
        val line = row(pairs).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(4), 0, dp(4)) }
        val texts = column()
        line.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        text(texts, label, 14f, bold = true).setPadding(0, 0, 0, 0)
        text(texts, if (tokenVisible) link else baseUrl, 12f, secondary = true).apply {
            typeface = Typeface.MONOSPACE
            setPadding(0, 0, 0, 0)
        }
        button(line, getString(R.string.copy), ButtonKind.OUTLINE) { copy(getString(R.string.copy_all_links), link) }
            .also { (it.layoutParams as LinearLayout.LayoutParams).marginStart = dp(8) }
    }

    private fun field(parent: LinearLayout, label: Int, initial: String, example: String, numeric: Boolean = false): EditText {
        val holder = column()
        val horizontal = parent.orientation == LinearLayout.HORIZONTAL
        parent.addView(holder, if (horizontal) LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            if (parent.childCount > 0) marginStart = dp(10)
        } else LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        text(holder, getString(label), 13f, secondary = true).setPadding(dp(2), dp(10), 0, dp(4))
        return EditText(this).apply {
            setText(initial)
            hint = example
            contentDescription = getString(label)
            inputType = if (numeric) InputType.TYPE_CLASS_NUMBER else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setSingleLine(true)
            textSize = 15f
            styleField(this)
            holder.addView(this, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
    }

    private fun styleField(edit: EditText) {
        edit.background = getDrawable(R.drawable.bg_field)
        edit.setPadding(dp(12), dp(10), dp(12), dp(10))
        edit.minHeight = dp(48)
        edit.setTextColor(color(R.color.text_primary))
        edit.setHintTextColor(color(R.color.off))
    }

    private fun code(parent: LinearLayout, value: String): TextView = text(parent, value, 13f).apply {
        typeface = Typeface.MONOSPACE
        background = getDrawable(R.drawable.bg_code)
        setPadding(dp(12), dp(8), dp(12), dp(8))
        (layoutParams as LinearLayout.LayoutParams).apply { topMargin = dp(4); bottomMargin = dp(4) }
    }

    private fun badge(parent: LinearLayout, symbol: String, tone: Tone, size: Int): TextView = TextView(this).apply {
        gravity = Gravity.CENTER
        textSize = size * 0.45f
        setTypeface(null, Typeface.BOLD)
        includeFontPadding = false
        setBadge(this, symbol, tone)
        parent.addView(this, LinearLayout.LayoutParams(dp(size), dp(size)))
    }

    private fun setBadge(view: TextView, symbol: String, tone: Tone) {
        view.text = symbol
        view.setTextColor(color(R.color.card))
        view.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(if (tone == Tone.NEUTRAL) color(R.color.off) else statusColor(tone))
        }
    }

    private fun buttonRow(parent: LinearLayout): LinearLayout = row(parent).apply { setPadding(0, dp(8), 0, 0) }

    private fun button(parent: LinearLayout, title: String, kind: ButtonKind, fill: Boolean = false, onClick: () -> Unit): Button =
        Button(this).apply {
            text = title
            isAllCaps = false
            textSize = 15f
            setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL))
            stateListAnimator = null
            minHeight = dp(48)
            minimumHeight = dp(48)
            minWidth = dp(64)
            minimumWidth = dp(64)
            setPadding(dp(18), 0, dp(18), 0)
            styleButton(this, kind)
            setOnClickListener { onClick() }
            // In a button row (fill) buttons share the width equally; otherwise they wrap their label.
            val horizontal = parent.orientation == LinearLayout.HORIZONTAL
            val params = when {
                horizontal && fill -> LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    if (parent.childCount > 0) marginStart = dp(8)
                }
                fill -> LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                else -> LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            }
            parent.addView(this, params.apply { if (!horizontal) topMargin = dp(6) })
        }

    private fun styleButton(button: Button, kind: ButtonKind) {
        button.background = getDrawable(when (kind) {
            ButtonKind.PRIMARY -> R.drawable.bg_button_primary
            ButtonKind.TONAL -> R.drawable.bg_button_tonal
            ButtonKind.OUTLINE -> R.drawable.bg_button_outline
        })
        button.setTextColor(color(when (kind) {
            ButtonKind.PRIMARY -> R.color.on_accent
            ButtonKind.TONAL -> R.color.on_accent_container
            ButtonKind.OUTLINE -> R.color.accent
        }))
    }

    private fun container(tone: Tone): Int = color(when (tone) {
        Tone.GOOD -> R.color.good_container
        Tone.WARN -> R.color.warn_container
        Tone.BAD -> R.color.bad_container
        else -> R.color.card
    })

    private fun onContainer(tone: Tone): Int = color(when (tone) {
        Tone.GOOD -> R.color.on_good_container
        Tone.WARN -> R.color.on_warn_container
        Tone.BAD -> R.color.on_bad_container
        else -> R.color.text_primary
    })

    private fun statusColor(tone: Tone): Int = color(when (tone) {
        Tone.GOOD -> R.color.good
        Tone.WARN -> R.color.warn
        Tone.BAD -> R.color.bad
        Tone.BUSY -> R.color.accent
        Tone.NEUTRAL -> R.color.text_secondary
    })

    private fun rounded(fill: Int, radiusDp: Int, stroke: Int? = null) = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = dp(radiusDp).toFloat()
        stroke?.let { setStroke(dp(1), it) }
    }

    private fun color(id: Int): Int = getColor(id)
    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    private fun row(parent: LinearLayout) = LinearLayout(this).also {
        it.orientation = LinearLayout.HORIZONTAL
        parent.addView(it, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
    private fun text(parent: LinearLayout, value: String, size: Float = 15f, bold: Boolean = false, secondary: Boolean = false): TextView =
        TextView(this).apply {
            setTextAppearance(android.R.style.TextAppearance_DeviceDefault)
            text = value
            textSize = size
            setTextColor(color(if (secondary) R.color.text_secondary else R.color.text_primary))
            if (bold) setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL))
            setLineSpacing(0f, 1.15f)
            setPadding(0, dp(3), 0, dp(3))
            parent.addView(this, LinearLayout.LayoutParams(
                if (parent.orientation == LinearLayout.HORIZONTAL) ViewGroup.LayoutParams.WRAP_CONTENT else ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT))
        }

    private companion object {
        val shownUpdatePromptVersions = mutableSetOf<String>()
        const val SMS_PERMISSION_REQUEST = 3
        const val REQUEST_DRAFT_STATE = "request_draft"
        const val REQUEST_REFRESH_MS = 10_000L
    }
}
