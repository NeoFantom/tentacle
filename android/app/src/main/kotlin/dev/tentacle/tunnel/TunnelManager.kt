package dev.tentacle.tunnel

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.SystemClock
import android.util.Log
import com.jcraft.jsch.ChannelDirectTCPIP
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
    // Network decisions must not wait behind a blocking SSH connection attempt.
    private val networkExecutor = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "tentacle-ssh-network").apply { isDaemon = true }
    }
    private var scheduled: ScheduledFuture<*>? = null
    private var session: Session? = null
    private var connectingSession: Session? = null
    private var localPort = -1
    private var active = false
    private var generation = 0L
    private val retryPolicy = PortConflictRetryPolicy(SystemClock::elapsedRealtime)
    private var callbackRegistered = false
    private var networkObserverRegistered = false
    private var networkPolicy: NetworkReconnectPolicy? = null
    private var connectionNetwork: NetworkSnapshot? = null
    private var connecting = false
    private var connectDeferred = false

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            synchronized(lock) {
                if (active && callbackRegistered) networkPolicy?.available(network.networkHandle)
            }
        }
        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
            synchronized(lock) {
                if (active && callbackRegistered) {
                    networkPolicy?.linkPropertiesChanged(snapshot(network, linkProperties))
                }
            }
        }
        override fun onLost(network: Network) {
            synchronized(lock) {
                if (active && callbackRegistered) networkPolicy?.defaultLost(network.networkHandle)
            }
        }
    }

    // Default callbacks also report loss of default status while the old network stays up.
    // Observe actual network loss separately so a surviving session can still be probed.
    private val networkObserver = object : ConnectivityManager.NetworkCallback() {
        override fun onLost(network: Network) {
            synchronized(lock) {
                if (active && networkObserverRegistered) networkPolicy?.lost(network.networkHandle)
            }
        }
        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
            synchronized(lock) {
                if (active && networkObserverRegistered && connectionNetwork?.id == network.networkHandle) {
                    networkPolicy?.linkPropertiesChanged(snapshot(network, linkProperties))
                }
            }
        }
    }

    fun start(localPort: Int) {
        synchronized(lock) {
            this.localPort = localPort
            active = settings.tunnelEnabled
            generation++
            retryPolicy.reset()
            cancelLocked()
            if (!active) {
                unregisterNetworkCallbackLocked()
                publish(TunnelStatus.State())
                return
            }
            networkPolicy?.close()
            // Subscribe before sampling so a loss during initialization cannot be missed.
            // Callback threads acquire this lock after the baseline has been installed.
            if (!callbackRegistered) {
                connectivity.registerDefaultNetworkCallback(networkCallback)
                callbackRegistered = true
            }
            if (!networkObserverRegistered) {
                connectivity.registerNetworkCallback(
                    NetworkRequest.Builder()
                        .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                        .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                        .build(),
                    networkObserver,
                )
                networkObserverRegistered = true
            }
            val initialNetwork = connectivity.activeNetwork?.let {
                connectivity.getLinkProperties(it)?.let { properties -> snapshot(it, properties) }
            }
            networkPolicy = NetworkReconnectPolicy(
                initialNetwork,
                NetworkReconnectPolicy.Scheduler { delay, task ->
                    val future = networkExecutor.schedule({ synchronized(lock) { task() } }, delay, TimeUnit.MILLISECONDS)
                    NetworkReconnectPolicy.Cancellation { future.cancel(false) }
                },
                { NetworkReconnectPolicy.Connection(connectionNetwork, session?.isConnected == true, connecting) },
                ::networkDecisionLocked,
            )
            pendingHostKeyChange()?.let {
                publish(TunnelStatus.State(
                    TunnelStatus.Mode.ERROR,
                    error = TunnelStatus.Error.HOST_KEY_CHANGED,
                    hostKeyFingerprint = it.fingerprint,
                ))
                return
            }
            if (networkPolicy?.canConnect == true) {
                scheduleLocked(0, generation)
            } else {
                networkDecisionLocked(NetworkReconnectPolicy.Decision.OFFLINE)
            }
        }
    }

    fun retry() {
        synchronized(lock) {
            if (!active || isHostKeyBlocked()) return
            generation++
            retryPolicy.reset()
            cancelLocked()
            if (networkPolicy?.canConnect == true) scheduleLocked(0, generation)
            else connectDeferred = true
        }
    }

    private fun snapshot(network: Network, properties: LinkProperties) = NetworkSnapshot(
        network.networkHandle,
        properties.interfaceName,
        properties.linkAddresses.map { it.toString() }.toSet(),
    )

    private fun networkDecisionLocked(decision: NetworkReconnectPolicy.Decision) {
        Log.i("tentacle", "SSH network decision=$decision network=${networkPolicy?.currentNetwork?.id} generation=$generation")
        if (!active || isHostKeyBlocked()) return
        when (decision) {
            NetworkReconnectPolicy.Decision.OFFLINE -> {
                generation++
                retryPolicy.resetBackoff()
                // Tear down now, while a best-effort SSH disconnect may still free the remote port.
                if (session != null || connectingSession?.isConnected == true) retryPolicy.sessionReplaced()
                cancelLocked()
                connectDeferred = true
                publish(TunnelStatus.State(TunnelStatus.Mode.ERROR, error = TunnelStatus.Error.CONNECTION))
            }
            NetworkReconnectPolicy.Decision.PROBE -> {
                val policy = networkPolicy ?: return
                val revision = policy.probeRevision ?: return
                val current = session
                generation++
                cancelAttemptsLocked()
                val expectedGeneration = generation
                scheduled = executor.schedule(
                    { probeSession(current, policy, revision, expectedGeneration) }, 0, TimeUnit.MILLISECONDS,
                )
            }
            NetworkReconnectPolicy.Decision.KEEP_ALIVE_PROBED -> {
                // A successful probe does not migrate the socket to the new default network.
                connectDeferred = false
                val expectedGeneration = generation
                scheduled = executor.schedule({ checkConnected(expectedGeneration) }, 5, TimeUnit.SECONDS)
            }
            NetworkReconnectPolicy.Decision.RECONNECT -> {
                retryPolicy.resetBackoff()
                if (session != null || connectingSession?.isConnected == true) retryPolicy.sessionReplaced()
                generation++
                cancelLocked()
                if (networkPolicy?.canConnect == true) scheduleLocked(0, generation)
                else connectDeferred = true
            }
            else -> if (connectDeferred) {
                connectDeferred = false
                if (!connecting && session?.isConnected != true) scheduleLocked(0, generation)
            }
        }
    }

    private fun probeSession(
        current: Session?,
        policy: NetworkReconnectPolicy,
        revision: Long,
        expectedGeneration: Long,
    ) {
        synchronized(lock) {
            if (!active || generation != expectedGeneration || networkPolicy !== policy || !policy.isProbeCurrent(revision)) return
        }
        Log.i("tentacle", "SSH probe start generation=$expectedGeneration")
        var channel: ChannelDirectTCPIP? = null
        var opened = false
        try {
            if (current?.isConnected == true) {
                channel = current.openChannel("direct-tcpip") as ChannelDirectTCPIP
                channel.setHost("127.0.0.1")
                channel.setPort(9)
                // No input stream: JSch performs the channel-open round trip synchronously.
                channel.connect(SESSION_PROBE_TIMEOUT_MILLIS)
                opened = channel.isConnected
            }
        } catch (_: Exception) {
            // JSch 0.2.26 Session.run stores SSH_MSG_CHANNEL_OPEN_FAILURE's reason in
            // Channel.exitstatus (public getExitStatus); timeout/I/O leaves it at -1.
        }
        val failureReason = channel?.exitStatus ?: -1
        val answered = SessionProbeResult(current?.isConnected == true, opened, failureReason).answered
        channel?.disconnect()
        synchronized(lock) {
            val cancelled = !active || generation != expectedGeneration || networkPolicy !== policy ||
                !policy.isProbeCurrent(revision)
            val result = when {
                cancelled -> "CANCELLED"
                answered && opened -> "ANSWERED_OPEN"
                answered -> "ANSWERED_FAILURE"
                else -> "NO_ANSWER"
            }
            Log.i("tentacle", "SSH probe result=$result generation=$expectedGeneration reason=$failureReason")
            if (!cancelled) policy.probeCompleted(revision, answered)
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
        networkExecutor.shutdownNow()
        publish(TunnelStatus.State())
    }

    private fun connect(expectedGeneration: Long) {
        synchronized(lock) {
            if (!active || generation != expectedGeneration || isHostKeyBlocked()) return
            if (networkPolicy?.canConnect != true) {
                connectDeferred = true
                return
            }
            if (connecting) return
            connecting = true
            retryPolicy.connecting()
            connectDeferred = false
            connectionNetwork = networkPolicy?.currentNetwork
            Log.i("tentacle", "SSH connect start generation=$expectedGeneration network=${connectionNetwork?.id}")
        }
        val host = settings.tunnelHost.trim()
        val user = settings.tunnelUser.trim()
        if (host.isEmpty() || user.isEmpty() || localPort !in 1..65535 ||
            settings.tunnelPort !in 1..65535 || settings.tunnelRemotePort !in 1..65535
        ) {
            synchronized(lock) { if (generation == expectedGeneration) connecting = false }
            Log.i("tentacle", "SSH connect failure generation=$expectedGeneration error=CONFIGURATION")
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
                if (!active || generation != expectedGeneration) {
                    next.disconnect()
                    Log.i("tentacle", "SSH connect failure generation=$expectedGeneration error=CANCELLED")
                    return
                }
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
                    Log.i("tentacle", "SSH connect failure generation=$expectedGeneration error=CANCELLED")
                    return
                }
                connectingSession = null
                connecting = false
                session = next
                retryPolicy.reset()
                scheduled = executor.schedule({ checkConnected(expectedGeneration) }, 5, TimeUnit.SECONDS)
                Log.i("tentacle", "SSH connect success generation=$expectedGeneration network=${connectionNetwork?.id}")
            }
            publishIfCurrent(expectedGeneration, TunnelStatus.State(
                TunnelStatus.Mode.CONNECTED,
                System.currentTimeMillis(),
                hostKeyFingerprint = settings.trustedHostFingerprint,
            ))
        } catch (e: Exception) {
            next?.disconnect()
            val error = when {
                changed -> TunnelStatus.Error.HOST_KEY_CHANGED
                e.message.orEmpty().contains("Auth fail", ignoreCase = true) -> TunnelStatus.Error.KEY_REJECTED
                e.message.orEmpty().contains("remote port forwarding failed", ignoreCase = true) -> TunnelStatus.Error.PORT_IN_USE
                else -> TunnelStatus.Error.CONNECTION
            }
            Log.i("tentacle", "SSH connect failure generation=$expectedGeneration error=$error")
            synchronized(lock) {
                if (connectingSession === next) connectingSession = null
                if (!active || generation != expectedGeneration) return
                connecting = false
                val retry = if (error != TunnelStatus.Error.HOST_KEY_CHANGED) {
                    retryPolicy.failure(error == TunnelStatus.Error.PORT_IN_USE)
                } else null
                publish(TunnelStatus.State(
                    if (retry?.showError == false) TunnelStatus.Mode.CONNECTING else TunnelStatus.Mode.ERROR,
                    error = error.takeUnless { retry?.showError == false },
                    hostKeyFingerprint = pendingHostKeyChange()?.fingerprint ?: settings.trustedHostFingerprint,
                ))
                if (retry != null) scheduleReconnect(expectedGeneration, retry.delayMillis)
            }
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

    private fun scheduleReconnect(expectedGeneration: Long, delayMillis: Long? = null) {
        synchronized(lock) {
            if (!active || generation != expectedGeneration) return
            if (networkPolicy?.canConnect != true) {
                connectDeferred = true
                return
            }
            val delay = delayMillis ?: retryPolicy.failure(portInUse = false).delayMillis
            scheduled = executor.schedule({ connect(expectedGeneration) }, delay, TimeUnit.MILLISECONDS)
        }
    }

    private fun scheduleLocked(delayMillis: Long, expectedGeneration: Long) {
        scheduled = executor.schedule({ connect(expectedGeneration) }, delayMillis, TimeUnit.MILLISECONDS)
    }

    private fun cancelAttemptsLocked() {
        scheduled?.cancel(true)
        scheduled = null
        connectingSession?.disconnect()
        connectingSession = null
        connecting = false
        connectDeferred = false
    }

    private fun cancelLocked() {
        cancelAttemptsLocked()
        connectionNetwork = null
        session?.disconnect()
        session = null
    }

    companion object {
        const val SESSION_PROBE_TIMEOUT_MILLIS = 2_000
    }

    private fun isHostKeyBlocked(): Boolean = pendingHostKeyChange() != null

    private fun pendingHostKeyChange(): PendingHostKey? = readPending()?.takeIf {
        it.endpoint == currentEndpoint() && it.key != settings.trustedHostKey
    }

    private fun currentEndpoint(): String = endpoint(settings.tunnelHost.trim(), settings.tunnelPort)

    private fun unregisterNetworkCallbackLocked() {
        networkPolicy?.close()
        networkPolicy = null
        if (callbackRegistered) {
            try { connectivity.unregisterNetworkCallback(networkCallback) } catch (_: IllegalArgumentException) {}
            callbackRegistered = false
        }
        if (networkObserverRegistered) {
            try { connectivity.unregisterNetworkCallback(networkObserver) } catch (_: IllegalArgumentException) {}
            networkObserverRegistered = false
        }
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
