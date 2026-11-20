package dev.tentacle.tunnel

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PortConflictRetryPolicyTest {
    private var now = 100_000L
    private val policy = PortConflictRetryPolicy({ now }, TunnelLogic::backoffBaseMillis)

    @Test fun `port conflict after replacement retries every second without showing error`() {
        policy.sessionReplaced()
        policy.connecting()
        repeat(30) {
            val retry = policy.failure(portInUse = true)
            assertEquals(1_000L, retry.delayMillis)
            assertFalse(retry.showError)
            now += retry.delayMillis
        }
    }

    @Test fun `port conflict at thirty seconds resumes exponential backoff and error`() {
        policy.sessionReplaced()
        policy.connecting()
        now += 29_999
        assertFalse(policy.failure(portInUse = true).showError)
        now++
        repeat(4) { attempt ->
            val retry = policy.failure(portInUse = true)
            assertEquals(TunnelLogic.backoffBaseMillis(attempt), retry.delayMillis)
            assertTrue(retry.showError)
        }
    }

    @Test fun `port conflict without replacement shows error and exponential backoff`() {
        repeat(4) { attempt ->
            val retry = policy.failure(portInUse = true)
            assertEquals(TunnelLogic.backoffBaseMillis(attempt), retry.delayMillis)
            assertTrue(retry.showError)
        }
    }

    @Test fun `other failures during grace still show error and exponential backoff`() {
        policy.sessionReplaced()
        policy.connecting()
        repeat(3) { attempt ->
            val retry = policy.failure(portInUse = false)
            assertEquals(TunnelLogic.backoffBaseMillis(attempt), retry.delayMillis)
            assertTrue(retry.showError)
        }
    }

    @Test fun `grace starts at first recovery attempt rather than first port conflict`() {
        policy.sessionReplaced()
        policy.connecting()
        now += 30_000
        assertTrue(policy.failure(portInUse = true).showError)
    }

    @Test fun `long offline interval does not consume pending recovery grace`() {
        policy.sessionReplaced()
        now += 60_000
        policy.resetBackoff()
        policy.connecting()
        val retry = policy.failure(portInUse = true)
        assertFalse(retry.showError)
        assertEquals(1_000L, retry.delayMillis)
    }

    @Test fun `repeated connection attempts do not extend grace`() {
        policy.sessionReplaced()
        policy.connecting()
        repeat(30) {
            policy.connecting()
            assertFalse(policy.failure(portInUse = true).showError)
            now += 1_000
        }
        policy.connecting()
        assertTrue(policy.failure(portInUse = true).showError)
    }

    @Test fun `successful connection or manual reset clears replacement grace`() {
        policy.sessionReplaced()
        policy.connecting()
        policy.failure(portInUse = true)
        policy.reset()
        val retry = policy.failure(portInUse = true)
        assertTrue(retry.showError)
        assertEquals(1_000L, retry.delayMillis)
    }

    @Test fun `new replacement resets previous backoff and starts fresh grace`() {
        repeat(4) { policy.failure(portInUse = true) }
        policy.sessionReplaced()
        policy.connecting()
        assertFalse(policy.failure(portInUse = true).showError)
        now += 30_000
        assertEquals(1_000L, policy.failure(portInUse = true).delayMillis)
    }
}
