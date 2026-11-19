package dev.tentacle.tunnel

import android.content.Context
import dev.tentacle.R
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.CopyOnWriteArraySet

object TunnelStatus {
    enum class Mode { OFF, CONNECTING, CONNECTED, ERROR }
    enum class Error { CONFIGURATION, CONNECTION, KEY_REJECTED, HOST_KEY_CHANGED, PORT_IN_USE }

    data class State(
        val mode: Mode = Mode.OFF,
        val connectedSinceMillis: Long? = null,
        val error: Error? = null,
        val hostKeyFingerprint: String? = null,
    ) {
        fun display(context: Context): String = when (mode) {
            Mode.OFF -> context.getString(R.string.tunnel_status_off)
            Mode.CONNECTING -> context.getString(R.string.tunnel_status_connecting)
            Mode.CONNECTED -> {
                val locale = context.resources.configuration.locales[0]
                val since = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, locale)
                    .format(Date(connectedSinceMillis ?: System.currentTimeMillis()))
                context.getString(R.string.tunnel_status_connected, since)
            }
            Mode.ERROR -> context.getString(when (error) {
                Error.CONFIGURATION -> R.string.tunnel_error_configuration
                Error.KEY_REJECTED -> R.string.tunnel_error_key_rejected
                Error.HOST_KEY_CHANGED -> R.string.tunnel_error_host_key_changed
                Error.PORT_IN_USE -> R.string.tunnel_error_port_in_use
                else -> R.string.tunnel_error_connection
            })
        }
    }

    private val listeners = CopyOnWriteArraySet<(State) -> Unit>()
    @Volatile var value = State()
        private set

    internal fun publish(state: State) {
        value = state
        listeners.forEach { it(state) }
    }

    fun observe(listener: (State) -> Unit): AutoCloseable {
        listeners.add(listener)
        listener(value)
        return AutoCloseable { listeners.remove(listener) }
    }
}
