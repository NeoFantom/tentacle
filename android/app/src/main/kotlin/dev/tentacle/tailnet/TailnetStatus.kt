package dev.tentacle.tailnet

import android.content.Context
import dev.tentacle.R
import java.util.concurrent.CopyOnWriteArraySet

/** Process-local state of the embedded Tailscale node, observed by the UI and the service. */
object TailnetStatus {
    enum class Mode(val wire: String) {
        OFF("stopped"), STARTING("starting"), NEEDS_LOGIN("needs_login"), RUNNING("running"), ERROR("error")
    }

    data class State(
        val mode: Mode = Mode.OFF,
        val ip: String? = null,
        val dnsName: String? = null,
        val loginUrl: String? = null,
        val error: String? = null,
        val peers: Int = 0,
        val directPeers: Int = 0,
    ) {
        fun display(context: Context): String = when (mode) {
            Mode.OFF -> context.getString(R.string.tailnet_status_off)
            Mode.STARTING -> context.getString(R.string.tailnet_status_starting)
            Mode.NEEDS_LOGIN -> if (error != null) context.getString(R.string.tailnet_status_needs_approval)
                else context.getString(R.string.tailnet_status_needs_login)
            Mode.RUNNING -> if (ip != null) context.getString(R.string.tailnet_status_connected, ip)
                else context.getString(R.string.tailnet_status_starting)
            Mode.ERROR -> context.getString(R.string.tailnet_status_error, error ?: "")
        }

        /** Read-only summary for `/v1/ping`; never includes keys or login URLs. */
        fun pingJson(): Map<String, Any>? = if (mode == Mode.OFF) null else linkedMapOf<String, Any>(
            "state" to mode.wire,
        ).apply { if (mode == Mode.RUNNING && ip != null) put("ip", ip) }
    }

    private val listeners = CopyOnWriteArraySet<(State) -> Unit>()
    @Volatile var value = State()
        private set

    internal fun publish(state: State) {
        if (state == value) return
        value = state
        listeners.forEach { it(state) }
    }

    fun observe(listener: (State) -> Unit): AutoCloseable {
        listeners.add(listener)
        listener(value)
        return AutoCloseable { listeners.remove(listener) }
    }
}
