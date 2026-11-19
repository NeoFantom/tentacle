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

class ServerService : Service() {
    private lateinit var settings: Settings
    private var server: HttpServer? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private val handler = Handler(Looper.getMainLooper())
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    override fun onCreate() {
        super.onCreate()
        settings = Settings(this)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "File server", NotificationManager.IMPORTANCE_LOW),
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            val notification = notification("Starting file server…")
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
                    info = {
                        PingInfo(
                            version = BuildConfig.VERSION_NAME,
                            device = DeviceEnvironment.deviceName,
                            androidSdk = Build.VERSION.SDK_INT,
                            allFilesAccess = DeviceEnvironment.allFilesAccess(this),
                            roots = api.roots,
                            timezone = DeviceEnvironment.timezoneId,
                        )
                    },
                )
                server = next
                next.start()
                settings.markStarted()
            }
            updateLocks()
            ServerStatus.publish(ServerStatus.State(running = true))
            updateNotification()
            watchNetworks()
        } catch (e: Exception) {
            ServerStatus.publish(ServerStatus.State(error = e.message ?: e.javaClass.simpleName))
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    private fun watchNetworks() {
        if (networkCallback != null) return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = refresh()
            override fun onLost(network: Network) = refresh()
            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) = refresh()
            private fun refresh() { handler.post { if (server?.isRunning == true) updateNotification() } }
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
        val host = DeviceEnvironment.addresses(this).firstOrNull()?.host ?: "no network"
        getSystemService(NotificationManager::class.java).notify(
            NOTIFICATION_ID, notification("Serving on $host:${settings.port}"),
        )
    }

    private fun notification(message: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, ServerService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_server)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(message)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
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
        try { server?.stop() } finally {
            server = null
            releaseLocks()
        }
    }

    override fun onDestroy() {
        networkCallback?.let { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(it) }
        networkCallback = null
        handler.removeCallbacksAndMessages(null)
        stopServer()
        stopForeground(STOP_FOREGROUND_REMOVE)
        ServerStatus.publish(ServerStatus.State(error = ServerStatus.value.error))
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val ACTION_STOP = "dev.tentacle.STOP"
        private const val ACTION_RESTART = "dev.tentacle.RESTART"
        private const val CHANNEL = "file_server"
        private const val NOTIFICATION_ID = 1
        fun start(context: Context, restart: Boolean = false) {
            try {
                context.startForegroundService(Intent(context, ServerService::class.java).apply {
                    if (restart) action = ACTION_RESTART
                })
            } catch (e: Exception) {
                ServerStatus.publish(ServerStatus.State(error = e.message ?: e.javaClass.simpleName))
            }
        }

        fun stop(context: Context) { context.stopService(Intent(context, ServerService::class.java)) }
    }
}
