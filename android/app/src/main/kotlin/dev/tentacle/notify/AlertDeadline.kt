package dev.tentacle.notify

/** A notification gets one alarm budget, including activity recreation and delayed full-screen delivery. */
internal class AlertDeadline(private val clock: () -> Long) {
    private val deadlines = linkedMapOf<String, Long>()

    @Synchronized fun register(id: String) {
        // Called only for a fresh store delivery. Identical retries never reach this boundary.
        // An ID reused after store eviction gets a fresh budget as a new notification.
        deadlines.remove(id)
        deadlines[id] = clock() + MAX_RING_MS
        while (deadlines.size > 50) deadlines.remove(deadlines.keys.first())
    }

    @Synchronized fun remaining(id: String): Long =
        deadlines[id]?.let { (it - clock()).coerceIn(0, MAX_RING_MS) } ?: 0

    companion object { const val MAX_RING_MS = 120_000L }
}

/** Re-arm the remaining budget when a stopped activity is recreated, without granting more time. */
internal class AlarmTimeouts(
    private val deadlines: AlertDeadline,
    private val schedule: (Runnable, Long) -> Unit,
    private val remove: (Runnable) -> Unit,
) {
    private val tasks = mutableMapOf<String, Runnable>()
    val ids: List<String> get() = tasks.keys.toList()

    fun arm(id: String, onTimeout: () -> Unit) {
        cancel(id)
        val task = Runnable {
            tasks.remove(id)
            onTimeout()
        }
        tasks[id] = task
        schedule(task, deadlines.remaining(id))
    }

    fun cancel(id: String) { tasks.remove(id)?.let(remove) }
}
