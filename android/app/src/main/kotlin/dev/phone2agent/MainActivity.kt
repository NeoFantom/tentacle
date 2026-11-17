package dev.phone2agent

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
    private var tokenVisible = false
    private var updating = false
    private var registered = false
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) { refresh() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = Settings(this)
        buildScreen()
        handleDebugIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleDebugIntent(intent)
    }

    private fun handleDebugIntent(intent: Intent) {
        if (BuildConfig.DEBUG && intent.getBooleanExtra("autostart", false)) ServerService.start(this)
    }

    override fun onResume() {
        super.onResume()
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(receiver, IntentFilter(ServerService.ACTION_STATUS), RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(receiver, IntentFilter(ServerService.ACTION_STATUS))
        }
        registered = true
        refresh()
    }

    override fun onPause() {
        if (registered) unregisterReceiver(receiver)
        registered = false
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
        text(content, "phone2agent", 28f).setTypeface(null, Typeface.BOLD)
        text(content, "Your files, available to your computer.")
        section("Server")
        statusText = text(content, "Stopped", 18f)
        startStop = button(content, "Start") {
            if (ServerService.status.running) ServerService.stop(this) else ServerService.start(this)
            refresh()
        }
        section("Pairing")
        text(content, "Copy a pairing link to p2a pair on your computer.")
        tokenText = text(content, "").apply { typeface = Typeface.MONOSPACE }
        val tokenActions = row(content)
        showToken = button(tokenActions, "Show") { tokenVisible = !tokenVisible; refresh() }
        button(tokenActions, "Copy token") { copy("phone2agent token", settings.token) }
        button(tokenActions, "Regenerate") {
            AlertDialog.Builder(this).setTitle("Regenerate token?")
                .setMessage("Existing clients will lose access immediately. Pair your computer again with the new token.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Regenerate") { _, _ -> settings.regenerateToken(); refresh() }.show()
        }
        pairs = column().also { content.addView(it) }
        section("Permissions")
        storageState = permission("All files access", "Open settings") { requestStorage() }
        notificationState = permission("Notifications", "Allow / settings") { requestNotifications() }
        batteryState = permission("Battery optimisation exemption", "Open settings") {
            launch(Intent(SystemSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, packageUri()),
                Intent(SystemSettings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS), appDetails())
        }
        if (isXiaomi()) {
            permission("Autostart: can't be detected — check manually", "Check manually") {
                launch(Intent().setComponent(ComponentName("com.miui.securitycenter",
                    "com.miui.permcenter.autostart.AutoStartManagementActivity")), appDetails())
            }
        }
        section("Settings")
        portText = text(content, "")
        button(content, "Change port") { changePort() }
        boot = toggle("Start on boot", settings.startOnBoot) { settings.startOnBoot = it }
        toggle("Keep awake while serving", settings.keepAwake) {
            settings.keepAwake = it
            if (ServerService.status.running) ServerService.start(this)
        }
        text(content, "Keep awake uses more battery. Wi-Fi lock behaviour depends on Android and the device.")
        section("phone2agent ${BuildConfig.VERSION_NAME}")
        text(content, "Read-only; anyone with the token on the same network can read your files; prefer Tailscale or a trusted Wi-Fi.")
    }

    private fun refresh() {
        if (!::statusText.isInitialized) return
        val state = ServerService.status
        statusText.text = when {
            state.error != null -> "Error: ${state.error}"
            state.running -> "Running on port ${settings.port}"
            else -> "Stopped"
        }
        startStop.text = if (state.running) "Stop" else "Start"
        tokenText.text = if (tokenVisible) "Token: ${settings.token}" else "Token: ••••••••"
        showToken.text = if (tokenVisible) "Hide" else "Show"
        pairs.removeAllViews()
        val addresses = DeviceEnvironment.addresses(this)
        if (addresses.isEmpty()) text(pairs, "No network address. Connect to Wi-Fi, Ethernet or a VPN.")
        addresses.forEach { address ->
            text(pairs, "${address.label} · ${address.host}", 14f)
            val link = Pairing.uri(address.host, settings.port, settings.token, DeviceEnvironment.deviceName)
            text(pairs, if (tokenVisible) link else
                Pairing.uri(address.host, settings.port, "••••••••", DeviceEnvironment.deviceName), 13f)
                .apply { typeface = Typeface.MONOSPACE }
            button(pairs, "Copy ${address.label} link") { copy("phone2agent pairing link", link) }
        }
        storageState.text = "All files access: " +
            if (DeviceEnvironment.allFilesAccess(this)) "granted" else "not granted"
        val notificationsAllowed = getSystemService(NotificationManager::class.java).areNotificationsEnabled() &&
            (Build.VERSION.SDK_INT < 33 || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)
        notificationState.text = "Notifications: " + if (notificationsAllowed) "granted" else "not granted"
        batteryState.text = "Battery optimisation exemption: " +
            if (getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)) "granted" else "not granted"
        portText.text = "Port: ${settings.port}"
        updating = true
        boot.isChecked = settings.startOnBoot
        updating = false
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
            contentDescription = "Server port"
        }
        val dialog = AlertDialog.Builder(this).setTitle("Server port (1024–65535)")
            .setView(input).setNegativeButton("Cancel", null).setPositiveButton("Save", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val port = input.text.toString().toIntOrNull()
                if (port == null || port !in 1024..65535) input.error = "Enter a port between 1024 and 65535"
                else {
                    val changed = port != settings.port
                    settings.port = port
                    if (changed && ServerService.status.running) ServerService.start(this, restart = true)
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
        Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
    }

    private fun launch(vararg intents: Intent) {
        for (intent in intents) {
            try { startActivity(intent); return } catch (_: Exception) { /* Try the next settings page. */ }
        }
        Toast.makeText(this, "Settings page unavailable on this device", Toast.LENGTH_LONG).show()
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
