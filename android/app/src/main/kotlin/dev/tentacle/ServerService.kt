package dev.tentacle

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import dev.tentacle.core.FileApi
import dev.tentacle.core.HttpServer
import dev.tentacle.core.PingInfo
import dev.tentacle.tailnet.TailnetManager
import dev.tentacle.tailnet.TailnetStatus
import dev.tentacle.tunnel.TunnelManager
import dev.tentacle.tunnel.TunnelStatus
import dev.tentacle.update.AppUpdater

class ServerService : Service() {
    private lateinit var settings: Settings
    private var tunnel: TunnelManager? = null
    private var tunnelSubscription: AutoCloseable? = null
    private var tailnet: TailnetManager? = null
    private var tailnetSubscription: AutoCloseable? = null
    private val updateCheck = object : Runnable {
        override fun run() {
            AppUpdater.check(this@ServerService)
            handler.postDelayed(this, 12 * 60 * 60 * 1000L)
        }
    }

    override fun attachBaseContext(base: Context) = super.attachBaseContext(AppLanguage.wrap(base))

    private var server: HttpServer? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private val handler = Handler(Looper.getMainLooper())
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    override fun onCreate() {
        super.onCreate()
        settings = Settings(this)
        AppLog.append("Server service created")
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.file_channel), NotificationManager.IMPORTANCE_LOW),
        )
        AppUpdater.initialize(this)
        handler.postDelayed(updateCheck, 12 * 60 * 60 * 1000L)
        tunnelSubscription = TunnelStatus.observe { state ->
            AppLog.append("SSH tunnel state=${state.mode} error=${state.error ?: "none"}")
            handler.post { if (server?.isRunning == true) updateNotification() }
        }
        tailnetSubscription = TailnetStatus.observe { state ->
            AppLog.append("Tailscale state=${state.mode} error=${state.error ?: "none"}")
            handler.post { if (server?.isRunning == true) updateNotification() }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        AppLog.append("Server service start action=${intent?.action ?: "start"}")
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            val notification = notification(getString(R.string.starting))
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else startForeground(NOTIFICATION_ID, notification)
            if (intent?.action == ACTION_RESTART) stopServer()
            if (server == null) {
                val api = FileApi(DeviceEnvironment.roots(this))
                val next = HttpServer(
                    port = settings.port,
                    tokenProvider = { settings.token },
                    api = api,
                    requestStore = RequestStores.get(this),
                    codeStore = dev.tentacle.codes.CodeStores.get(this),
                    phoneNotificationStoreProvider = { dev.tentacle.feed.PhoneNotificationStores.get(this) },
                    notifyStore = dev.tentacle.notify.PhoneNotifications.get(this),
                    questionStore = dev.tentacle.notify.PhoneNotifications.questions(this),
                    info = {
                        PingInfo(
                            version = BuildConfig.VERSION_NAME,
                            device = DeviceEnvironment.deviceName,
                            androidSdk = Build.VERSION.SDK_INT,
                            allFilesAccess = DeviceEnvironment.allFilesAccess(this),
                            roots = api.roots,
                            timezone = DeviceEnvironment.timezoneId,
                            tailnet = TailnetStatus.value.pingJson(),
                            lan = DeviceEnvironment.addresses(this).filter { it.priority == 0 }
                                .map { "http://${it.host}:${settings.port}" },
                            requests = mapOf("followup" to true, "retry" to true),
                            phoneNotifications = mapOf("enabled" to settings.phoneNotificationsEnabled),
                        )
                    },
                )
                next.accessLogger = { method, path, status, bytes, durationMs ->
                    AppLog.append("HTTP $method $path status=$status bytes=$bytes duration_ms=$durationMs")
                }
                server = next
                next.start()
                AppLog.append("File server started")
                settings.markStarted()
            }
            if (tunnel == null) {
                tunnel = TunnelManager(this, settings) {
                    handler.post { if (server?.isRunning == true) updateNotification() }
                }.also { it.start(settings.port) }
            }
            if (tailnet == null) {
                tailnet = TailnetManager(this, settings).also { it.start(settings.port) }
            }
            if (intent?.action == ACTION_TRUST_HOST) tunnel?.trustNewHostKey()
            if (intent?.action == ACTION_TAILNET_LOGIN) tailnet?.login()
            if (intent?.action == ACTION_TAILNET_LOGOUT) tailnet?.logout()
            updateLocks()
            ServerStatus.publish(ServerStatus.State(running = true))
            updateNotification()
            watchNetworks()
        } catch (e: Exception) {
            android.util.Log.w("tentacle", "File server failed to start: ${e.message}")
            AppLog.append("File server failed to start\n${e.stackTraceToString()}")
            ServerStatus.publish(ServerStatus.State(error = "server_failed"))
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    private fun watchNetworks() {
        if (networkCallback != null) return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = refresh("available")
            override fun onLost(network: Network) = refresh("lost")
            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) =
                refresh("link properties changed")
            private fun refresh(event: String) {
                AppLog.append("Network $event")
                handler.post { if (server?.isRunning == true) updateNotification() }
            }
        }
        getSystemService(ConnectivityManager::class.java).registerNetworkCallback(
            NetworkRequest.Builder()
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_TRUSTED)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
                .build(), callback,
        )
        networkCallback = callback
    }

    private fun updateNotification() {
        refreshNotificationChannel(this)
        val localized = AppLanguage.wrap(applicationContext)
        val host = DeviceEnvironment.addresses(this).firstOrNull()?.host ?: localized.getString(R.string.no_network_short)
        var status = localized.getString(R.string.notification_status,
            localized.getString(R.string.serving, host, settings.port), TunnelStatus.value.display(localized))
        if (TailnetStatus.value.mode != TailnetStatus.Mode.OFF) {
            status = localized.getString(R.string.notification_tailnet, status, TailnetStatus.value.display(localized))
        }
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(status))
    }

    private fun notification(message: String): Notification {
        val localized = AppLanguage.wrap(applicationContext)
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, ServerService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_server)
            .setContentTitle(localized.getString(R.string.app_name))
            .setContentText(message)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .addAction(Notification.Action.Builder(null, localized.getString(R.string.stop), stop).build())
            .build()
    }

    @Suppress("DEPRECATION")
    private fun updateLocks() {
        releaseLocks()
        if (!settings.keepAwake) return
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "tentacle:server").apply { acquire() }
        val mode = if (Build.VERSION.SDK_INT >= 29) WifiManager.WIFI_MODE_FULL_LOW_LATENCY
            else WifiManager.WIFI_MODE_FULL_HIGH_PERF
        wifiLock = applicationContext.getSystemService(WifiManager::class.java)
            .createWifiLock(mode, "tentacle:server").apply { acquire() }
    }

    private fun releaseLocks() {
        wifiLock?.let { if (it.isHeld) it.release() }
        wifiLock = null
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    private fun stopServer() {
        tunnel?.close()
        tunnel = null
        tailnet?.close()
        tailnet = null
        try { server?.stop() } finally {
            if (server != null) AppLog.append("File server stopped")
            server = null
            releaseLocks()
        }
    }

    override fun onDestroy() {
        AppLog.append("Server service stopping")
        dev.tentacle.notify.PhoneNotifications.stopAlarms(this)
        networkCallback?.let { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(it) }
        networkCallback = null
        tunnelSubscription?.close()
        tunnelSubscription = null
        tailnetSubscription?.close()
        tailnetSubscription = null
        handler.removeCallbacksAndMessages(null)
        stopServer()
        stopForeground(STOP_FOREGROUND_REMOVE)
        ServerStatus.publish(ServerStatus.State(error = ServerStatus.value.error))
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val ACTION_STOP = "dev.tentacle.STOP"
        private const val ACTION_TRUST_HOST = "dev.tentacle.TRUST_HOST"
        private const val ACTION_RESTART = "dev.tentacle.RESTART"
        private const val ACTION_TAILNET_LOGIN = "dev.tentacle.TAILNET_LOGIN"
        private const val ACTION_TAILNET_LOGOUT = "dev.tentacle.TAILNET_LOGOUT"
        private const val CHANNEL = "file_server"
        private const val NOTIFICATION_ID = 1
        fun refreshNotificationChannel(context: Context) {
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, AppLanguage.wrap(context).getString(R.string.file_channel),
                    NotificationManager.IMPORTANCE_LOW))
        }

        fun start(context: Context, restart: Boolean = false) {
            try {
                context.startForegroundService(Intent(context, ServerService::class.java).apply {
                    if (restart) action = ACTION_RESTART
                })
            } catch (e: Exception) {
                ServerStatus.publish(ServerStatus.State(error = "server_failed"))
            }
        }

        fun trustNewHostKey(context: Context) {
            if (ServerStatus.value.running) context.startForegroundService(
                Intent(context, ServerService::class.java).setAction(ACTION_TRUST_HOST))
        }

        fun tailnetLogin(context: Context) {
            if (ServerStatus.value.running) context.startForegroundService(
                Intent(context, ServerService::class.java).setAction(ACTION_TAILNET_LOGIN))
        }

        fun tailnetLogout(context: Context) {
            if (ServerStatus.value.running) context.startForegroundService(
                Intent(context, ServerService::class.java).setAction(ACTION_TAILNET_LOGOUT))
        }

        fun stop(context: Context) { context.stopService(Intent(context, ServerService::class.java)) }
    }
}
