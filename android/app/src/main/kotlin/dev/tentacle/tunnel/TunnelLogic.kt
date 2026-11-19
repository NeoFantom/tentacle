package dev.tentacle.tunnel

import kotlin.math.min
import kotlin.random.Random

object TunnelLogic {
    enum class HostKeyDecision { TRUST_FIRST, TRUSTED, CHANGED }

    fun authorizedKeysLine(publicKey: String, remotePort: Int): String {
        require(remotePort in 1..65535)
        return "restrict,port-forwarding,permitlisten=\"127.0.0.1:$remotePort\"," +
            "command=\"/usr/sbin/nologin\" ${publicKey.trim()}"
    }

    fun hostKeyDecision(
        endpoint: String,
        key: String,
        trustedEndpoint: String,
        trustedKey: String,
    ): HostKeyDecision = when {
        trustedEndpoint != endpoint || trustedKey.isBlank() -> HostKeyDecision.TRUST_FIRST
        trustedKey == key -> HostKeyDecision.TRUSTED
        else -> HostKeyDecision.CHANGED
    }

    fun backoffBaseMillis(attempt: Int): Long {
        require(attempt >= 0)
        return min(60_000L, 1_000L shl min(attempt, 6))
    }

    fun backoffMillis(attempt: Int, random: Random = Random.Default): Long {
        val base = backoffBaseMillis(attempt)
        val jitter = (base / 5).coerceAtLeast(1)
        return (base - jitter + random.nextLong(jitter * 2 + 1)).coerceIn(1_000L, 60_000L)
    }
}
