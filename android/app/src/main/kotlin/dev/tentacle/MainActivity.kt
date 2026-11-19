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
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings as SystemSettings
import android.text.InputType
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import dev.tentacle.tunnel.TunnelKeys
import dev.tentacle.tunnel.TunnelStatus
import dev.tentacle.tunnel.TunnelLogic
import dev.tentacle.update.AppUpdater
import dev.tentacle.update.UpdateState

class MainActivity : Activity() {
    private lateinit var settings: Settings
    private lateinit var content: LinearLayout
    private lateinit var statusText: TextView
    private lateinit var startStop: Button
    private lateinit var tokenText: TextView
    private lateinit var showToken: Button
    private lateinit var pairs: LinearLayout
    private lateinit var storageState: TextView
    private lateinit var notificationState: TextView
    private lateinit var batteryState: TextView
    private lateinit var portText: TextView
    private lateinit var boot: Switch
    private lateinit var tunnelStatusText: TextView
    private lateinit var hostFingerprint: TextView
    private lateinit var updateStatusText: TextView
    private var tunnelSubscription: AutoCloseable? = null
    private var updateSubscription: AutoCloseable? = null

    override fun attachBaseContext(base: Context) = super.attachBaseContext(AppLanguage.wrap(base))

    private var installRequested = false
    private lateinit var installUpdate: Button
    private lateinit var allowUpdates: Button

    private var tokenVisible = false
    private var updating = false
    private var statusSubscription: AutoCloseable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = Settings(this)
        buildScreen()
        handleDebugIntent(intent)
        AppUpdater.initialize(this)
        handleUpdateIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleDebugIntent(intent)
        handleUpdateIntent(intent)
    }

    private fun handleDebugIntent(intent: Intent) {
        if (BuildConfig.DEBUG && intent.getBooleanExtra("autostart", false)) ServerService.start(this)
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
        ServerService.refreshNotificationChannel(this)
        AppUpdater.refreshNotificationChannel(this)
        statusSubscription = ServerStatus.observe { runOnUiThread { refresh(it) } }
        tunnelSubscription = TunnelStatus.observe { runOnUiThread { refresh() } }
        updateSubscription = AppUpdater.observe { state -> runOnUiThread {
            refresh()
            if (installRequested && state is UpdateState.Available) {
                installRequested = false
                AppUpdater.downloadAndInstall(this)
            } else if (state is UpdateState.Error || state is UpdateState.Disabled || state is UpdateState.UpToDate) {
                installRequested = false
            }
        } }
    }

    override fun onPause() {
        tunnelSubscription?.close()
        tunnelSubscription = null
        updateSubscription?.close()
        updateSubscription = null
        statusSubscription?.close()
        statusSubscription = null
        tokenVisible = false
        refresh()
        super.onPause()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refresh()
    }

    private fun buildScreen() {
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = true
        }
        content = column().apply { setPadding(dp(20), dp(12), dp(20), dp(24)) }
        scroll.addView(content)
        val frame = FrameLayout(this).apply {
            clipToPadding = true
            addView(scroll, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        // Keep the scrolling viewport inside the bars, including while its content is moving.
        frame.setOnApplyWindowInsetsListener { view, insets ->
            view.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            insets
        }
        setContentView(frame)
        text(content, getString(R.string.app_name), 28f).setTypeface(null, Typeface.BOLD)
        text(content, getString(R.string.intro))
        section(getString(R.string.server))
        statusText = text(content, getString(R.string.stopped), 18f)
        startStop = button(content, getString(R.string.start)) {
            if (ServerStatus.value.running) ServerService.stop(this) else ServerService.start(this)
            refresh()
        }
        section(getString(R.string.pairing))
        text(content, getString(R.string.pairing_help))
        tokenText = text(content, "").apply { typeface = Typeface.MONOSPACE }
        val tokenActions = row(content)
        showToken = button(tokenActions, getString(R.string.show)) { tokenVisible = !tokenVisible; refresh() }
        button(tokenActions, getString(R.string.copy_token)) { copy(getString(R.string.copy_token), settings.token) }
        button(tokenActions, getString(R.string.regenerate)) {
            AlertDialog.Builder(this).setTitle(getString(R.string.regenerate_title))
                .setMessage(getString(R.string.regenerate_message))
                .setNegativeButton(getString(R.string.cancel), null)
                .setPositiveButton(getString(R.string.regenerate)) { _, _ -> settings.regenerateToken(); refresh() }.show()
        }
        pairs = column().also { content.addView(it) }
        connectionSection()
        section(getString(R.string.permissions))
        storageState = permission(getString(R.string.all_files), getString(R.string.open_settings)) { requestStorage() }
        notificationState = permission(getString(R.string.notifications), getString(R.string.allow_settings)) { requestNotifications() }
        batteryState = permission(getString(R.string.battery), getString(R.string.open_settings)) {
            launch(Intent(SystemSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, packageUri()),
                Intent(SystemSettings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS), appDetails())
        }
        if (isXiaomi()) {
            permission(getString(R.string.autostart_unknown), getString(R.string.check_manually)) {
                launch(Intent().setComponent(ComponentName("com.miui.securitycenter",
                    "com.miui.permcenter.autostart.AutoStartManagementActivity")), appDetails())
            }
        }
        section(getString(R.string.settings))
        portText = text(content, "")
        button(content, getString(R.string.change_port)) { changePort() }
        boot = toggle(getString(R.string.boot), settings.startOnBoot) { settings.startOnBoot = it }
        toggle(getString(R.string.keep_awake), settings.keepAwake) {
            settings.keepAwake = it
            if (ServerStatus.value.running) ServerService.start(this)
        }
        text(content, getString(R.string.awake_help))
        updatesSection()
        section(getString(R.string.language))
        button(content, getString(R.string.language)) {
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
        section(getString(R.string.version, BuildConfig.VERSION_NAME))
        text(content, getString(R.string.privacy))
    }

    private fun refresh(state: ServerStatus.State = ServerStatus.value) {
        if (!::statusText.isInitialized) return
        statusText.text = when {
            state.error != null -> getString(R.string.server_failed)
            state.running -> getString(R.string.running, settings.port)
            else -> getString(R.string.stopped)
        }
        startStop.text = if (state.running) getString(R.string.stop) else getString(R.string.start)
        tokenText.text = if (tokenVisible) getString(R.string.token_value, settings.token) else getString(R.string.token_value, "••••••••")
        showToken.text = if (tokenVisible) getString(R.string.hide) else getString(R.string.show)
        pairs.removeAllViews()
        val addresses = DeviceEnvironment.addresses(this)
        if (addresses.isEmpty()) text(pairs, getString(R.string.no_network))
        addresses.forEach { address ->
            val label = getString(when (address.priority) {
                0 -> R.string.lan
                1 -> R.string.tailscale
                else -> R.string.other
            })
            pairingRow(label, "http://${address.host}:${settings.port}")
        }
        if (settings.tunnelHost.isNotBlank() && settings.tunnelUser.isNotBlank() && settings.publicUrl.isNotBlank()) {
            pairingRow(getString(R.string.public_connection), settings.publicUrl)
        }
        tunnelStatusText.text = TunnelStatus.value.display(this)
        hostFingerprint.text = getString(R.string.host_fingerprint,
            (TunnelStatus.value.hostKeyFingerprint ?: settings.trustedHostFingerprint).ifEmpty { getString(R.string.not_trusted) })
        updateStatusText.text = AppUpdater.stateText(this)
        installUpdate.isEnabled = AppUpdater.available != null &&
            AppUpdater.state !is UpdateState.Downloading && AppUpdater.state !is UpdateState.Installing
        allowUpdates.visibility = if (packageManager.canRequestPackageInstalls()) android.view.View.GONE else android.view.View.VISIBLE
        storageState.text = permissionText(R.string.all_files, DeviceEnvironment.allFilesAccess(this))
        val notificationsAllowed = getSystemService(NotificationManager::class.java).areNotificationsEnabled() &&
            (Build.VERSION.SDK_INT < 33 || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)
        notificationState.text = permissionText(R.string.notifications, notificationsAllowed)
        batteryState.text = permissionText(R.string.battery, getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName))
        portText.text = getString(R.string.port_value, settings.port)
        updating = true
        boot.isChecked = settings.startOnBoot
        updating = false
    }

    private fun permissionText(label: Int, granted: Boolean): String = getString(R.string.permission_state,
        getString(label), getString(if (granted) R.string.granted else R.string.not_granted))

    private fun pairingRow(label: String, baseUrl: String) {
        text(pairs, getString(R.string.notification_status, label, baseUrl), 14f)
        val link = Pairing.uri(baseUrl, settings.token, DeviceEnvironment.deviceName)
        text(pairs, if (tokenVisible) link else Pairing.uri(baseUrl, "••••••••", DeviceEnvironment.deviceName), 13f)
            .apply { typeface = Typeface.MONOSPACE }
        button(pairs, getString(R.string.copy_link, label)) { copy(getString(R.string.pairing), link) }
    }

    private fun field(label: Int, initial: String, numeric: Boolean = false): EditText {
        text(content, getString(label))
        return EditText(this).apply {
            setText(initial)
            contentDescription = getString(label)
            inputType = if (numeric) InputType.TYPE_CLASS_NUMBER else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setSingleLine(true)
            content.addView(this, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
    }

    private fun connectionSection() {
        section(getString(R.string.connect_anywhere))
        toggle(getString(R.string.tunnel_enable), settings.tunnelEnabled) {
            settings.tunnelEnabled = it
            if (ServerStatus.value.running) ServerService.start(this, restart = true)
        }
        val host = field(R.string.ssh_host, settings.tunnelHost)
        val port = field(R.string.ssh_port, settings.tunnelPort.toString(), true)
        val user = field(R.string.ssh_user, settings.tunnelUser)
        val remote = field(R.string.remote_port, settings.tunnelRemotePort.toString(), true)
        val publicUrl = field(R.string.public_url, settings.publicUrl)
        button(content, getString(R.string.save_connection)) {
            val sshPort = port.text.toString().toIntOrNull()
            val remotePort = remote.text.toString().toIntOrNull()
            val url = publicUrl.text.toString().trim().trimEnd('/')
            val validUrl = url.isEmpty() || runCatching {
                val parsed = java.net.URI(url)
                parsed.scheme in listOf("http", "https") && !parsed.host.isNullOrEmpty() && parsed.rawQuery == null && parsed.rawFragment == null && parsed.userInfo == null
            }.getOrDefault(false)
            if (host.text.isBlank() || user.text.isBlank() || sshPort == null || sshPort !in 1..65535 || remotePort == null || remotePort !in 1..65535 || !validUrl) {
                Toast.makeText(this, R.string.connection_invalid, Toast.LENGTH_LONG).show()
            } else {
                settings.tunnelHost = host.text.toString().trim()
                settings.tunnelPort = sshPort
                settings.tunnelUser = user.text.toString().trim()
                settings.tunnelRemotePort = remotePort
                settings.publicUrl = url
                if (ServerStatus.value.running) ServerService.start(this, restart = true)
                refresh()
            }
        }
        text(content, getString(R.string.public_key))
        val keyText = text(content, "").apply { typeface = Typeface.MONOSPACE; setTextIsSelectable(true) }
        // Key generation may block on entropy; keep it off the UI thread.
        Thread({
            val key = runCatching { TunnelKeys.publicKey(applicationContext) }.getOrNull()
            runOnUiThread { keyText.text = key ?: getString(R.string.key_failed) }
        }, "tentacle-key").start()
        button(content, getString(R.string.copy)) {
            val key = keyText.text.toString()
            if (key.startsWith("ecdsa-")) copy(getString(R.string.public_key), key)
        }
        button(content, getString(R.string.copy_authorized)) {
            val key = keyText.text.toString()
            val remotePort = remote.text.toString().toIntOrNull()
            if (remotePort == null || remotePort !in 1..65535) {
                remote.error = getString(R.string.connection_invalid)
            } else if (key.startsWith("ecdsa-")) copy(getString(R.string.public_key),
                TunnelLogic.authorizedKeysLine(key, remotePort))
        }
        hostFingerprint = text(content, "")
        tunnelStatusText = text(content, "")
        button(content, getString(R.string.trust_new_key)) { ServerService.trustNewHostKey(this) }
    }

    private fun updatesSection() {
        section(getString(R.string.updates))
        val source = field(R.string.update_source, settings.updateSource)
        button(content, getString(R.string.save_update_source)) {
            settings.updateSource = source.text.toString().trim()
            AppUpdater.check(this, manual = true)
        }
        updateStatusText = text(content, "")
        button(content, getString(R.string.check_updates)) { AppUpdater.check(this, manual = true) }
        installUpdate = button(content, getString(R.string.install_update)) { AppUpdater.downloadAndInstall(this) }
        allowUpdates = button(content, getString(R.string.unknown_apps)) { AppUpdater.openUnknownSources(this) }
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
        val dialog = AlertDialog.Builder(this).setTitle(getString(R.string.port_title))
            .setView(input).setNegativeButton(getString(R.string.cancel), null).setPositiveButton(getString(R.string.save), null).create()
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
    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    private fun row(parent: LinearLayout) = LinearLayout(this).also { parent.addView(it) }
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
    private fun text(parent: LinearLayout, value: String, size: Float = 15f): TextView = TextView(this).apply {
        setTextAppearance(android.R.style.TextAppearance_DeviceDefault)
        text = value
        textSize = size
        setPadding(0, dp(4), 0, dp(4))
        parent.addView(this, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }
    private fun section(title: String) {
        text(content, title, 19f).apply { setPadding(0, dp(22), 0, dp(6)); setTypeface(null, Typeface.BOLD) }
    }
    private fun button(parent: LinearLayout, title: String, action: () -> Unit): Button = Button(this).apply {
        text = title
        isAllCaps = false
        setOnClickListener { action() }
        parent.addView(this, if (parent.orientation == LinearLayout.HORIZONTAL)
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            else LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }
    private fun permission(title: String, action: String, onClick: () -> Unit): TextView {
        val label = text(content, title)
        button(content, action, onClick)
        return label
    }
    private fun toggle(title: String, checked: Boolean, action: (Boolean) -> Unit): Switch = Switch(this).apply {
        text = title
        isChecked = checked
        setPadding(0, dp(10), 0, dp(10))
        setOnCheckedChangeListener { _, value -> if (!updating) action(value) }
        content.addView(this, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }
}
