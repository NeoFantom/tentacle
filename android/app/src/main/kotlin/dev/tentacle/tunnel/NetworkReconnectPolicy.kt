package dev.tentacle.tunnel

data class NetworkSnapshot(
    val id: Long,
    val interfaceName: String?,
    val addresses: Set<String>,
)

/** Callers serialize events and scheduled tasks, including [close]. */
class NetworkReconnectPolicy(
    initialNetwork: NetworkSnapshot?,
    private val scheduler: Scheduler,
    private val connection: () -> Connection,
    private val onDecision: (Decision) -> Unit,
) : AutoCloseable {
    fun interface Cancellation { fun cancel() }
    fun interface Scheduler {
        fun schedule(delayMillis: Long, task: () -> Unit): Cancellation
    }

    data class Connection(
        val network: NetworkSnapshot? = null,
        val connected: Boolean = false,
        val connecting: Boolean = false,
    )

    enum class Decision { UNCHANGED, PROBE, KEEP_ALIVE_PROBED, KEEP_CONNECTING, OFFLINE, RECONNECT }

    var currentNetwork: NetworkSnapshot? = initialNetwork
        private set
    private var settledNetwork = initialNetwork
    private val knownNetworks = mutableMapOf<Long, NetworkSnapshot>().apply {
        initialNetwork?.let { put(it.id, it) }
    }
    private var ownNetworkLost = false
    private var defaultNetworkLost = false
    private var ownPropertiesChanged = false
    private var pending: Cancellation? = null
    private var revision = 0L
    private var closed = false
    var probeRevision: Long? = null
        private set

    val canConnect: Boolean get() = !closed && currentNetwork != null && pending == null

    fun available(id: Long) {
        if (closed || currentNetwork?.id == id) return
        // Preserve known addresses until the matching link-properties callback arrives.
        currentNetwork = knownNetworks[id]
            ?: NetworkSnapshot(id, null, emptySet())
        debounce()
    }

    fun linkPropertiesChanged(snapshot: NetworkSnapshot) {
        if (closed || (currentNetwork?.id != snapshot.id && connection().network?.id != snapshot.id)) return
        if (knownNetworks[snapshot.id] == snapshot) return
        knownNetworks[snapshot.id] = snapshot
        if (currentNetwork?.id == snapshot.id) currentNetwork = snapshot
        else ownPropertiesChanged = true
        debounce()
    }

    /** Losing default status does not mean the network carrying the SSH socket is gone. */
    fun defaultLost(id: Long) {
        if (closed || currentNetwork?.id != id) return
        currentNetwork = null
        defaultNetworkLost = true
        debounce()
    }

    /** Called by the all-network observer, including for an old non-default network. */
    fun lost(id: Long) {
        if (closed) return
        knownNetworks.remove(id)
        val isOwnNetwork = connection().network?.id == id
        if (currentNetwork?.id != id && !isOwnNetwork) return
        if (currentNetwork?.id == id) {
            currentNetwork = null
            defaultNetworkLost = true
        }
        ownNetworkLost = ownNetworkLost || isOwnNetwork
        debounce()
    }

    private fun debounce() {
        pending?.cancel()
        val expectedRevision = ++revision
        pending = scheduler.schedule(NETWORK_DEBOUNCE_MILLIS) {
            if (!closed && revision == expectedRevision) {
                pending = null
                val state = connection()
                val decision = when {
                    // The default may disappear before a replacement is announced while
                    // the socket's own network is still usable. Only actual loss kills it.
                    currentNetwork == null && !ownNetworkLost && state.network != null &&
                        state.connected -> Decision.PROBE
                    currentNetwork == null && !ownNetworkLost && state.network != null &&
                        state.connecting -> Decision.KEEP_CONNECTING
                    currentNetwork == null -> Decision.OFFLINE
                    ownNetworkLost -> Decision.RECONNECT
                    currentNetwork == settledNetwork && !defaultNetworkLost && !ownPropertiesChanged &&
                        probeRevision == null -> Decision.UNCHANGED
                    state.connected -> Decision.PROBE
                    state.connecting && state.network == currentNetwork -> Decision.KEEP_CONNECTING
                    else -> Decision.RECONNECT
                }
                probeRevision = expectedRevision.takeIf { decision == Decision.PROBE }
                settledNetwork = currentNetwork
                ownNetworkLost = false
                defaultNetworkLost = false
                ownPropertiesChanged = false
                onDecision(decision)
            }
        }
    }

    fun isProbeCurrent(expectedRevision: Long): Boolean =
        !closed && revision == expectedRevision && probeRevision == expectedRevision

    /** A newer callback invalidates a probe even before its debounce has settled. */
    fun probeCompleted(expectedRevision: Long, answered: Boolean) {
        if (!isProbeCurrent(expectedRevision)) return
        probeRevision = null
        onDecision(if (answered) Decision.KEEP_ALIVE_PROBED else Decision.RECONNECT)
    }

    override fun close() {
        closed = true
        revision++
        probeRevision = null
        pending?.cancel()
        pending = null
    }

    companion object {
        const val NETWORK_DEBOUNCE_MILLIS = 400L
    }
}

/** Retry state belongs to one tunnel; time is monotonic and injectable for JVM tests. */
class PortConflictRetryPolicy(
    private val nowMillis: () -> Long,
    private val backoffMillis: (Int) -> Long = { TunnelLogic.backoffMillis(it) },
) {
    data class Retry(val delayMillis: Long, val showError: Boolean)

    private var replacedAt: Long? = null
    private var replacementPending = false
    private var attempt = 0

    fun reset() {
        replacedAt = null
        replacementPending = false
        resetBackoff()
    }

    fun resetBackoff() {
        attempt = 0
    }

    fun sessionReplaced() {
        replacementPending = true
        replacedAt = null
        resetBackoff()
    }

    /** Offline time must not consume grace; retries must not extend it. */
    fun connecting() {
        if (replacementPending) {
            replacedAt = nowMillis()
            replacementPending = false
        }
    }

    fun failure(portInUse: Boolean): Retry {
        val age = replacedAt?.let { nowMillis() - it }
        return if (portInUse && age != null && age in 0 until PORT_CONFLICT_GRACE_MILLIS) {
            Retry(PORT_CONFLICT_RETRY_MILLIS, showError = false)
        } else {
            Retry(backoffMillis(attempt++), showError = true)
        }
    }

    companion object {
        const val PORT_CONFLICT_RETRY_MILLIS = 1_000L
        const val PORT_CONFLICT_GRACE_MILLIS = 30_000L
    }
}

/** A direct-tcpip rejection is a live SSH reply, regardless of its reason code. */
data class SessionProbeResult(
    val sessionConnected: Boolean,
    val channelOpened: Boolean,
    val openFailureReason: Int = -1,
) {
    val answered: Boolean get() = sessionConnected && (channelOpened || openFailureReason >= 0)
}
