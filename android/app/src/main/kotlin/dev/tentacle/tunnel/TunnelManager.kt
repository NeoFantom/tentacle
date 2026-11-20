package dev.tentacle.tunnel

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import com.jcraft.jsch.HostKey
import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import com.jcraft.jsch.UserInfo
import dev.tentacle.Settings
import java.io.File
import java.security.MessageDigest
import java.util.Base64
import java.util.Properties
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

class TunnelManager(
    context: Context,
    private val settings: Settings,
    private val onChanged: () -> Unit = {},
) : AutoCloseable {
    private val context = context.applicationContext
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val lock = Any()
    private val executor = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "tentacle-ssh-tunnel").apply { isDaemon = true }
    }
    private var scheduled: ScheduledFuture<*>? = null
    private var session: Session? = null
    private var connectingSession: Session? = null
    private var localPort = -1
    private var active = false
    private var generation = 0L
    private var attempt = 0
    private var callbackRegistered = false
    private var defaultNetwork: Network? = null

    // A new default network (Wi-Fi <-> mobile) kills the old TCP connection:
    // reconnect at once with the backoff reset instead of waiting for keepalives.
    // Registration reports the current network immediately; that is not a change.
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            val changed = synchronized(lock) { (defaultNetwork != network).also { defaultNetwork = network } }
            if (changed) retry()
        }
        override fun onLost(network: Network) {
            synchronized(lock) { if (defaultNetwork == network) defaultNetwork = null }
            retry()
        }
    }

    fun start(localPort: Int) {
        synchronized(lock) {
            this.localPort = localPort
            active = settings.tunnelEnabled
            generation++
            attempt = 0
            cancelLocked()
            if (!active) {
                unregisterNetworkCallbackLocked()
                publish(TunnelStatus.State())
                return
            }
            if (!callbackRegistered) {
                defaultNetwork = connectivity.activeNetwork
                connectivity.registerDefaultNetworkCallback(networkCallback)
                callbackRegistered = true
            }
            pendingHostKeyChange()?.let {
                publish(TunnelStatus.State(
                    TunnelStatus.Mode.ERROR,
                    error = TunnelStatus.Error.HOST_KEY_CHANGED,
                    hostKeyFingerprint = it.fingerprint,
                ))
                return
            }
            scheduleLocked(0, generation)
        }
    }

    fun retry() {
        synchronized(lock) {
            if (!active || isHostKeyBlocked()) return
            generation++
            attempt = 0
            cancelLocked()
            scheduleLocked(0, generation)
        }
    }

    fun trustNewHostKey() {
        val pending = readPending() ?: return
        if (pending.endpoint != currentEndpoint()) return
        settings.trustHostKey(pending.endpoint, pending.key, pending.fingerprint)
        pendingFile().delete()
        retry()
    }

    override fun close() {
        synchronized(lock) {
            active = false
            generation++
            cancelLocked()
            unregisterNetworkCallbackLocked()
        }
        executor.shutdownNow()
        publish(TunnelStatus.State())
    }

    private fun connect(expectedGeneration: Long) {
        synchronized(lock) {
            if (!active || generation != expectedGeneration || isHostKeyBlocked()) return
        }
        val host = settings.tunnelHost.trim()
        val user = settings.tunnelUser.trim()
        if (host.isEmpty() || user.isEmpty() || localPort !in 1..65535 ||
            settings.tunnelPort !in 1..65535 || settings.tunnelRemotePort !in 1..65535
        ) {
            publishIfCurrent(expectedGeneration,
                TunnelStatus.State(TunnelStatus.Mode.ERROR, error = TunnelStatus.Error.CONFIGURATION))
            return
        }
        publishIfCurrent(expectedGeneration, TunnelStatus.State(TunnelStatus.Mode.CONNECTING))
        var next: Session? = null
        var changed = false
        try {
            val endpoint = endpoint(host, settings.tunnelPort)
            val jsch = JSch()
            jsch.addIdentity(TunnelKeys.privateKeyPath(context))
            jsch.hostKeyRepository = PinningRepository(endpoint) { changed = true }
            next = jsch.getSession(user, host, settings.tunnelPort)
            synchronized(lock) {
                if (!active || generation != expectedGeneration) return
                connectingSession = next
            }
            next.apply {
                setConfig("StrictHostKeyChecking", "yes")
                setConfig("PreferredAuthentications", "publickey")
                setServerAliveInterval(30_000)
                setServerAliveCountMax(3)
                connect(15_000)
                setPortForwardingR("127.0.0.1", settings.tunnelRemotePort, "127.0.0.1", localPort)
            }
            synchronized(lock) {
                if (!active || generation != expectedGeneration) {
                    next.disconnect()
                    return
                }
                connectingSession = null
                session = next
                attempt = 0
                scheduled = executor.schedule({ checkConnected(expectedGeneration) }, 5, TimeUnit.SECONDS)
            }
            publishIfCurrent(expectedGeneration, TunnelStatus.State(
                TunnelStatus.Mode.CONNECTED,
                System.currentTimeMillis(),
                hostKeyFingerprint = settings.trustedHostFingerprint,
            ))
        } catch (e: Exception) {
            next?.disconnect()
            synchronized(lock) {
                if (connectingSession === next) connectingSession = null
                if (!active || generation != expectedGeneration) return
            }
            val error = when {
                changed -> TunnelStatus.Error.HOST_KEY_CHANGED
                e.message.orEmpty().contains("Auth fail", ignoreCase = true) -> TunnelStatus.Error.KEY_REJECTED
                e.message.orEmpty().contains("remote port forwarding failed", ignoreCase = true) -> TunnelStatus.Error.PORT_IN_USE
                else -> TunnelStatus.Error.CONNECTION
            }
            publishIfCurrent(expectedGeneration, TunnelStatus.State(
                TunnelStatus.Mode.ERROR,
                error = error,
                hostKeyFingerprint = pendingHostKeyChange()?.fingerprint ?: settings.trustedHostFingerprint,
            ))
            if (error != TunnelStatus.Error.HOST_KEY_CHANGED) scheduleReconnect(expectedGeneration)
        }
    }

    private fun checkConnected(expectedGeneration: Long) {
        synchronized(lock) {
            if (!active || generation != expectedGeneration) return
            if (session?.isConnected == true) {
                scheduled = executor.schedule({ checkConnected(expectedGeneration) }, 5, TimeUnit.SECONDS)
                return
            }
        }
        publishIfCurrent(expectedGeneration,
            TunnelStatus.State(TunnelStatus.Mode.ERROR, error = TunnelStatus.Error.CONNECTION))
        scheduleReconnect(expectedGeneration)
    }

    private fun scheduleReconnect(expectedGeneration: Long) {
        synchronized(lock) {
            if (!active || generation != expectedGeneration) return
            val delay = TunnelLogic.backoffMillis(attempt++)
            scheduled = executor.schedule({ connect(expectedGeneration) }, delay, TimeUnit.MILLISECONDS)
        }
    }

    private fun scheduleLocked(delayMillis: Long, expectedGeneration: Long) {
        scheduled = executor.schedule({ connect(expectedGeneration) }, delayMillis, TimeUnit.MILLISECONDS)
    }

    private fun cancelLocked() {
        scheduled?.cancel(true)
        scheduled = null
        connectingSession?.disconnect()
        connectingSession = null
        session?.disconnect()
        session = null
    }

    private fun isHostKeyBlocked(): Boolean = pendingHostKeyChange() != null

    private fun pendingHostKeyChange(): PendingHostKey? = readPending()?.takeIf {
        it.endpoint == currentEndpoint() && it.key != settings.trustedHostKey
    }

    private fun currentEndpoint(): String = endpoint(settings.tunnelHost.trim(), settings.tunnelPort)

    private fun unregisterNetworkCallbackLocked() {
        if (!callbackRegistered) return
        try { connectivity.unregisterNetworkCallback(networkCallback) } catch (_: IllegalArgumentException) {}
        callbackRegistered = false
    }

    private fun publish(state: TunnelStatus.State) {
        TunnelStatus.publish(state)
        onChanged()
    }

    private fun publishIfCurrent(expectedGeneration: Long, state: TunnelStatus.State) {
        synchronized(lock) {
            if (!active || generation != expectedGeneration) return
            publish(state)
        }
    }

    private inner class PinningRepository(
        private val endpoint: String,
        private val changed: () -> Unit,
    ) : HostKeyRepository {
        override fun check(host: String, keyBytes: ByteArray): Int {
            val key = Base64.getEncoder().encodeToString(keyBytes)
            val fingerprint = sha256Fingerprint(keyBytes)
            return when (TunnelLogic.hostKeyDecision(
                endpoint, key, settings.trustedHostEndpoint, settings.trustedHostKey,
            )) {
                TunnelLogic.HostKeyDecision.TRUSTED -> HostKeyRepository.OK
                TunnelLogic.HostKeyDecision.TRUST_FIRST -> {
                    settings.trustHostKey(endpoint, key, fingerprint)
                    HostKeyRepository.OK
                }
                TunnelLogic.HostKeyDecision.CHANGED -> {
                    writePending(PendingHostKey(endpoint, key, fingerprint))
                    changed()
                    HostKeyRepository.CHANGED
                }
            }
        }

        override fun add(hostkey: HostKey?, ui: UserInfo?) = Unit
        override fun remove(host: String?, type: String?) = Unit
        override fun remove(host: String?, type: String?, key: ByteArray?) = Unit
        override fun getKnownHostsRepositoryID(): String = "Tentacle host key pin"
        override fun getHostKey(): Array<HostKey> = emptyArray()
        override fun getHostKey(host: String?, type: String?): Array<HostKey> = emptyArray()
    }

    private data class PendingHostKey(val endpoint: String, val key: String, val fingerprint: String)

    private fun writePending(value: PendingHostKey) {
        val target = pendingFile()
        val temporary = File(target.parentFile, "${target.name}.tmp")
        temporary.outputStream().use { output ->
            Properties().apply {
                setProperty("endpoint", value.endpoint)
                setProperty("key", value.key)
                setProperty("fingerprint", value.fingerprint)
            }.store(output, null)
            output.fd.sync()
        }
        check(temporary.renameTo(target)) { "Cannot save pending SSH host key" }
    }

    private fun readPending(): PendingHostKey? = try {
        pendingFile().takeIf(File::isFile)?.inputStream()?.use { input ->
            Properties().apply { load(input) }
        }?.let { PendingHostKey(it.getProperty("endpoint"), it.getProperty("key"), it.getProperty("fingerprint")) }
    } catch (_: Exception) { null }

    private fun pendingFile() = File(context.filesDir, "pending_tunnel_host_key")

    private fun endpoint(host: String, port: Int) = "$host:$port"

    private fun sha256Fingerprint(key: ByteArray): String = "SHA256:" + Base64.getEncoder()
        .withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(key))
}
