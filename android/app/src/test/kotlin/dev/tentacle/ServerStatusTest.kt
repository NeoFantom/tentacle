package dev.tentacle

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ServerStatusTest {
    @Test
    fun `observer receives current state and live changes until closed`() {
        ServerStatus.publish(ServerStatus.State())
        val observed = mutableListOf<ServerStatus.State>()
        val subscription = ServerStatus.observe(observed::add)

        val running = ServerStatus.State(running = true)
        ServerStatus.publish(running)
        subscription.close()
        ServerStatus.publish(ServerStatus.State(error = "stopped"))

        assertEquals(listOf(ServerStatus.State(), running), observed)
    }
}
