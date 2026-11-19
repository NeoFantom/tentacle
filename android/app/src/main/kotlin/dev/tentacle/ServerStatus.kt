package dev.tentacle

import java.util.concurrent.CopyOnWriteArraySet

/** Process-local service state with immediate and live delivery to the UI. */
internal object ServerStatus {
    data class State(val running: Boolean = false, val error: String? = null)

    private val listeners = CopyOnWriteArraySet<(State) -> Unit>()

    @Volatile
    var value = State()
        private set

    fun publish(state: State) {
        value = state
        listeners.forEach { it(state) }
    }

    fun observe(listener: (State) -> Unit): AutoCloseable {
        listeners.add(listener)
        listener(value)
        return AutoCloseable { listeners.remove(listener) }
    }
}
