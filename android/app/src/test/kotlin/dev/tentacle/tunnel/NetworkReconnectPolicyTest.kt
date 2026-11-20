package dev.tentacle.tunnel

import dev.tentacle.tunnel.NetworkReconnectPolicy.Connection
import dev.tentacle.tunnel.NetworkReconnectPolicy.Decision
import dev.tentacle.tunnel.NetworkReconnectPolicy.Companion.NETWORK_DEBOUNCE_MILLIS
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NetworkReconnectPolicyTest {
    private val original = NetworkSnapshot(1, "test0", setOf("192.0.2.1/24"))
    private val replacement = NetworkSnapshot(2, "test1", setOf("192.0.2.2/24"))

    @Test fun `registration callbacks for current network do not reconnect`() {
        val fixture = Fixture()
        fixture.policy.available(original.id)
        fixture.policy.linkPropertiesChanged(original)
        fixture.settle()
        assertEquals(emptyList<Decision>(), fixture.decisions)
        assertTrue(fixture.policy.canConnect)
    }

    @Test fun `own network lost and new default in one burst reconnects once without probe`() {
        val fixture = Fixture()
        fixture.policy.lost(original.id)
        fixture.clock.advance(100)
        fixture.policy.available(replacement.id)
        fixture.clock.advance(100)
        fixture.policy.linkPropertiesChanged(replacement)
        fixture.settle()
        assertEquals(listOf(Decision.RECONNECT), fixture.decisions)
        assertEquals(null, fixture.policy.probeRevision)
    }

    @Test fun `available and identical link properties probe once`() {
        val fixture = Fixture()
        fixture.policy.available(replacement.id)
        fixture.policy.linkPropertiesChanged(replacement)
        fixture.clock.advance(100)
        fixture.policy.linkPropertiesChanged(replacement.copy(addresses = replacement.addresses.toSet()))
        fixture.settle()
        assertEquals(listOf(Decision.PROBE), fixture.decisions)
    }

    @Test fun `address change on same network probes once`() {
        val fixture = Fixture()
        fixture.policy.linkPropertiesChanged(original.copy(addresses = setOf("192.0.2.3/24")))
        fixture.settle()
        assertEquals(listOf(Decision.PROBE), fixture.decisions)
    }

    @Test fun `interface change on same network probes once`() {
        val fixture = Fixture()
        fixture.policy.linkPropertiesChanged(original.copy(interfaceName = "test2"))
        fixture.settle()
        assertEquals(listOf(Decision.PROBE), fixture.decisions)
    }

    @Test fun `repeated identical link properties do not reconnect`() {
        val fixture = Fixture()
        repeat(10) {
            fixture.policy.linkPropertiesChanged(original)
            fixture.settle()
        }
        assertEquals(emptyList<Decision>(), fixture.decisions)
    }

    @Test fun `address ordering does not change the network`() {
        val network = original.copy(addresses = linkedSetOf("192.0.2.1/24", "2001:db8::1/64"))
        val fixture = Fixture(network)
        fixture.policy.linkPropertiesChanged(network.copy(addresses = network.addresses.reversed().toSet()))
        fixture.settle()
        assertEquals(emptyList<Decision>(), fixture.decisions)
    }

    @Test fun `own network loss requires replacement even if same network returns within window`() {
        val fixture = Fixture()
        fixture.policy.lost(original.id)
        assertFalse(fixture.policy.canConnect)
        fixture.clock.advance(100)
        fixture.policy.available(original.id)
        fixture.policy.linkPropertiesChanged(original)
        fixture.settle()
        assertEquals(listOf(Decision.RECONNECT), fixture.decisions)
        assertTrue(fixture.policy.canConnect)
    }

    @Test fun `lost then same network within window reconnects dead session`() {
        val fixture = Fixture()
        fixture.connection = Connection()
        fixture.policy.lost(original.id)
        fixture.policy.available(original.id)
        fixture.policy.linkPropertiesChanged(original)
        fixture.settle()
        assertEquals(listOf(Decision.RECONNECT), fixture.decisions)
    }

    @Test fun `own network lost tears down and network return reconnects without probe`() {
        val fixture = Fixture()
        fixture.policy.lost(original.id)
        assertFalse(fixture.policy.canConnect)
        fixture.clock.advance(NETWORK_DEBOUNCE_MILLIS - 1)
        assertEquals(emptyList<Decision>(), fixture.decisions)
        fixture.clock.advance(1)
        assertEquals(listOf(Decision.OFFLINE), fixture.decisions)
        fixture.connection = Connection() // The manager tears down at the offline decision.
        fixture.clock.advance(60_000)
        assertFalse(fixture.policy.canConnect)
        assertEquals(listOf(Decision.OFFLINE), fixture.decisions)
        fixture.policy.available(replacement.id)
        fixture.policy.linkPropertiesChanged(replacement)
        assertFalse(fixture.policy.canConnect)
        fixture.settle()
        assertTrue(fixture.policy.canConnect)
        assertEquals(listOf(Decision.OFFLINE, Decision.RECONNECT), fixture.decisions)
    }

    @Test fun `starting offline waits for a network`() {
        val fixture = Fixture(null)
        fixture.clock.advance(60_000)
        assertFalse(fixture.policy.canConnect)
        assertEquals(emptyList<Decision>(), fixture.decisions)
        fixture.policy.available(original.id)
        fixture.policy.linkPropertiesChanged(original)
        fixture.settle()
        assertEquals(listOf(Decision.RECONNECT), fixture.decisions)
    }

    @Test fun `two real changes far apart probe twice`() {
        val fixture = Fixture()
        fixture.policy.available(replacement.id)
        fixture.policy.linkPropertiesChanged(replacement)
        fixture.settle()
        fixture.clock.advance(5_000)
        fixture.policy.available(original.id)
        fixture.policy.linkPropertiesChanged(original)
        fixture.settle()
        assertEquals(listOf(Decision.PROBE, Decision.PROBE), fixture.decisions)
    }

    @Test fun `own network loss cancels attempt even when same network returns`() {
        val fixture = Fixture()
        fixture.connection = Connection(original, connecting = true)
        fixture.policy.lost(original.id)
        fixture.policy.available(original.id)
        fixture.policy.linkPropertiesChanged(original)
        fixture.settle()
        assertEquals(listOf(Decision.RECONNECT), fixture.decisions)
    }

    @Test fun `real change replaces connection attempt for old network`() {
        val fixture = Fixture()
        fixture.connection = Connection(original, connecting = true)
        fixture.policy.available(replacement.id)
        fixture.policy.linkPropertiesChanged(replacement)
        fixture.settle()
        assertEquals(listOf(Decision.RECONNECT), fixture.decisions)
        fixture.connection = Connection(replacement, connecting = true)
        fixture.policy.available(replacement.id)
        fixture.policy.linkPropertiesChanged(replacement)
        fixture.settle()
        assertEquals(listOf(Decision.RECONNECT), fixture.decisions)
    }

    @Test fun `own network loss after new default still reconnects once`() {
        val fixture = Fixture()
        fixture.policy.available(replacement.id)
        fixture.policy.linkPropertiesChanged(replacement)
        fixture.policy.lost(original.id)
        fixture.policy.linkPropertiesChanged(original)
        fixture.settle()
        assertEquals(replacement, fixture.policy.currentNetwork)
        assertEquals(listOf(Decision.RECONNECT), fixture.decisions)
    }

    @Test fun `debounce waits only for changed callbacks`() {
        val fixture = Fixture()
        fixture.policy.available(replacement.id)
        fixture.policy.linkPropertiesChanged(replacement)
        fixture.clock.advance(NETWORK_DEBOUNCE_MILLIS - 1)
        assertEquals(emptyList<Decision>(), fixture.decisions)
        fixture.policy.linkPropertiesChanged(replacement)
        fixture.clock.advance(1)
        assertEquals(listOf(Decision.PROBE), fixture.decisions)
    }

    @Test fun `address change reverted within burst keeps existing session`() {
        val fixture = Fixture()
        fixture.policy.linkPropertiesChanged(original.copy(addresses = emptySet()))
        fixture.policy.linkPropertiesChanged(original)
        fixture.settle()
        assertEquals(listOf(Decision.UNCHANGED), fixture.decisions)
        assertTrue(fixture.policy.canConnect)
    }

    @Test fun `cancelled debounce tasks cannot make stale decisions`() {
        val fixture = Fixture(deliverCancelled = true)
        fixture.policy.lost(original.id)
        fixture.clock.advance(100)
        fixture.policy.available(replacement.id)
        fixture.policy.linkPropertiesChanged(replacement)
        fixture.clock.advance(NETWORK_DEBOUNCE_MILLIS - 100)
        assertEquals(emptyList<Decision>(), fixture.decisions)
        fixture.clock.advance(100)
        assertEquals(listOf(Decision.RECONNECT), fixture.decisions)
    }

    @Test fun `close cancels pending decisions and ignores later callbacks`() {
        val fixture = Fixture(deliverCancelled = true)
        fixture.policy.lost(original.id)
        fixture.policy.close()
        fixture.policy.available(replacement.id)
        fixture.policy.linkPropertiesChanged(replacement)
        fixture.settle()
        assertFalse(fixture.policy.canConnect)
        assertEquals(emptyList<Decision>(), fixture.decisions)
    }

    @Test fun `default switch while own network stays up probes and keeps original ownership`() {
        val fixture = Fixture()
        fixture.policy.available(replacement.id)
        fixture.policy.linkPropertiesChanged(replacement)
        fixture.policy.defaultLost(original.id)
        fixture.settle()
        fixture.policy.probeCompleted(fixture.policy.probeRevision!!, answered = true)
        assertEquals(listOf(Decision.PROBE, Decision.KEEP_ALIVE_PROBED), fixture.decisions)
        assertEquals(original, fixture.connection.network)
        fixture.policy.lost(original.id)
        fixture.settle()
        assertEquals(replacement, fixture.policy.currentNetwork)
        assertEquals(listOf(Decision.PROBE, Decision.KEEP_ALIVE_PROBED, Decision.RECONNECT), fixture.decisions)
    }

    @Test fun `same network returning after offline reconnects without probe`() {
        val fixture = Fixture()
        fixture.policy.lost(original.id)
        fixture.settle()
        fixture.connection = Connection()
        fixture.policy.available(original.id)
        fixture.policy.linkPropertiesChanged(original)
        fixture.settle()
        assertEquals(listOf(Decision.OFFLINE, Decision.RECONNECT), fixture.decisions)
        assertEquals(null, fixture.policy.probeRevision)
    }

    @Test fun `default loss before new default probes surviving session`() {
        val fixture = Fixture()
        fixture.policy.defaultLost(original.id)
        fixture.clock.advance(100)
        fixture.policy.available(replacement.id)
        fixture.policy.linkPropertiesChanged(replacement)
        fixture.settle()
        assertEquals(listOf(Decision.PROBE), fixture.decisions)
        fixture.policy.probeCompleted(fixture.policy.probeRevision!!, answered = true)
        assertEquals(listOf(Decision.PROBE, Decision.KEEP_ALIVE_PROBED), fixture.decisions)
    }

    @Test fun `late loss of old network leaves replacement session alone`() {
        val fixture = Fixture()
        fixture.policy.available(replacement.id)
        fixture.policy.linkPropertiesChanged(replacement)
        fixture.settle()
        fixture.policy.probeCompleted(fixture.policy.probeRevision!!, answered = false)
        fixture.connection = Connection(replacement, connected = true)
        fixture.policy.lost(original.id)
        fixture.policy.defaultLost(original.id)
        fixture.policy.linkPropertiesChanged(original)
        fixture.settle()
        assertEquals(listOf(Decision.PROBE, Decision.RECONNECT), fixture.decisions)
        assertTrue(fixture.policy.canConnect)
    }

    @Test fun `default lost without own loss probes and keeps answering session`() {
        val fixture = Fixture()
        fixture.policy.defaultLost(original.id)
        fixture.settle()
        assertFalse(fixture.policy.canConnect)
        assertEquals(listOf(Decision.PROBE), fixture.decisions)
        fixture.policy.probeCompleted(fixture.policy.probeRevision!!, answered = true)
        fixture.clock.advance(60_000)
        assertEquals(listOf(Decision.PROBE, Decision.KEEP_ALIVE_PROBED), fixture.decisions)
        assertEquals(original, fixture.connection.network)
    }

    @Test fun `default lost without own loss defers reconnect after no probe answer`() {
        val fixture = Fixture()
        fixture.policy.defaultLost(original.id)
        fixture.settle()
        fixture.policy.probeCompleted(fixture.policy.probeRevision!!, answered = false)
        fixture.connection = Connection() // The manager disconnects and defers the attempt.
        fixture.clock.advance(60_000)
        assertFalse(fixture.policy.canConnect)
        assertEquals(listOf(Decision.PROBE, Decision.RECONNECT), fixture.decisions)
        fixture.policy.available(replacement.id)
        fixture.policy.linkPropertiesChanged(replacement)
        fixture.settle()
        assertTrue(fixture.policy.canConnect)
        assertEquals(listOf(Decision.PROBE, Decision.RECONNECT, Decision.RECONNECT), fixture.decisions)
        assertEquals(null, fixture.policy.probeRevision)
    }

    @Test fun `default lost without own loss preserves current attempt`() {
        val fixture = Fixture()
        fixture.connection = Connection(original, connecting = true)
        fixture.policy.defaultLost(original.id)
        fixture.settle()
        assertEquals(listOf(Decision.KEEP_CONNECTING), fixture.decisions)
        fixture.policy.lost(original.id)
        fixture.settle()
        assertEquals(listOf(Decision.KEEP_CONNECTING, Decision.OFFLINE), fixture.decisions)
    }

    @Test fun `actual own loss while default absent cancels probe and goes offline`() {
        val fixture = Fixture()
        fixture.policy.defaultLost(original.id)
        fixture.settle()
        val revision = fixture.policy.probeRevision!!
        fixture.policy.lost(original.id)
        fixture.policy.probeCompleted(revision, answered = true)
        fixture.settle()
        assertFalse(fixture.policy.canConnect)
        assertEquals(listOf(Decision.PROBE, Decision.OFFLINE), fixture.decisions)
    }

    @Test fun `unrelated network loss does not disturb session or attempt`() {
        for (connection in listOf(Connection(original, connected = true), Connection(original, connecting = true))) {
            val fixture = Fixture()
            fixture.connection = connection
            fixture.policy.lost(replacement.id)
            fixture.settle()
            assertEquals(emptyList<Decision>(), fixture.decisions)
            assertTrue(fixture.policy.canConnect)
        }
    }

    @Test fun `own network property change after default switch probes once`() {
        val fixture = Fixture()
        fixture.policy.available(replacement.id)
        fixture.settle()
        fixture.policy.probeCompleted(fixture.policy.probeRevision!!, answered = true)
        val changed = original.copy(interfaceName = "test2")
        fixture.policy.linkPropertiesChanged(changed)
        fixture.policy.linkPropertiesChanged(changed)
        fixture.settle()
        assertEquals(listOf(Decision.PROBE, Decision.KEEP_ALIVE_PROBED, Decision.PROBE), fixture.decisions)
    }

    @Test fun `default callback loss and replacement preserve matching attempt`() {
        val fixture = Fixture()
        fixture.connection = Connection(replacement, connecting = true)
        fixture.policy.defaultLost(original.id)
        fixture.policy.available(replacement.id)
        fixture.policy.linkPropertiesChanged(replacement)
        fixture.settle()
        assertEquals(listOf(Decision.KEEP_CONNECTING), fixture.decisions)
    }

    @Test fun `own network loss invalidates in flight probe and reconnects once`() {
        val fixture = Fixture()
        fixture.policy.available(replacement.id)
        fixture.settle()
        val revision = fixture.policy.probeRevision!!
        fixture.policy.lost(original.id)
        assertFalse(fixture.policy.isProbeCurrent(revision))
        fixture.policy.probeCompleted(revision, answered = true)
        fixture.settle()
        fixture.policy.probeCompleted(revision, answered = false)
        assertEquals(listOf(Decision.PROBE, Decision.RECONNECT), fixture.decisions)
    }

    @Test fun `probe timeout reconnects exactly once`() {
        val fixture = Fixture()
        fixture.policy.available(replacement.id)
        fixture.settle()
        val revision = fixture.policy.probeRevision!!
        fixture.policy.probeCompleted(revision, answered = false)
        fixture.policy.probeCompleted(revision, answered = false)
        assertEquals(listOf(Decision.PROBE, Decision.RECONNECT), fixture.decisions)
    }

    @Test fun `other default loss invalidates probe then probes surviving own network`() {
        val fixture = Fixture()
        fixture.policy.available(replacement.id)
        fixture.settle()
        val revision = fixture.policy.probeRevision!!
        fixture.policy.lost(replacement.id)
        assertFalse(fixture.policy.isProbeCurrent(revision))
        fixture.policy.probeCompleted(revision, answered = false)
        fixture.settle()
        assertEquals(listOf(Decision.PROBE, Decision.PROBE), fixture.decisions)
    }

    @Test fun `new probe ignores old answer and accepts current answer once`() {
        val fixture = Fixture()
        fixture.policy.available(replacement.id)
        fixture.settle()
        val old = fixture.policy.probeRevision!!
        fixture.policy.linkPropertiesChanged(replacement)
        fixture.settle()
        val current = fixture.policy.probeRevision!!
        fixture.policy.probeCompleted(old, answered = false)
        fixture.policy.probeCompleted(current, answered = true)
        fixture.policy.probeCompleted(current, answered = false)
        assertEquals(listOf(Decision.PROBE, Decision.PROBE, Decision.KEEP_ALIVE_PROBED), fixture.decisions)
    }

    @Test fun `callback burst reverting during probe starts a fresh probe`() {
        val fixture = Fixture()
        fixture.policy.available(replacement.id)
        fixture.policy.linkPropertiesChanged(replacement)
        fixture.settle()
        val old = fixture.policy.probeRevision!!
        fixture.policy.linkPropertiesChanged(replacement.copy(addresses = emptySet()))
        fixture.policy.linkPropertiesChanged(replacement)
        fixture.policy.probeCompleted(old, answered = true)
        fixture.settle()
        assertEquals(listOf(Decision.PROBE, Decision.PROBE), fixture.decisions)
        assertTrue(fixture.policy.isProbeCurrent(fixture.policy.probeRevision!!))
    }

    @Test fun `closed policy ignores probe result`() {
        val fixture = Fixture()
        fixture.policy.available(replacement.id)
        fixture.settle()
        val revision = fixture.policy.probeRevision!!
        fixture.policy.close()
        fixture.policy.probeCompleted(revision, answered = true)
        assertEquals(listOf(Decision.PROBE), fixture.decisions)
    }

    @Test fun `opened probe channel proves session alive`() {
        assertTrue(SessionProbeResult(sessionConnected = true, channelOpened = true).answered)
    }

    @Test fun `all server channel open failures prove session alive`() {
        for (reason in listOf(1, 2, 3, 4, 42)) {
            assertTrue(SessionProbeResult(true, false, reason).answered)
        }
    }

    @Test fun `timeout or io failure without reply does not prove session alive`() {
        assertFalse(SessionProbeResult(true, false, -1).answered)
    }

    @Test fun `disconnected session is dead even if channel had answered`() {
        assertFalse(SessionProbeResult(false, true).answered)
        assertFalse(SessionProbeResult(false, false, 2).answered)
    }

    private inner class Fixture(initial: NetworkSnapshot? = original, deliverCancelled: Boolean = false) {
        val clock = FakeScheduler(deliverCancelled)
        val decisions = mutableListOf<Decision>()
        var connection = Connection(initial, connected = initial != null)
        val policy = NetworkReconnectPolicy(initial, clock, { connection }, decisions::add)
        fun settle() = clock.advance(NETWORK_DEBOUNCE_MILLIS)
    }

    private class FakeScheduler(private val deliverCancelled: Boolean) : NetworkReconnectPolicy.Scheduler {
        private data class Task(val due: Long, val run: () -> Unit, var cancelled: Boolean = false)
        private var now = 0L
        private val tasks = mutableListOf<Task>()

        override fun schedule(delayMillis: Long, task: () -> Unit): NetworkReconnectPolicy.Cancellation {
            val scheduled = Task(now + delayMillis, task)
            tasks += scheduled
            return NetworkReconnectPolicy.Cancellation { scheduled.cancelled = true }
        }

        fun advance(millis: Long) {
            val target = now + millis
            while (true) {
                val next = tasks.minByOrNull { it.due }?.takeIf { it.due <= target } ?: break
                tasks.remove(next)
                now = next.due
                if (!next.cancelled || deliverCancelled) next.run()
            }
            now = target
        }
    }
}
