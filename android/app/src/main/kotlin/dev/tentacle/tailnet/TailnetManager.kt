package dev.tentacle.tailnet

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Log
import dev.tentacle.Settings
import dev.tentacle.tailnet.bridge.tsbridge.Tsbridge
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Runs the embedded Tailscale node (Go bridge, userspace tsnet) next to the
 * local HTTP server. It needs no VpnService, so it coexists with VPN/proxy
 * apps. Only the local server's port is reachable over the tailnet.
 *
 * The Go node is process-wide. All bridge calls of every manager run on one
 * shared background thread, so a replacement manager's start always follows
 * the previous manager's stop.
 */
class TailnetManager(
    context: Context,
    private val settings: Settings,
    private val onChanged: () -> Unit = {},
) : AutoCloseable {
    private val context = context.applicationContext
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private var poll: ScheduledFuture<*>? = null
    private var active = false
    private var callbackRegistered = false

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = networkChanged()
        override fun onLost(network: Network) = networkChanged()
        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) = networkChanged()
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) = Unit
    }

    fun start(localPort: Int) {
        executor.execute {
            if (!settings.tailnetEnabled) {
                stopNode()
                publish(TailnetStatus.State())
                return@execute
            }
            try {
                val stateDir = stateDirectory(context)
                val controlUrl = settings.tailnetControlUrl
                if (settings.tailnetStateControlUrl != controlUrl) {
                    // A node identity belongs to one control server; start fresh on a new one.
                    stateDir.deleteRecursively()
                    settings.tailnetStateControlUrl = controlUrl
                }
                Tsbridge.setInterfaces(interfacesJson())
                Tsbridge.start(stateDir.path, settings.tailnetHostname, controlUrl, settings.tailnetAuthKey, localPort.toLong())
                active = true
                registerCallback()
                publish(TailnetStatus.State(TailnetStatus.Mode.STARTING))
                schedulePoll(500)
            } catch (e: Exception) {
                Log.w(TAG, "Embedded Tailscale failed to start: ${e.message}")
                publish(TailnetStatus.State(TailnetStatus.Mode.ERROR, error = e.message))
            }
        }
    }

    fun login() = executor.execute {
        if (!active) return@execute
        runCatching { Tsbridge.login() }.onFailure { Log.w(TAG, "Login failed: ${it.message}") }
        schedulePoll(300)
    }

    fun logout() = executor.execute {
        if (!active) return@execute
        runCatching { Tsbridge.logout() }.onFailure { Log.w(TAG, "Logout failed: ${it.message}") }
        settings.tailnetAuthKey = "" // Do not log straight back in with a saved key.
        schedulePoll(300)
    }

    private fun networkChanged() = executor.execute {
        if (!active) return@execute
        try {
            Tsbridge.setInterfaces(interfacesJson())
            Tsbridge.networkChanged()
        } catch (e: Exception) {
            Log.w(TAG, "Network change not delivered: ${e.message}")
        }
        schedulePoll(1_000)
    }

    private fun schedulePoll(delayMillis: Long) {
        poll?.cancel(false)
        poll = executor.schedule({ pollStatus() }, delayMillis, TimeUnit.MILLISECONDS)
    }

    private fun pollStatus() {
        if (!active) return
        val state = try {
            TailnetLogic.parseStatus(Tsbridge.statusJSON())
        } catch (e: Exception) {
            TailnetStatus.State(TailnetStatus.Mode.ERROR, error = e.message)
        }
        if (state.mode == TailnetStatus.Mode.RUNNING && settings.tailnetAuthKey.isNotEmpty()) {
            // The node identity is now in the state directory; the key is no longer needed.
            settings.tailnetAuthKey = ""
        }
        publish(state)
        schedulePoll(if (state.mode == TailnetStatus.Mode.RUNNING) 10_000 else 2_000)
    }

    private fun publish(state: TailnetStatus.State) {
        val changed = state != TailnetStatus.value
        TailnetStatus.publish(state)
        if (changed) onChanged()
    }

    private fun registerCallback() {
        if (callbackRegistered) return
        connectivity.registerDefaultNetworkCallback(networkCallback)
        callbackRegistered = true
    }

    private fun stopNode() {
        poll?.cancel(false)
        poll = null
        if (callbackRegistered) {
            try { connectivity.unregisterNetworkCallback(networkCallback) } catch (_: IllegalArgumentException) {}
            callbackRegistered = false
        }
        if (active) {
            runCatching { Tsbridge.stop() }.onFailure { Log.w(TAG, "Stop failed: ${it.message}") }
            active = false
        }
    }

    override fun close() {
        executor.execute {
            stopNode()
            publish(TailnetStatus.State())
        }
    }

    /** Interfaces from java.net (Go cannot enumerate them on Android 11+) plus the default route. */
    private fun interfacesJson(): String {
        val interfaces = try {
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty().mapNotNull { nif ->
                runCatching {
                    TailnetLogic.Interface(
                        name = nif.name, index = nif.index, mtu = nif.mtu, up = nif.isUp,
                        loopback = nif.isLoopback, pointToPoint = nif.isPointToPoint,
                        multicast = nif.supportsMulticast(),
                        addresses = nif.interfaceAddresses.mapNotNull { address ->
                            address.address?.hostAddress?.let { it to address.networkPrefixLength.toInt() }
                        },
                    )
                }.getOrNull()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Cannot enumerate interfaces: ${e.message}")
            emptyList()
        }
        val properties = try {
            connectivity.activeNetwork?.let { connectivity.getLinkProperties(it) }
        } catch (e: SecurityException) {
            null
        }
        val gateway = properties?.routes?.firstOrNull { it.isDefaultRoute && it.gateway is Inet4Address }
            ?.gateway?.hostAddress.orEmpty()
        return TailnetLogic.interfacesJson(interfaces, properties?.interfaceName.orEmpty(), gateway)
    }

    companion object {
        private const val TAG = "tentacle"
        private val executor = Executors.newSingleThreadScheduledExecutor { task ->
            Thread(task, "tentacle-tailnet").apply { isDaemon = true }
        }

        /** App-private, excluded from backups; keeps the node identity across restarts. */
        fun stateDirectory(context: Context): File = File(context.noBackupFilesDir, "tailnet")
    }
}
