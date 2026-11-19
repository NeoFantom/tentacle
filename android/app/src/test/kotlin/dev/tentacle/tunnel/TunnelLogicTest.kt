package dev.tentacle.tunnel

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.random.Random

class TunnelLogicTest {
    @Test fun `authorized keys line restricts the exact loopback port`() {
        assertEquals(
            "restrict,port-forwarding,permitlisten=\"127.0.0.1:8765\",command=\"/usr/sbin/nologin\" " +
                "ecdsa-sha2-nistp256 AAAAexample tentacle-phone",
            TunnelLogic.authorizedKeysLine("ecdsa-sha2-nistp256 AAAAexample tentacle-phone", 8765),
        )
    }

    @Test fun `backoff doubles and caps at sixty seconds`() {
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 32_000L, 60_000L, 60_000L),
            (0..7).map(TunnelLogic::backoffBaseMillis))
        (0..7).forEach { attempt ->
            val base = TunnelLogic.backoffBaseMillis(attempt)
            val actual = TunnelLogic.backoffMillis(attempt, Random(attempt))
            assertTrue(actual in (base - base / 5)..(base + base / 5))
            assertTrue(actual <= 60_000L)
        }
    }

    @Test fun `host key decision is endpoint specific and detects changes`() {
        assertEquals(TunnelLogic.HostKeyDecision.TRUST_FIRST,
            TunnelLogic.hostKeyDecision("a:22", "key-a", "", ""))
        assertEquals(TunnelLogic.HostKeyDecision.TRUST_FIRST,
            TunnelLogic.hostKeyDecision("b:22", "key-b", "a:22", "key-a"))
        assertEquals(TunnelLogic.HostKeyDecision.TRUSTED,
            TunnelLogic.hostKeyDecision("a:22", "key-a", "a:22", "key-a"))
        assertEquals(TunnelLogic.HostKeyDecision.CHANGED,
            TunnelLogic.hostKeyDecision("a:22", "key-b", "a:22", "key-a"))
    }
}
